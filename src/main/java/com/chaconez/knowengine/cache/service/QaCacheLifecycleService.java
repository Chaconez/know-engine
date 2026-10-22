package com.chaconez.knowengine.cache.service;

import com.chaconez.knowengine.cache.entity.QaCacheEntry;
import com.chaconez.knowengine.cache.util.QaCacheJsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 问答缓存有效性校验与准出服务。
 */
@Service
@RequiredArgsConstructor
public class QaCacheLifecycleService {

    private final QaCacheEntryService entryService;

    /** 低命中率准出需完整观察 7 天，且至少有 100 次查询。 */
    private static final int EXIT_WINDOW_DAYS = 7;
    private static final int MIN_QUERY_COUNT = 100;
    private static final double MIN_HIT_RATE = 0.01;

    /** 差评至少来自 3 位用户，且占全部评价用户的一半。 */
    private static final int MIN_NEGATIVE_USERS = 3;
    private static final double MIN_NEGATIVE_RATE = 0.5;

    /**
     * 校验有效期和来源，下线失效条目。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean ensureCurrent(String id) {
        QaCacheEntry entry = entryService.lock(id);
        if (entry == null || !Set.of("PENDING", "APPROVED", "ACTIVE").contains(entry.getStatus())) {
            return false;
        }
        if (entry.getExpiresAt() != null && !entry.getExpiresAt().isAfter(LocalDateTime.now())) {
            entryService.autoDisable(id, "EXPIRED", "已超过人工审批有效期");
            return false;
        }
        if (entry.getDependencies() == null) {
            Set<QaCacheEntryService.Dependency> dependencies = resolveDependencies(entry.getSources());
            if (dependencies.isEmpty()) {
                entryService.autoDisable(id, "SOURCE_UNVERIFIABLE", "无法从原始引用追溯完整文档版本，需要重新整理审核");
                return false;
            }
            entryService.saveDependencies(id, dependencies);
        }
        if (!entryService.dependenciesCurrent(id)) {
            entryService.autoDisable(id, "KNOWLEDGE_CHANGED", "来源文档版本变化、删除、失效或已不再公开");
            return false;
        }
        return true;
    }

    Set<QaCacheEntryService.Dependency> resolveDependencies(String sources) {
        Set<QaCacheEntryService.Dependency> result = new LinkedHashSet<QaCacheEntryService.Dependency>();
        // 数据库异常向外传播，避免误判来源失效。
        final JsonNode history;
        try {
            history = QaCacheJsonUtil.MAPPER.readTree(sources == null ? "null" : sources);
        } catch (JsonProcessingException e) {
            return Set.of();
        }
        if (history == null || !history.isArray() || history.isEmpty()) {
            return Set.of();
        }
        for (JsonNode source : history) {
            final JsonNode refs;
            if (!source.path("references").isTextual()) {
                return Set.of();
            }
            try {
                refs = QaCacheJsonUtil.MAPPER.readTree(source.path("references").asText());
            } catch (JsonProcessingException e) {
                return Set.of();
            }
            if (refs == null || !refs.isArray() || refs.isEmpty()) {
                return Set.of();
            }
            for (JsonNode ref : refs) {
                String docId = ref.path("documentId").asText(""),
                        embeddingId = ref.path("embeddingId").asText(""),
                        docVersion = ref.path("version").asText("");
                if (!docId.matches("[0-9]{1,18}") || embeddingId.isBlank() || docVersion.isBlank()) {
                    return Set.of();
                }
                QaCacheEntryService.Dependency dep = entryService.resolveChunk(docId, embeddingId, docVersion);
                if (dep == null) {
                    return Set.of();
                }
                result.add(dep);
            }
        }
        return result;
    }

    /**
     * 根据用户评价和命中率执行准出。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void evaluateUsage(String id, LocalDateTime now) {
        QaCacheEntry entry = entryService.lock(id);
        if (entry == null || !"ACTIVE".equals(entry.getStatus())) {
            return;
        }
        QaCacheEntryService.FeedbackCounts votes = entryService.feedbackCounts(id);
        if (negativeThreshold(votes)) {
            entryService.autoDisable(
                    id,
                    "NEGATIVE_FEEDBACK",
                    votes.negative() + " 位用户差评 / " + votes.total() + " 位用户评价");
            return;
        }
        LocalDateTime since = now.minusDays(EXIT_WINDOW_DAYS);
        if (entry.getActivatedAt() == null || entry.getActivatedAt().isAfter(since)) {
            return;
        }
        QaCacheEntryService.Usage usage = entryService.usage(id, since);
        if (lowHitThreshold(usage)) {
            entryService.autoDisable(
                    id,
                    "LOW_HIT_RATE",
                    "最近 "
                            + EXIT_WINDOW_DAYS
                            + " 天命中 "
                            + usage.hits()
                            + " / 有效查询 "
                            + usage.queries());
        }
    }

    static boolean negativeThreshold(QaCacheEntryService.FeedbackCounts votes) {
        return votes.total() > 0
                && votes.negative() >= MIN_NEGATIVE_USERS
                && (double) votes.negative() / votes.total() >= MIN_NEGATIVE_RATE;
    }

    static boolean lowHitThreshold(QaCacheEntryService.Usage usage) {
        return usage.queries() >= MIN_QUERY_COUNT
                && (double) usage.hits() / usage.queries() < MIN_HIT_RATE;
    }
}
