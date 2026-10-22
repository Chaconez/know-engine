package com.chaconez.knowengine.chat.memory;

import com.chaconez.knowengine.chat.constant.ChatMessageType;
import com.chaconez.knowengine.chat.entity.ChatMessage;
import com.chaconez.knowengine.chat.service.ChatMessageService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于数据库 + Redis 的 ChatMemoryStore 实现
 * <p>
 * 使用 Redis 缓存 + 数据库持久化的组合策略，只保留最近 10 条消息：
 * - getMessages(): 优先从 Redis 读取；缓存未命中时从数据库加载历史消息并写入 Redis
 * - updateMessages(): 更新 Redis 缓存（保证同一次 AiServices 调用内多次 add()/messages() 状态一致）
 * - 消息的最终持久化由 ChatMessageService 在流式对话完成时处理（saveUserMessage/updateContent）
 * <p>
 * Redis Key 格式: know-engine:chat-memory:{conversationId}
 * TTL: 1 小时（自动过期，避免冷数据长期占用内存）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseChatMemoryStore implements ChatMemoryStore {

    private final ChatMessageService chatMessageService;

    private final StringRedisTemplate stringRedisTemplate;

    private static final String REDIS_KEY_PREFIX = "know-engine:chat-memory:";

    /**
     * 最大保留消息条数
     */
    private static final int MAX_MESSAGES = 10;

    /**
     * Redis 缓存过期时间（小时）
     */
    private static final long CACHE_TTL_HOURS = 1;

    @Override
    public List<dev.langchain4j.data.message.ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null && !json.isEmpty()) {
                return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
            }
        } catch (Exception e) {
            log.warn("Redis 读取聊天记忆失败, memoryId={}, 将从数据库加载", memoryId, e);
        }

        // Redis 未命中，从数据库加载
        List<dev.langchain4j.data.message.ChatMessage> messages = loadFromDatabase(memoryId.toString());
        // 写入 Redis
        saveToRedis(key, messages);
        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<dev.langchain4j.data.message.ChatMessage> messages) {
        // 只保留最近 MAX_MESSAGES 条消息
        List<dev.langchain4j.data.message.ChatMessage> trimmed = trimMessages(messages);
        saveToRedis(buildKey(memoryId), trimmed);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        try {
            stringRedisTemplate.delete(buildKey(memoryId));
        } catch (Exception e) {
            log.warn("Redis 删除聊天记忆失败, memoryId={}", memoryId, e);
        }
        chatMessageService.deleteMessagesByConversationId(memoryId.toString());
    }

    /**
     * 从数据库加载历史消息
     * 注意：过滤掉意图识别结果消息（INTENT_RECOGNITION），避免污染对话上下文
     */
    private List<dev.langchain4j.data.message.ChatMessage> loadFromDatabase(String conversationId) {
        List<ChatMessage> dbMessages = chatMessageService.getRecentMessages(conversationId, MAX_MESSAGES);

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        for (ChatMessage dbMessage : dbMessages) {
            if (dbMessage.getContent() == null || dbMessage.getContent().isEmpty()) {
                continue;
            }

            if (dbMessage.getType() == ChatMessageType.USER) {
                messages.add(UserMessage.from(dbMessage.getContent()));
            } else if (dbMessage.getType() == ChatMessageType.ASSISTANT) {
                messages.add(AiMessage.from(dbMessage.getContent()));
            }
        }
        return messages;
    }

    /**
     * 清除指定会话的缓存（在新一轮对话开始前调用，确保从DB重新加载最新消息）
     */
    public void evictCache(Object memoryId) {
        try {
            stringRedisTemplate.delete(buildKey(memoryId));
        } catch (Exception e) {
            log.warn("Redis 清除聊天记忆缓存失败, memoryId={}", memoryId, e);
        }
    }

    /**
     * 序列化消息列表并保存到 Redis，设置 TTL
     */
    private void saveToRedis(String key, List<dev.langchain4j.data.message.ChatMessage> messages) {
        try {
            String json = ChatMessageSerializer.messagesToJson(messages);
            stringRedisTemplate.opsForValue().set(key, json, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis 保存聊天记忆失败, key={}", key, e);
        }
    }

    /**
     * 截断消息列表，只保留最近 MAX_MESSAGES 条
     */
    private List<dev.langchain4j.data.message.ChatMessage> trimMessages(List<dev.langchain4j.data.message.ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        if (messages.size() <= MAX_MESSAGES) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(messages.size() - MAX_MESSAGES, messages.size()));
    }

    private String buildKey(Object memoryId) {
        return REDIS_KEY_PREFIX + memoryId;
    }
}
