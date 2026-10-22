package com.chaconez.knowengine.chat.entity;

import com.chaconez.knowengine.chat.constant.ChatMessageType;
import com.chaconez.knowengine.chat.constant.RetrievalSource;
import com.chaconez.knowengine.document.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI对话消息表
 */
@Getter
@Setter
@TableName(value = "chat_message", autoResultMap = true)
public class ChatMessage extends BaseEntity {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 消息唯一标识
     */
    private String messageId;

    /**
     * 所属会话ID
     */
    private String conversationId;

    /**
     * 角色：user/assistant
     */
    private ChatMessageType type;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 改写后的内容
     */
    private String transformContent;

    /**
     * Token数量
     */
    private Integer tokenCount;

    /**
     * 使用的模型名称
     */
    private String modelName;

    /**
     * RAG引用内容JSON数组
     * 包含document_id、document_title、chunk_id、chunk_content、similarity_score、retrieval_source等字段
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<RagReference> ragReferences;

    /**
     * 扩展元数据JSON格式
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    /**
     * 本次回答是否命中问答缓存，数据库默认 false。
     */
    private Boolean cacheHit;

    /**
     * 回答评价：null 未评价，true 有帮助，false 无帮助。
     */
    private Boolean helpful;

    /**
     * 用户评价说明。
     */
    private String feedbackComment;

    /**
     * 首次评价时间。
     */
    private LocalDateTime feedbackAt;

    /**
     * RAG引用内容内部类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RagReference {
        /**
         * 文档ID
         */
        private String documentId;

        /**
         * 文档URL
         */
        private String url;

        /**
         * 文档标题
         */
        private String documentTitle;

        /**
         * 文档块ID
         */
        private String chunkId;

        /**
         * 嵌入ID
         */
        private String embeddingId;

        /**
         * 文档版本号
         */
        private String version;

        /**
         * 文档块内容
         */
        private String chunkContent;

        /**
         * 相似度分数
         */
        private Double similarityScore;

        private Double rerankScore;

        /**
         * 检索来源：vector/keyword/hybrid/rerank
         */
        private RetrievalSource retrievalSource;

        /**
         * 扩展元数据
         */
        private Map<String, Object> metadata;

    }
}
