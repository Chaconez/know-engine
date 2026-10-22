package com.chaconez.knowengine.chat.service.impl;

import com.chaconez.knowengine.chat.constant.ChatConversationStatus;
import com.chaconez.knowengine.chat.entity.ChatConversation;
import com.chaconez.knowengine.chat.mapper.ChatConversationMapper;
import com.chaconez.knowengine.chat.service.ChatConversationService;
import com.chaconez.knowengine.infra.snowflake.SnowflakeIdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI对话会话表 Service 实现类
 */
@Service
public class ChatConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation> implements ChatConversationService {

    @Override
    public List<ChatConversation> getConversationsByUserId(String userId) {
        return this.list(new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getUserId, userId)
                .ne(ChatConversation::getStatus, "deleted")
                .orderByDesc(ChatConversation::getUpdatedAt));
    }

    @Override
    public ChatConversation getByConversationId(String conversationId) {
        return this.getOne(new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId)
                .ne(ChatConversation::getStatus, "deleted"));
    }

    @Override
    public String createConversation(String userId, String title) {
        String conversationId = UUID.randomUUID().toString().replace("-", "") + userId;

        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId(conversationId);
        conversation.setUserId(userId);
        conversation.setTitle(title != null ? title : "新对话");
        conversation.setStatus(ChatConversationStatus.ACTIVE);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        
        this.save(conversation);
        return conversationId;
    }

    @Override
    public boolean updateTitle(String conversationId, String title) {
        return this.update(new LambdaUpdateWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId)
                .set(ChatConversation::getTitle, title)
                .set(ChatConversation::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public boolean archiveConversation(String conversationId) {
        return this.update(new LambdaUpdateWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId)
                .set(ChatConversation::getStatus, "archived")
                .set(ChatConversation::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public boolean deleteConversation(String conversationId) {
        return this.update(new LambdaUpdateWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId)
                .set(ChatConversation::getStatus, "deleted")
                .set(ChatConversation::getUpdatedAt, LocalDateTime.now()));
    }
}
