package com.chaconez.knowengine.cache.service;

import com.chaconez.knowengine.cache.entity.QaCacheEntry;
import com.chaconez.knowengine.cache.entity.QaMiningEvidence;
import com.chaconez.knowengine.chat.entity.ChatMessage;

import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 问答缓存条目 Service 接口。
 */
public interface QaCacheEntryService extends IService<QaCacheEntry> {

    record Source(
            String id, String conversationId, String question, String answer, String references) {}

    record Dependency(long documentId, long versionId, String chunkId) {}

    record Usage(long queries, long hits) {}

    record FeedbackCounts(long total, long negative) {}

    /**
     * 判断当前问题之前是否已有会话消息。
     */
    boolean hasHistory(String conversationId, String messageId);

    /**
     * 按状态分页查询缓存条目。
     */
    List<QaCacheEntry> list(String status, int offset, int limit);

    /**
     * 保存人工审批结果和有效期。
     */
    int approve(
            String id,
            String question,
            String answer,
            String reviewer,
            String note,
            LocalDateTime expiry);

    /**
     * 保存人工拒绝或下线结果。
     */
    int changeStatus(String id, String status, String reviewer, String note);

    /**
     * 启用已审核条目并记录首次生效时间。
     */
    void activate(String id);

    /**
     * 重新激活已下线条目，恢复为待发布状态。
     */
    int reactivate(String id, String reviewer, String note, LocalDateTime expiry);

    /**
     * 记录向量发布失败信息。
     */
    void indexFailed(String id);

    /**
     * 按 ID 分页查询需要准出检查的条目。
     */
    List<QaCacheEntry> lifecycleBatch(String afterId);

    /**
     * 查询并锁定缓存条目。
     */
    QaCacheEntry lock(String id);

    /**
     * 下线缓存条目并记录原因。
     */
    int autoDisable(String id, String reason, String detail);

    /**
     * 下线引用指定文档或版本的缓存。
     */
    void invalidateByDocument(Long documentId, Long versionId);

    /**
     * 下线引用指定分段的缓存。
     */
    void invalidateByChunk(String chunkId);

    /**
     * 查询待清理的下线向量 ID。
     */
    List<String> vectorsToDelete();

    /**
     * 标记下线条目的向量待清理。
     */
    void vectorNeedsCleanup(String id);

    /**
     * 标记条目向量已清理。
     */
    void vectorDeleted(String id);

    /**
     * 查询引用分段所属的唯一文档版本。
     */
    Dependency resolveChunk(String documentId, String embeddingId,String docVersion);

    /**
     * 保存不可覆盖的来源版本快照。
     */
    void saveDependencies(String id, Set<Dependency> dependencies);

    /**
     * 校验来源版本是否仍有效且公开。
     */
    boolean dependenciesCurrent(String id);

    /**
     * 解析缓存条目的来源依赖，构建引用文档列表。
     */
    List<ChatMessage.RagReference> resolveReferences(String entryId);

    /**
     * 标记回答命中缓存并关联缓存条目。
     */
    void markCacheAnswer(String assistantId, String entryId);

    /**
     * 统计指定时间窗口内的 RAG 回答数（粗口径分母）和条目命中数。
     */
    Usage usage(String id, LocalDateTime since);

    /**
     * 按用户统计缓存条目的首次评价。
     */
    FeedbackCounts feedbackCounts(String id);

    /**
     * 累加缓存命中次数。
     */
    void hit(String id);

    /**
     * 分页查询时间窗口内的合格首轮问答。
     */
    List<QaMiningEvidence> miningHistory(
            LocalDateTime since, LocalDateTime until, long afterId, int limit);

    /**
     * 复查来源问答是否仍符合提取条件。
     */
    List<QaMiningEvidence> recheckMining(List<Long> ids, LocalDateTime since, LocalDateTime until);

    /**
     * 统计同一问题的待审核、待发布及生效条目。
     */
    int openMiningCandidates(String questionKey);

    /**
     * 按来源指纹幂等保存待审核候选。
     */
    int insertIfAbsent(QaCacheEntry entry);
}
