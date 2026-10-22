package com.chaconez.knowengine.document.entity;

import com.chaconez.knowengine.document.constant.DocumentStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档版本表实体类
 * 存储文档每个版本的快照信息，与 knowledge_document 一对多关系
 */
@Getter
@Setter
@TableName("knowledge_document_version")
public class KnowledgeDocumentVersion extends BaseEntity {

    /**
     * 版本ID
     */
    @TableId(type = IdType.AUTO)
    private Long versionId;

    /**
     * 关联文档ID（knowledge_document.doc_id）
     */
    private Long docId;

    /**
     * 版本号（语义化版本，如 1.0.0）
     */
    private String version;

    /**
     * 文档URL（MinIO原始文件）
     */
    private String docUrl;

    /**
     * 转换后的文档URL
     */
    private String convertedDocUrl;

    /**
     * 文档内容哈希值（SHA-256）
     */
    private String contentHash;

    /**
     * 版本状态：UPLOADED, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED, STORED
     */
    private DocumentStatus status;

    /**
     * 上传用户
     */
    private String uploadUser;

    /**
     * 版本变更说明
     */
    private String changelog;
}
