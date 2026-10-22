package com.chaconez.knowengine.cache.service;

import com.chaconez.knowengine.cache.config.QaCacheProperties;
import com.chaconez.knowengine.cache.entity.QaCacheEntry;
import com.chaconez.knowengine.cache.entity.QaMiningEvidence;
import com.chaconez.knowengine.cache.util.QaCacheJsonUtil;
import com.chaconez.knowengine.chat.entity.ChatMessage;
import com.chaconez.knowengine.document.event.DocumentInvalidatedEvent;
import com.chaconez.knowengine.document.event.SegmentEditedEvent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 问答缓存应用服务，负责历史问答提取、查询、发布和定时准出。
 */
@Service
@Slf4j
public class QaCacheService {

    /** 模型候选答案的最低质量置信度。 */
    private static final double MIN_CANDIDATE_QUALITY = 0.9;

    /** 每个高频问题默认最多提供 100 个会话的证据，且不少于最低频次要求。 */
    private static final int MAX_EVIDENCE_COUNT = 100;

    private final QaCacheEntryService entryService;

    private final QaCacheProperties properties;

    private final QaCacheVectorService vectors;

    private final QaCacheCurator curator;

    private final QaCacheLifecycleService lifecycle;

    /**
     * 缓存查询结果，包含答案文本和引用文档。
     */
    public record CachedAnswer(String answer, List<ChatMessage.RagReference> references) {}

    /**
     * 创建缓存业务服务。
     */
    @Autowired
    public QaCacheService(
            QaCacheEntryService entryService,
            QaCacheProperties properties,
            QaCacheVectorService vectors,
            ChatModel model,
            QaCacheLifecycleService lifecycle) {
        this(entryService, properties, vectors, AiServices.create(QaCacheCurator.class, model), lifecycle);
    }

    QaCacheService(
            QaCacheEntryService entryService,
            QaCacheProperties properties,
            QaCacheVectorService vectors,
            QaCacheCurator curator,
            QaCacheLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
        this.entryService = entryService;
        this.properties = properties;
        this.vectors = vectors;
        this.curator = curator;
    }

    /**
     * 检索首问缓存并返回可用答案。
     */
    public Optional<CachedAnswer> lookup(
            String question, String conversationId, String messageId, String assistantMessageId) {
        if (!properties.isEnabled()
                || question == null
                || question.length() < 5
                || question.length() > 500) {
            return Optional.empty();
        }
        try {
            // 仅对会话首问检索缓存。
            if (entryService.hasHistory(conversationId, messageId)) {
                return Optional.empty();
            }
            double threshold = properties.getMinScore();
            if (!Double.isFinite(threshold) || threshold < 0.8 || threshold > 1) {
                return Optional.empty();
            }
            for (QaCacheVectorService.Match match : vectors.search(question, threshold)) {
                if (!Double.isFinite(match.score()) || match.score() < threshold) {
                    continue;
                }
                QaCacheEntry entry = entryService.getById(match.id());
                if (isUsable(entry, LocalDateTime.now())
                        && lifecycle.ensureCurrent(entry.getId())) {
                    entryService.markCacheAnswer(assistantMessageId, entry.getId());
                    try {
                        entryService.hit(entry.getId());
                    } catch (Exception e) {
                        log.debug("缓存计数更新失败", e);
                    }
                    List<ChatMessage.RagReference> refs = entryService.resolveReferences(entry.getId());
                    return Optional.of(new CachedAnswer(entry.getAnswer(), refs));
                }
            }
        } catch (Exception e) {
            log.warn("问答缓存查询失败，回退原问答流程", e);
        }
        return Optional.empty();
    }

    static boolean isUsable(QaCacheEntry entry, LocalDateTime now) {
        return entry != null
                && "ACTIVE".equals(entry.getStatus())
                && entry.getExpiresAt() != null
                && entry.getExpiresAt().isAfter(now)
                && entry.getAnswer() != null
                && !entry.getAnswer().isBlank();
    }

    /**
     * 整理最近七天的高频问答，返回新增待审核条目数。
     */
    public int curate() {
        if (!properties.isEnabled()) {
            return 0;
        }
        LocalDateTime until = LocalDateTime.now();
        LocalDateTime since = until.minusDays(7);
        int minFrequency = Math.max(3, properties.getMinFrequency());

        List<QaMiningEvidence> history = loadHistory(since, until);
        if (history.stream().map(QaMiningEvidence::getConversationId).distinct().count() < minFrequency) {
            return 0;
        }

        int inserted = 0;
        for (List<QaMiningEvidence> group : groupQuestions(history)) {
            if (curateGroup(group, minFrequency, since, until)) {
                inserted++;
            }
        }
        return inserted;
    }

    /**
     * 分页读取指定时间窗口内的合格问答。
     */
    private List<QaMiningEvidence> loadHistory(LocalDateTime since, LocalDateTime until) {
        List<QaMiningEvidence> history = new ArrayList<>();
        long afterId = 0;
        while (true) {
            List<QaMiningEvidence> page = entryService.miningHistory(since, until, afterId, 200);
            history.addAll(page);
            if (page.size() < 200) {
                return history;
            }
            afterId = page.getLast().getAnswerId();
        }
    }

    /**
     * 调用模型归组并校验结果。
     */
    private List<List<QaMiningEvidence>> groupQuestions(List<QaMiningEvidence> history) {
        List<Map<String, String>> questions = history.stream()
                .map(row -> Map.of("id", row.getQuestionMessageId(), "question", row.getQuestion()))
                .toList();
        QaCacheCurator.Grouping grouping = curator.group(QaCacheJsonUtil.write(questions));
        return validateGroups(grouping, history);
    }

    /**
     * 按独立会话统计频次并整理高频问题。
     */
    private boolean curateGroup(List<QaMiningEvidence> group, int minFrequency,
                                LocalDateTime since, LocalDateTime until) {
        Map<String, QaMiningEvidence> conversations = new LinkedHashMap<>();
        for (QaMiningEvidence evidence : group.reversed()) {
            conversations.putIfAbsent(evidence.getConversationId(), evidence);
        }
        // 过滤掉来源文档已失效（删除、版本变更等）的证据。
        conversations.values().removeIf(e -> !hasValidReferences(e.getRefs()));
        int frequency = conversations.size();
        if (frequency < minFrequency) {
            return false;
        }

        // 按归一化问题键精确去重。
        List<String> questionKeys = group.stream()
                .map(evidence -> questionKey(evidence.getQuestion())).distinct().sorted().toList();
        if (questionKeys.stream().anyMatch(key -> entryService.openMiningCandidates(key) > 0)) {
            return false;
        }

        int evidenceLimit = Math.max(minFrequency, MAX_EVIDENCE_COUNT);
        List<QaMiningEvidence> input = conversations.values().stream().limit(evidenceLimit).toList();
        List<QaCacheEntryService.Source> sources = input.stream().map(QaMiningEvidence::source).toList();
        String historyJson = QaCacheJsonUtil.write(Map.of("frequency", frequency, "sources", sources));
        QaCacheCurator.Result result = curator.curate(historyJson);
        if (result == null || result.candidates() == null) {
            throw new IllegalStateException("问答整理模型返回无效结果");
        }

        // 复查整组频次依据，处理期间新增差评、删除或改写时不保存此次结果。
        List<Long> answerIds = group.stream().map(QaMiningEvidence::getAnswerId).toList();
        List<QaMiningEvidence> current = entryService.recheckMining(answerIds, since, until);
        if (!new HashSet<>(group).equals(new HashSet<>(current))) {
            return false;
        }
        return saveCandidate(questionKeys.getFirst(), input, result, frequency);
    }

    /**
     * 校验归组是否完整且无重复或未知问题。
     */
    private List<List<QaMiningEvidence>> validateGroups(
            QaCacheCurator.Grouping result, List<QaMiningEvidence> history) {
        if (result == null || result.groups() == null) {
            throw new IllegalStateException("问题归组模型返回无效结果");
        }
        Map<String, QaMiningEvidence> byId =
                history.stream()
                        .collect(
                                Collectors.toMap(
                                        QaMiningEvidence::getQuestionMessageId,
                                        Function.identity()));
        Set<String> assigned = new HashSet<>();
        List<List<QaMiningEvidence>> groups = new ArrayList<>();
        for (QaCacheCurator.Group group : result.groups()) {
            if (group == null || group.questionIds() == null || group.questionIds().isEmpty()) {
                throw new IllegalStateException("问题归组包含空组");
            }
            List<QaMiningEvidence> members = new ArrayList<>();
            for (String id : group.questionIds()) {
                if (!byId.containsKey(id) || !assigned.add(id)) {
                    throw new IllegalStateException("问题归组包含未知或重复的问题 ID");
                }
                members.add(byId.get(id));
            }
            members.sort(Comparator.comparing(QaMiningEvidence::getAnswerId));
            groups.add(members);
        }
        if (assigned.size() != byId.size()) {
            throw new IllegalStateException("问题归组遗漏输入问题");
        }
        return groups;
    }

    /**
     * 校验候选答案并幂等保存。
     */
    private boolean saveCandidate(
            String key,
            List<QaMiningEvidence> input,
            QaCacheCurator.Result result,
            long frequency) {
        Map<String, QaCacheEntryService.Source> byId =
                input.stream()
                        .map(QaMiningEvidence::source)
                        .collect(
                                Collectors.toMap(
                                        QaCacheEntryService.Source::id, Function.identity()));
        for (QaCacheCurator.Candidate candidate : result.candidates()) {
            if (!validCandidate(candidate, byId, properties)) {
                continue;
            }
            if (hasSimilarCachedQuestion(candidate.question().trim())) {
                return false;
            }
            List<QaCacheEntryService.Source> evidence =
                    candidate.sourceIds().stream().distinct().sorted().map(byId::get).toList();
            QaCacheEntry entry = new QaCacheEntry();
            entry.setId(UUID.randomUUID().toString());
            entry.setQuestionKey(key);
            entry.setQuestion(candidate.question().trim());
            entry.setAnswer(candidate.answer().trim());
            entry.setSources(QaCacheJsonUtil.write(evidence));
            entry.setQuality(candidate.quality());
            entry.setReason(candidate.reason());
            entry.setFrequency(Math.toIntExact(frequency));
            entry.setFingerprint(
                    digest(
                            key
                                    + ":"
                                    + evidence.stream()
                                            .map(QaCacheEntryService.Source::id)
                                            .collect(Collectors.joining(","))));
            return entryService.insertIfAbsent(entry) > 0;
        }
        return false;
    }

    /**
     * 检查是否存在仍生效的相似缓存问题。
     */
    private boolean hasSimilarCachedQuestion(String question) {
        double minScore = properties.getMinScore();
        if (!Double.isFinite(minScore) || minScore < 0.8 || minScore > 1) {
            throw new IllegalStateException("缓存相似度阈值必须在 0.8 到 1 之间");
        }
        LocalDateTime now = LocalDateTime.now();
        // 无生效条目时跳过向量检索。
        if (entryService.count(new LambdaQueryWrapper<QaCacheEntry>()
                .eq(QaCacheEntry::getStatus, "ACTIVE")
                .gt(QaCacheEntry::getExpiresAt, now)) == 0) {
            return false;
        }
        for (QaCacheVectorService.Match match : vectors.search(question, minScore)) {
            if (Double.isFinite(match.score()) && match.score() >= minScore
                    && isUsable(entryService.getById(match.id()), now)) {
                return true;
            }
        }
        return false;
    }

    private static String digest(String value) {
        return DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8));
    }

    static String questionKey(String question) {
        String normalized =
                Normalizer.normalize(question, Normalizer.Form.NFKC)
                        .strip()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", " ")
                        .replaceAll("[?!。]+$", "")
                        .strip();
        // 归一化时保留型号、数值和否定条件。
        return digest(normalized);
    }

    /**
     * 校验证据的 RAG 引用是否均可追溯到有效文档分段。
     */
    private boolean hasValidReferences(String refs) {
        if (refs == null || refs.isBlank()) {
            return false;
        }
        JsonNode refsNode;
        try {
            refsNode = QaCacheJsonUtil.MAPPER.readTree(refs);
        } catch (JsonProcessingException e) {
            return false;
        }
        if (refsNode == null || !refsNode.isArray() || refsNode.isEmpty()) {
            return false;
        }
        for (JsonNode ref : refsNode) {
            String docId = ref.path("documentId").asText("");
            String embeddingId = ref.path("embeddingId").asText("");
            String docVersion = ref.path("version").asText("");
            if (!docId.matches("[0-9]{1,18}") || embeddingId.isBlank() || docVersion.isBlank()) {
                return false;
            }
            if (entryService.resolveChunk(docId, embeddingId, docVersion) == null) {
                return false;
            }
        }
        return true;
    }

    static boolean validCandidate(
            QaCacheCurator.Candidate c,
            Map<String, QaCacheEntryService.Source> sources,
            QaCacheProperties p) {
        if (c == null
                || !c.publicReusable()
                || !Double.isFinite(c.quality())
                || c.quality() < MIN_CANDIDATE_QUALITY
                || c.quality() > 1
                || c.question() == null
                || c.question().isBlank()
                || c.question().length() > 500
                || c.answer() == null
                || c.answer().isBlank()
                || c.answer().length() > 16000
                || c.sourceIds() == null
                || c.sourceIds().stream().anyMatch(id -> !sources.containsKey(id))) {
            return false;
        }
        return c.sourceIds().stream()
                        .distinct()
                        .map(sources::get)
                        .map(QaCacheEntryService.Source::conversationId)
                        .distinct()
                        .count()
                >= Math.max(2, p.getMinFrequency());
    }

    /**
     * 在文档变更事务中同步下线相关缓存。
     */
    @EventListener
    public void onDocumentInvalidated(DocumentInvalidatedEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        for (Long documentId : event.getDocumentIds()) {
            entryService.invalidateByDocument(documentId, event.getVersionId());
        }
    }

    /**
     * 分段内容编辑时，精确下线引用该分段的缓存条目。
     */
    @EventListener
    public void onSegmentEdited(SegmentEditedEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        log.info("分段编辑，精确下线引用该分段的缓存条目: {}", event.getChunkId());
        entryService.invalidateByChunk(event.getChunkId());
    }

    /**
     * 检查并下线失效缓存，清理对应向量。
     */
    public void retire() {
        if (!properties.isEnabled()) {
            return;
        }
        String cursor = "";
        while (true) {
            List<QaCacheEntry> batch = entryService.lifecycleBatch(cursor);
            if (batch.isEmpty()) {
                break;
            }
            for (QaCacheEntry entry : batch) {
                if (lifecycle.ensureCurrent(entry.getId())) {
                    lifecycle.evaluateUsage(entry.getId(), LocalDateTime.now());
                }
            }
            cursor = batch.getLast().getId();
        }
        for (String id : entryService.vectorsToDelete()) {
            try {
                vectors.remove(id);
                entryService.vectorDeleted(id);
            } catch (Exception e) {
                log.warn("缓存下线向量清理失败，将自动重试: id={}", id, e);
            }
        }
    }

    /**
     * 发布已审核条目，失败时保留重试状态。
     */
    public void publish(String id) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            QaCacheEntry entry = entryService.getById(id);
            if (entry == null || !"APPROVED".equals(entry.getStatus())
                    || entry.getExpiresAt() == null
                    || !entry.getExpiresAt().isAfter(LocalDateTime.now())) {
                return;
            }
            if (!lifecycle.ensureCurrent(id)) {
                return;
            }
            vectors.publish(entry);
            // 仅启用仍符合条件的条目。
            if (lifecycle.ensureCurrent(id)) {
                entryService.activate(id);
            } else {
                entryService.vectorNeedsCleanup(id);
            }
        } catch (Exception e) {
            log.warn("问答向量发布失败: id={}", id, e);
            entryService.indexFailed(id);
            entryService.vectorNeedsCleanup(id);
        }
    }
}
