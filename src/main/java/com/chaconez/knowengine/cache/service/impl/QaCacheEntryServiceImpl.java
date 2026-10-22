package com.chaconez.knowengine.cache.service.impl;

import com.chaconez.knowengine.cache.entity.QaCacheEntry;
import com.chaconez.knowengine.cache.entity.QaMiningEvidence;
import com.chaconez.knowengine.cache.mapper.QaCacheEntryMapper;
import com.chaconez.knowengine.cache.service.QaCacheEntryService;
import com.chaconez.knowengine.chat.entity.ChatMessage;
import com.chaconez.knowengine.chat.mapper.ChatMessageMapper;
import com.chaconez.knowengine.cache.util.QaCacheJsonUtil;
import com.chaconez.knowengine.document.constant.DocumentStatus;
import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.chaconez.knowengine.document.entity.KnowledgeDocumentVersion;
import com.chaconez.knowengine.document.entity.KnowledgeSegment;
import com.chaconez.knowengine.document.service.KnowledgeDocumentService;
import com.chaconez.knowengine.document.service.KnowledgeDocumentVersionService;
import com.chaconez.knowengine.document.service.KnowledgeSegmentService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 问答缓存条目 Service 实现，封装缓存持久化操作。
 */
@Service
public class QaCacheEntryServiceImpl extends ServiceImpl<QaCacheEntryMapper, QaCacheEntry>
        implements QaCacheEntryService {

    private final ChatMessageMapper messageMapper;

    private final KnowledgeSegmentService segmentService;

    private final KnowledgeDocumentService documentService;

    private final KnowledgeDocumentVersionService documentVersionService;

    /**
     * 创建缓存条目持久化服务。
     */
    public QaCacheEntryServiceImpl(
            QaCacheEntryMapper entryMapper,
            ChatMessageMapper messageMapper,
            KnowledgeSegmentService segmentService,
            KnowledgeDocumentService documentService,
            KnowledgeDocumentVersionService documentVersionService) {
        this.baseMapper = entryMapper;
        this.messageMapper = messageMapper;
        this.segmentService = segmentService;
        this.documentService = documentService;
        this.documentVersionService = documentVersionService;
    }

    /**
     * 判断当前问题之前是否已有会话消息。
     */
    @Override
    public boolean hasHistory(String conversationId, String messageId) {
        return baseMapper.hasHistory(conversationId, messageId);
    }

    /**
     * 按状态分页查询缓存条目。
     */
    @Override
    public List<QaCacheEntry> list(String status, int offset, int limit) {
        return page(new Page<QaCacheEntry>(offset / limit + 1L, limit, false),
                new LambdaQueryWrapper<QaCacheEntry>()
                        .eq(QaCacheEntry::getStatus, status)
                        .orderByDesc(QaCacheEntry::getCreatedAt)).getRecords();
    }

    /**
     * 保存人工审批结果和有效期。
     */
    @Override
    public int approve(
            String id,
            String question,
            String answer,
            String reviewer,
            String note,
            LocalDateTime expiry) {
        return update(new LambdaUpdateWrapper<QaCacheEntry>()
                .eq(QaCacheEntry::getId, id)
                .eq(QaCacheEntry::getStatus, "PENDING")
                .set(QaCacheEntry::getQuestion, question)
                .set(QaCacheEntry::getAnswer, answer)
                .set(QaCacheEntry::getReviewer, reviewer)
                .set(QaCacheEntry::getReviewNote, note)
                .set(QaCacheEntry::getExpiresAt, expiry)
                .set(QaCacheEntry::getStatus, "APPROVED")
                .set(QaCacheEntry::getReviewedAt, LocalDateTime.now())) ? 1 : 0;
    }

    /**
     * 保存人工拒绝或下线结果。
     */
    @Override
    public int changeStatus(String id, String status, String reviewer, String note) {
        LambdaUpdateWrapper<QaCacheEntry> updateWrapper = new LambdaUpdateWrapper<QaCacheEntry>()
                .eq(QaCacheEntry::getId, id)
                .set(QaCacheEntry::getStatus, status)
                .set(QaCacheEntry::getReviewer, reviewer)
                .set(QaCacheEntry::getReviewNote, note)
                .set(QaCacheEntry::getReviewedAt, LocalDateTime.now());
        if ("REJECTED".equals(status)) {
            updateWrapper.eq(QaCacheEntry::getStatus, "PENDING");
        } else if ("DISABLED".equals(status)) {
            updateWrapper.in(QaCacheEntry::getStatus, "APPROVED", "ACTIVE")
                    .set(QaCacheEntry::getDisabledAt, LocalDateTime.now())
                    .set(QaCacheEntry::getDisableReason, "MANUAL");
        } else {
            return 0;
        }
        return update(updateWrapper) ? 1 : 0;
    }

    /**
     * 启用已审核条目并记录首次生效时间。
     */
    @Override
    public void activate(String id) {
        baseMapper.activate(id);
    }

    /**
     * 重新激活已下线条目，恢复为待发布状态。
     */
    @Override
    public int reactivate(String id, String reviewer, String note, LocalDateTime expiry) {
        return baseMapper.reactivate(id, reviewer, note, expiry);
    }

    /**
     * 记录向量发布失败信息。
     */
    @Override
    public void indexFailed(String id) {
        update(new LambdaUpdateWrapper<QaCacheEntry>()
                .eq(QaCacheEntry::getId, id)
                .eq(QaCacheEntry::getStatus, "APPROVED")
                .set(QaCacheEntry::getIndexError, "向量发布失败，任务将自动重试"));
    }

    /**
     * 按 ID 分页查询需要准出检查的条目。
     */
    @Override
    public List<QaCacheEntry> lifecycleBatch(String afterId) {
        return page(new Page<QaCacheEntry>(1, 100, false), new LambdaQueryWrapper<QaCacheEntry>()
                .in(QaCacheEntry::getStatus, "PENDING", "APPROVED", "ACTIVE")
                .gt(QaCacheEntry::getId, afterId)
                .orderByAsc(QaCacheEntry::getId)).getRecords();
    }

    /**
     * 查询并锁定缓存条目。
     */
    @Override
    public QaCacheEntry lock(String id) {
        return baseMapper.lock(id);
    }

    /**
     * 下线缓存条目并记录原因。
     */
    @Override
    public int autoDisable(String id, String reason, String detail) {
        return update(new LambdaUpdateWrapper<QaCacheEntry>()
                .eq(QaCacheEntry::getId, id)
                .in(QaCacheEntry::getStatus, "PENDING", "APPROVED", "ACTIVE")
                .set(QaCacheEntry::getStatus, "DISABLED")
                .set(QaCacheEntry::getDisabledAt, LocalDateTime.now())
                .set(QaCacheEntry::getDisableReason, reason)
                .set(QaCacheEntry::getDisableDetail, detail)
                .set(QaCacheEntry::isVectorDeleted, false)) ? 1 : 0;
    }

    /**
     * 下线引用指定文档或版本的缓存。
     */
    @Override
    public void invalidateByDocument(Long documentId, Long versionId) {
        Map<String, Long> dependency = new LinkedHashMap<>();
        dependency.put("documentId", documentId);
        if (versionId != null) {
            dependency.put("versionId", versionId);
        }
        update(new LambdaUpdateWrapper<QaCacheEntry>()
                .in(QaCacheEntry::getStatus, "PENDING", "APPROVED", "ACTIVE")
                .apply("JSON_CONTAINS(dependencies, {0})", QaCacheJsonUtil.write(List.of(dependency)))
                .set(QaCacheEntry::getStatus, "DISABLED")
                .set(QaCacheEntry::getDisabledAt, LocalDateTime.now())
                .set(QaCacheEntry::getDisableReason, "KNOWLEDGE_CHANGED")
                .set(QaCacheEntry::getDisableDetail, "来源文档已变更或失效：documentId=" + documentId
                        + (versionId == null ? "" : ", versionId=" + versionId))
                .set(QaCacheEntry::isVectorDeleted, false));
    }

    /**
     * 下线引用指定分段的缓存。
     */
    @Override
    public void invalidateByChunk(String chunkId) {
        update(new LambdaUpdateWrapper<QaCacheEntry>()
                .in(QaCacheEntry::getStatus, "PENDING", "APPROVED", "ACTIVE")
                .apply("JSON_CONTAINS(dependencies, JSON_OBJECT('chunkId', {0}))", chunkId)
                .set(QaCacheEntry::getStatus, "DISABLED")
                .set(QaCacheEntry::getDisabledAt, LocalDateTime.now())
                .set(QaCacheEntry::getDisableReason, "SEGMENT_CHANGED")
                .set(QaCacheEntry::getDisableDetail, "来源分段内容已修改：chunkId=" + chunkId)
                .set(QaCacheEntry::isVectorDeleted, false));
    }

    /**
     * 查询待清理的下线向量 ID。
     */
    @Override
    public List<String> vectorsToDelete() {
        return page(new Page<QaCacheEntry>(1, 100, false), new LambdaQueryWrapper<QaCacheEntry>()
                .select(QaCacheEntry::getId)
                .eq(QaCacheEntry::getStatus, "DISABLED")
                .eq(QaCacheEntry::isVectorDeleted, false)
                .orderByAsc(QaCacheEntry::getUpdatedAt)).getRecords().stream()
                .map(QaCacheEntry::getId).toList();
    }

    /**
     * 标记下线条目的向量待清理。
     */
    @Override
    public void vectorNeedsCleanup(String id) {
        update(new LambdaUpdateWrapper<QaCacheEntry>()
                .eq(QaCacheEntry::getId, id)
                .eq(QaCacheEntry::getStatus, "DISABLED")
                .set(QaCacheEntry::isVectorDeleted, false));
    }

    /**
     * 标记条目向量已清理。
     */
    @Override
    public void vectorDeleted(String id) {
        update(new LambdaUpdateWrapper<QaCacheEntry>()
                .eq(QaCacheEntry::getId, id)
                .set(QaCacheEntry::isVectorDeleted, true));
    }

    /**
     * 查询引用分段所属的唯一文档版本。
     */
    @Override
    public Dependency resolveChunk(String documentId, String embeddingId, String docVersion) {
        List<Dependency> dependencies = segmentService.list(new LambdaQueryWrapper<KnowledgeSegment>()
                        .select(KnowledgeSegment::getDocumentId, KnowledgeSegment::getDocumentVersion, KnowledgeSegment::getChunkId)
                        .eq(KnowledgeSegment::getDocumentId, parseLongId(documentId))
                        .eq(KnowledgeSegment::getEmbeddingId, embeddingId)
                        .eq(KnowledgeSegment::getDocumentVersion, parseLongId(docVersion))
                        .isNotNull(KnowledgeSegment::getDocumentVersion)).stream()
                .map(segment -> new Dependency(segment.getDocumentId(), segment.getDocumentVersion(), segment.getChunkId()))
                .distinct()
                .toList();
        return dependencies.size() == 1 ? dependencies.getFirst() : null;
    }

    /**
     * 解析数字 ID 字符串，非数字时返回 null，避免与数值列隐式转换比较。
     */
    private static Long parseLongId(String value) {
        return value != null && value.matches("[0-9]{1,18}") ? Long.valueOf(value) : null;
    }

    /**
     * 保存不可覆盖的来源版本快照。
     */
    @Override
    public void saveDependencies(String id, Set<Dependency> dependencies) {
        if (dependencies == null || dependencies.isEmpty()
                || dependencies.stream().anyMatch(dependency -> dependency == null
                || dependency.documentId() <= 0 || dependency.versionId() <= 0)) {
            throw new IllegalArgumentException("来源文档版本依赖不能为空且必须有效");
        }
        // 来源版本快照不可覆盖。
        update(new LambdaUpdateWrapper<QaCacheEntry>()
                .eq(QaCacheEntry::getId, id)
                .isNull(QaCacheEntry::getDependencies)
                .set(QaCacheEntry::getDependencies, QaCacheJsonUtil.write(dependencies)));
    }

    /**
     * 校验来源版本是否仍有效且公开。
     */
    @Override
    public boolean dependenciesCurrent(String id) {
        QaCacheEntry entry = getById(id);
        if (entry == null || entry.getDependencies() == null) {
            return false;
        }
        List<Dependency> dependencies;
        try {
            dependencies = QaCacheJsonUtil.MAPPER.readValue(
                    entry.getDependencies(), new TypeReference<List<Dependency>>() {
                    });
        } catch (JsonProcessingException e) {
            return false;
        }
        if (dependencies == null || dependencies.isEmpty()) {
            return false;
        }
        for (Dependency dependency : dependencies) {
            if (dependency == null || dependency.documentId() <= 0 || dependency.versionId() <= 0) {
                return false;
            }
            if (documentService.count(new LambdaQueryWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getDocId, dependency.documentId())
                    .eq(KnowledgeDocument::getCurrentVersionId, dependency.versionId())
                    .eq(KnowledgeDocument::getAccessibleBy, "VISITOR")) == 0) {
                return false;
            }
            if (documentVersionService.count(new LambdaQueryWrapper<KnowledgeDocumentVersion>()
                    .eq(KnowledgeDocumentVersion::getVersionId, dependency.versionId())
                    .eq(KnowledgeDocumentVersion::getDocId, dependency.documentId())
                    .eq(KnowledgeDocumentVersion::getStatus, DocumentStatus.VECTOR_STORED)) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析缓存条目的来源依赖，构建引用文档列表。
     */
    @Override
    public List<ChatMessage.RagReference> resolveReferences(String entryId) {
        QaCacheEntry entry = getById(entryId);
        if (entry == null || entry.getDependencies() == null) {
            return List.of();
        }
        List<Dependency> dependencies;
        try {
            dependencies = QaCacheJsonUtil.MAPPER.readValue(
                    entry.getDependencies(), new TypeReference<List<Dependency>>() {
                    });
        } catch (JsonProcessingException e) {
            return List.of();
        }
        if (dependencies == null || dependencies.isEmpty()) {
            return List.of();
        }
        List<ChatMessage.RagReference> refs = new ArrayList<>();
        for (Dependency dep : dependencies) {
            KnowledgeDocument doc = documentService.getById(dep.documentId());
            if (doc == null) {
                continue;
            }
            KnowledgeDocumentVersion version = documentVersionService.getById(dep.versionId());
            refs.add(ChatMessage.RagReference.builder()
                    .documentId(String.valueOf(dep.documentId()))
                    .documentTitle(doc.getDocTitle())
                    .url(version != null ? version.getDocUrl() : null)
                    .version(version != null ? version.getVersion() : null)
                    .build());
        }
        return refs;
    }

    /**
     * 标记回答命中缓存并关联缓存条目。
     */
    @Override
    public void markCacheAnswer(String assistantId, String entryId) {
        baseMapper.markCacheAnswer(assistantId, entryId);
    }

    /**
     * 统计指定时间窗口内的 RAG 回答数（粗口径分母）和条目命中数。
     */
    @Override
    public Usage usage(String id, LocalDateTime since) {
        return baseMapper.usage(id, since);
    }

    /**
     * 按用户统计缓存条目的首次评价。
     */
    @Override
    public FeedbackCounts feedbackCounts(String id) {
        return messageMapper.feedbackCounts(id);
    }

    /**
     * 累加缓存命中次数。
     */
    @Override
    public void hit(String id) {
        baseMapper.hit(id);
    }

    /**
     * 分页查询时间窗口内的合格首轮问答。
     */
    @Override
    public List<QaMiningEvidence> miningHistory(
            LocalDateTime since, LocalDateTime until, long afterId, int limit) {
        return baseMapper.miningHistory(since, until, afterId, limit);
    }

    /**
     * 复查来源问答是否仍符合提取条件。
     */
    @Override
    public List<QaMiningEvidence> recheckMining(
            List<Long> ids, LocalDateTime since, LocalDateTime until) {
        return baseMapper.recheckMining(ids, since, until);
    }

    /**
     * 统计同一问题的待审核、待发布及生效条目。
     */
    @Override
    public int openMiningCandidates(String questionKey) {
        return Math.toIntExact(count(new LambdaQueryWrapper<QaCacheEntry>()
                .eq(QaCacheEntry::getQuestionKey, questionKey)
                .in(QaCacheEntry::getStatus, "PENDING", "APPROVED", "ACTIVE")));
    }

    /**
     * 按来源指纹幂等保存待审核候选。
     */
    @Override
    public int insertIfAbsent(QaCacheEntry entry) {
        return baseMapper.insertIfAbsent(entry);
    }
}
