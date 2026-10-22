package com.chaconez.knowengine.document.service;

import com.chaconez.knowengine.document.constant.DocumentStatus;
import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 知识文档表 Service 接口
 */
public interface KnowledgeDocumentService extends IService<KnowledgeDocument> {

    /**
     * 删除文档，并级联逻辑删除该文档下的所有分段
     *
     * @param docId 文档ID
     * @return 是否删除成功
     */
    boolean removeDocumentWithSegments(Long docId);

    /**
     * 批量删除文档，并级联逻辑删除这些文档下的所有分段
     *
     * @param docIds 文档ID列表
     * @return 是否删除成功
     */
    boolean removeDocumentsWithSegments(List<Long> docIds);

    /**
     * 让指定版本失效：
     * 1. 清理该版本在 ES 中的向量数据
     * 2. 将该版本下所有分段状态从 VECTOR_STORED 降为 STORED，并清空 embeddingId
     * 3. 将版本记录状态从 VECTOR_STORED 降为 CHUNKED
     *
     * @param versionId 版本ID（knowledge_document_version.version_id）
     */
    void deactivateVersion(Long versionId);

    /**
     * 让指定版本生效（重新向量化）：
     * 1. 校验版本状态必须为 CHUNKED
     * 2. 对该版本下所有 STORED 且未向量化的分段重新 embed 并写入 ES
     * 3. 将分段状态更新为 VECTOR_STORED
     * 4. 将版本记录状态从 CHUNKED 升为 VECTOR_STORED
     *
     * @param versionId 版本ID（knowledge_document_version.version_id）
     */
    void activateVersion(Long versionId);

    /**
     * 扫描需要清理的文档
     *
     * @return 需要清理的文档列表
     */
    List<KnowledgeDocument> scanDocumentsNeedingCleanup();

    /**
     * 同步推进文档和指定版本的状态。
     * 仅当当前状态按生命周期顺序早于目标状态时才会更新；若当前状态已大于或等于目标状态，则跳过，避免状态回退。
     *
     * @param docId        文档ID
     * @param versionId    版本ID（knowledge_document_version.version_id）
     * @param targetStatus 目标状态，如 CONVERTING、CONVERTED、CHUNKED、VECTOR_STORED、STORED
     * @return 是否执行了更新（true：文档或版本至少有一个被更新；false：均未更新）
     */
    boolean advanceDocumentAndVersionStatus(Long docId, Long versionId, DocumentStatus targetStatus);
}
