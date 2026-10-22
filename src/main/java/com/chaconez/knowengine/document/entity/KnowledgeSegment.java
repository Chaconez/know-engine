package com.chaconez.knowengine.document.entity;

import com.chaconez.knowengine.document.constant.SegmentStatus;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 知识片段表实体类
 */
@Getter
@Setter
@TableName("knowledge_segment")
public class KnowledgeSegment extends BaseEntity {

    /**
     * 片段ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文本内容
     */
    private String text;

    /**
     * 分片ID
     */
    private String chunkId;

    /**
     * 元数据
     */
    private String metadata;

    /**
     * 所属文档ID
     */
    private Long documentId;

    /**
     * 所属文档版本ID（指向 knowledge_document_version.version_id）
     */
    private Long documentVersion;

    /**
     * 顺序
     */
    private Integer chunkOrder;

    /**
     * 嵌入ID
     */
    private String embeddingId;

    /**
     * 状态：INIT, VECTOR_STORED
     */
    private SegmentStatus status;

    /**
     * 是否跳过嵌入生成
     */
    private Integer skipEmbedding;

    @JsonIgnore
    public Map<String, String> getMetadataMap() {
        return metadata == null ? null : JSON.parseObject(metadata, Map.class);
    }
}


