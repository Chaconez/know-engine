package com.chaconez.knowengine.document.service;

import com.chaconez.knowengine.document.entity.KnowledgeDocument;

import java.util.List;

/**
 * 文档版本清理服务
 * 负责清理旧版本的分段和向量数据（影子更新收尾）
 */
public interface DocumentCleanupService {

    /**
     * 清理指定文档的旧版本分段和向量数据
     * 仅删除 document_version != currentVersionId 的分段和向量，保留当前版本数据
     *
     * @param docId            文档ID
     * @param currentVersionId 当前激活版本ID
     */
    boolean cleanupOldVersionData(Long docId, Long currentVersionId);
}
