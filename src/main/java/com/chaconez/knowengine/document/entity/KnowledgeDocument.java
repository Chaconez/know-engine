package com.chaconez.knowengine.document.entity;

import com.chaconez.knowengine.document.constant.DocumentStatus;
import com.chaconez.knowengine.document.constant.KnowledgeBaseType;
import com.chaconez.knowengine.document.util.DocumentPermissionUtils;
import com.chaconez.knowengine.rag.constant.RoleEnum;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 知识文档表实体类
 */
@Getter
@Setter
@TableName("knowledge_document")
public class KnowledgeDocument extends BaseEntity {

    /**
     * 文档ID
     */
    @TableId(type = IdType.AUTO)
    private Long docId;

    /**
     * 文档标题
     */
    private String docTitle;

    /**
     * 状态：INIT, UPLOADED, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED
     */
    private DocumentStatus status;

    /**
     * 可见范围
     */
    private String accessibleBy;

    /**
     * 文档描述
     */
    private String description;

    /**
     * 知识库类型
     */
    private KnowledgeBaseType knowledgeBaseType;

    /**
     * 扩展字段，保存JSON字符串
     */
    private String extension;

    /**
     * 当前激活版本ID，指向 knowledge_document_version.version_id
     */
    private Long currentVersionId;

    public KnowledgeDocument create(DocumentUploadParam documentUploadParam) {
        this.setDocTitle(documentUploadParam.title());
        this.setStatus(DocumentStatus.UPLOADED);
        this.setDescription(documentUploadParam.description());
        this.setKnowledgeBaseType(KnowledgeBaseType.valueOf(documentUploadParam.knowledgeBaseType()));
        this.setTableName(documentUploadParam.tableName());
        this.setAccessibleBy(DocumentPermissionUtils.getDocumentPermission(RoleEnum.valueOf(documentUploadParam.accessibleBy())));
        return this;
    }

    @JsonIgnore
    public Boolean isOverride() {
        if (extension != null && !extension.isEmpty()) {
            return (Boolean) JSON.parseObject(extension, Map.class).get("isOverride");
        }
        return false;
    }

    @JsonIgnore
    public String getTableName() {
        if (extension != null && !extension.isEmpty()) {
            return (String) JSON.parseObject(extension, Map.class).get("tableName");
        }
        return null;
    }

    @JsonIgnore
    public void setTableName(String tableName) {
        Map<String, Serializable> extensionMap;
        if (extension == null) {
            extensionMap = new HashMap<String, Serializable>();
        } else {
            extensionMap = JSON.parseObject(extension, Map.class);
        }
        extensionMap.put("tableName", tableName);
        this.extension = JSON.toJSONString(extensionMap);
    }

    /**
     * 获取上次分段时使用的参数（从 extension JSON 中读取）
     */
    @JsonIgnore
    public DocumentSplitParam getSplitParam() {
        if (extension != null && !extension.isEmpty()) {
            Map<String, Object> map = JSON.parseObject(extension, Map.class);
            Object sp = map.get("splitParam");
            if (sp != null) {
                Map<String, Object> spMap = (Map<String, Object>) sp;
                return new DocumentSplitParam(
                        (String) spMap.get("splitType"),
                        spMap.get("chunkSize") != null ? ((Number) spMap.get("chunkSize")).intValue() : null,
                        spMap.get("overlap") != null ? ((Number) spMap.get("overlap")).intValue() : null,
                        spMap.get("titleLevel") != null ? ((Number) spMap.get("titleLevel")).intValue() : null,
                        (String) spMap.get("separator"),
                        (String) spMap.get("regex")
                );
            }
        }
        return null;
    }

    /**
     * 保存分段参数到 extension JSON 中
     */
    @JsonIgnore
    public void setSplitParam(DocumentSplitParam param) {
        Map<String, Serializable> extensionMap;
        if (extension == null) {
            extensionMap = new HashMap<String, Serializable>();
        } else {
            extensionMap = JSON.parseObject(extension, Map.class);
        }
        Map<String, Serializable> spMap = new HashMap<>();
        spMap.put("splitType", param.splitType());
        spMap.put("chunkSize", param.chunkSize());
        spMap.put("overlap", param.overlap());
        spMap.put("titleLevel", param.titleLevel());
        spMap.put("separator", param.separator());
        spMap.put("regex", param.regex());
        extensionMap.put("splitParam", (Serializable) spMap);
        this.extension = JSON.toJSONString(extensionMap);
    }
}
