package com.chaconez.knowengine.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, streamingChatModel = "openAiStreamingChatModel", chatMemoryProvider = "chatMemoryProvider")
public interface CommonChatService {

    @SystemMessage("你是一个智能客服，请回答用户的问题。")
    public Flux<String> streamChat(@MemoryId String userId, @UserMessage String message);
}