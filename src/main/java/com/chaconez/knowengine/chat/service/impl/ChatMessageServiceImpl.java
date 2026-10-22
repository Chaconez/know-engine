package com.chaconez.knowengine.chat.service.impl;

import com.chaconez.knowengine.chat.constant.ChatMessageType;
import com.chaconez.knowengine.chat.entity.ChatMessage;
import com.chaconez.knowengine.chat.entity.ChatConversation;
import com.chaconez.knowengine.chat.service.ChatConversationService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.chaconez.knowengine.chat.mapper.ChatMessageMapper;
import com.chaconez.knowengine.chat.service.ChatMessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI对话消息表 Service 实现类
 */
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

    private final ChatConversationService chatConversationService;

    /**
     * 创建聊天消息服务。
     */
    public ChatMessageServiceImpl(ChatMessageMapper messageMapper, ChatConversationService chatConversationService) {
        this.baseMapper = messageMapper;
        this.chatConversationService = chatConversationService;
    }

    /**
     * 按时间顺序查询会话消息。
     */
    @Override
    public List<ChatMessage> getMessagesByConversationId(String conversationId) {
        return this.list(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getCreatedAt));
    }

    /**
     * 按消息 ID 查询消息。
     */
    @Override
    public ChatMessage getByMessageId(String messageId) {
        return this.getOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId));
    }

    /**
     * 保存用户消息并返回消息 ID。
     */
    @Override
    public String saveUserMessage(String conversationId, String content) {
        String messageId = UUID.randomUUID().toString().replace("-", "");

        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setConversationId(conversationId);
        message.setType(ChatMessageType.USER);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());

        this.save(message);
        return messageId;
    }

    /**
     * 更新问题改写内容。
     */
    @Override
    public void updateTransformContent(String messageId, String transformContent) {
        ChatMessage update = new ChatMessage();
        update.setTransformContent(transformContent);
        this.update(update, new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId));
    }

    /**
     * 更新回答的知识引用。
     */
    @Override
    public void updateRagReferences(String messageId, List<ChatMessage.RagReference> ragReferences) {
        ChatMessage update = new ChatMessage();
        update.setRagReferences(ragReferences);
        this.update(update, new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId));
    }

    /**
     * 更新消息正文。
     */
    @Override
    public void updateContent(String messageId, String content) {
        ChatMessage update = new ChatMessage();
        update.setContent(content);
        this.update(update, new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId));
    }

    /**
     * 创建助手消息并返回消息 ID。
     */
    @Override
    public String saveAssistantMessage(String conversationId) {
        String messageId = UUID.randomUUID().toString().replace("-", "");

        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setConversationId(conversationId);
        message.setType(ChatMessageType.ASSISTANT);
        message.setCreatedAt(LocalDateTime.now());

        this.save(message);
        return messageId;
    }

    /**
     * 删除指定会话的消息。
     */
    @Override
    public boolean deleteMessagesByConversationId(String conversationId) {
        return this.remove(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));
    }

    /**
     * 查询会话最近的消息并按时间排序。
     */
    @Override
    public List<ChatMessage> getRecentMessages(String conversationId, int limit) {
        // 查询最新的 limit+2 条，排除最新的2条（当前轮次刚保存的user消息和空assistant消息）
        Page<ChatMessage> page = this.page(
                new Page<>(1, limit + 2),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByDesc(ChatMessage::getCreatedAt)
        );

        List<ChatMessage> records = page.getRecords();
        // 去掉最新的2条
        if (records.size() > 2) {
            records = records.subList(2, records.size());
        } else {
            return new java.util.ArrayList<>();
        }

        // 返回列表需要反转，使其按时间正序排列
        java.util.Collections.reverse(records);
        return records;
    }

    /**
     * 保存用户对本人回答的首次评价。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void feedback(String messageId, String userId, boolean helpful, String comment) {
        if (comment != null && comment.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评价说明最多 1000 字");
        }
        ChatMessage message = getOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId)
                .eq(ChatMessage::getType, ChatMessageType.ASSISTANT));
        if (message == null || message.getContent() == null || message.getContent().isBlank()
                || chatConversationService.count(new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getConversationId, message.getConversationId())
                        .eq(ChatConversation::getUserId, userId)) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到本人已完成的回答");
        }
        // 条件更新保证并发或重复提交时只有首次评价生效。
        update(new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, message.getId())
                .isNotNull(ChatMessage::getContent)
                .ne(ChatMessage::getContent, "")
                .eq(ChatMessage::getType, ChatMessageType.ASSISTANT)
                .isNull(ChatMessage::getHelpful)
                .set(ChatMessage::getHelpful, helpful)
                .set(ChatMessage::getFeedbackComment, comment)
                .set(ChatMessage::getFeedbackAt, LocalDateTime.now()));
    }
}
