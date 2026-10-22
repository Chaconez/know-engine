package com.chaconez.knowengine.cache.entity;

import com.chaconez.knowengine.cache.service.QaCacheEntryService.Source;

import lombok.Data;

/**
 * 从历史消息查询得到的问答证据，不单独持久化。
 */
@Data
public class QaMiningEvidence {

    /**
     * 助手回答在 chat_message 中的数字主键。
     */
    private Long answerId;

    /**
     * 原始用户问题的消息 ID。
     */
    private String questionMessageId;

    /**
     * 所属会话 ID。
     */
    private String conversationId;

    /**
     * 问题正文。
     */
    private String question;

    /**
     * 答案正文。
     */
    private String answer;

    /**
     * 原始 RAG 引用 JSON。
     */
    private String refs;

    /**
     * 转换为问答来源快照。
     */
    public Source source() {
        return new Source(questionMessageId, conversationId, question, answer, refs);
    }
}
