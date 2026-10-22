package com.chaconez.knowengine.ai.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        // 根据 memoryId 动态创建独立的记忆窗口
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId) // 设置记忆ID
                .maxMessages(10) // 每个会话保留10条
                .build();
    }
}