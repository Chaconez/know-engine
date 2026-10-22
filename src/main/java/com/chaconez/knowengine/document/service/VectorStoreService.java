package com.chaconez.knowengine.document.service;

import com.chaconez.knowengine.document.entity.KnowledgeSegment;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 向量存储服务
 * 统一封装知识分段与 ES 向量库之间的增删改查操作，
 * 避免 embeddingStore/openAiEmbeddingModel 散落在多个业务 Service 中。
 */
public interface VectorStoreService {

    /**
     * 批量嵌入并存储知识分段
     *
     * @param segments 待向量化的知识分段
     * @return 与 segments 顺序一一对应的 embeddingId 列表
     */
    List<String> embedAndStore(List<KnowledgeSegment> segments);

    /**
     * 重新嵌入单个知识分段，写入新向量并返回 embeddingId。
     * 调用方需自行删除旧向量（如需原子替换）。
     *
     * @param segment 待重新向量化的知识分段
     * @return 新的 embeddingId
     */
    String embedAndStore(KnowledgeSegment segment);

    /**
     * 根据 embeddingId 删除单个向量
     *
     * @param embeddingId 向量ID
     */
    void remove(String embeddingId);

    /**
     * 根据 embeddingId 列表批量删除向量
     *
     * @param embeddingIds 向量ID列表
     */
    void removeAll(List<String> embeddingIds);

    /**
     * 按 docId 删除该文档下的所有向量
     *
     * @param docId 文档ID
     */
    void removeByDocId(Long docId);

    /**
     * 按 docId 列表批量删除向量
     *
     * @param docIds 文档ID列表
     */
    void removeByDocIds(List<Long> docIds);

    /**
     * 按 docId + versionId 删除该版本下的所有向量
     *
     * @param docId     文档ID
     * @param versionId 版本ID
     */
    void removeByDocIdAndVersion(Long docId, Long versionId);

    /**
     * 语义检索：对查询文本做 embedding，返回最相关的分段文本
     *
     * @param query    查询文本
     * @param minScore 最小相似度阈值
     * @return 最相关分段的文本，无结果返回 null
     */
    String search(String query, double minScore);

    /**
     * 将 KnowledgeSegment 转换为 TextSegment（含 metadata）
     *
     * @param segment 知识分段
     * @return TextSegment
     */
    TextSegment toTextSegment(KnowledgeSegment segment);
}
