package com.chaconez.knowengine.document.service;

import com.chaconez.knowengine.document.entity.KnowledgeDocumentVersion;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 文档版本表 Service 接口
 */
public interface KnowledgeDocumentVersionService extends IService<KnowledgeDocumentVersion> {

    /**
     * 查询文档的所有版本（按版本号降序）
     *
     * @param docId 文档ID
     * @return 版本列表
     */
    List<KnowledgeDocumentVersion> listByDocId(Long docId);


    List<KnowledgeDocumentVersion> listByDocIdAndVersion(Long docId, String version);
    /**
     * 获取文档的最新版本号
     *
     * @param docId 文档ID
     * @return 最新版本号（如 "2.0.0"），无版本记录时返回 null
     */
    String getLatestVersion(Long docId);

    /**
     * 检查内容哈希是否已存在
     *
     * @param contentHash 内容哈希值
     * @return 是否已存在
     */
    boolean existsByContentHash(String contentHash);
}
