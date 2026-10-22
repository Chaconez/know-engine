package com.chaconez.knowengine.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

public interface KnowEngineChatAiService {

    public Flux<String> streamChat(@MemoryId String conversationId, @UserMessage String message);

    public String chat(@MemoryId String conversationId, @UserMessage String message);

}
