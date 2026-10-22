package com.chaconez.knowengine.cache.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chaconez.knowengine.ai.service.CommonChatService;
import com.chaconez.knowengine.ai.service.IntentRecognitionService;
import com.chaconez.knowengine.chat.constant.ChatSource;
import com.chaconez.knowengine.chat.entity.ChatMessage;
import com.chaconez.knowengine.chat.memory.DatabaseChatMemoryStore;
import com.chaconez.knowengine.chat.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

class QaCacheChatTest {


    @Test
    void ordinaryAnswersAlsoEmitFeedbackMessageAndEmptyAnswersDoNot() {
        ChatApplicationService chat = new ChatApplicationService();
        QaCacheService cache = mock(QaCacheService.class);
        ChatMessageService messages = mock(ChatMessageService.class);
        DatabaseChatMemoryStore memory = mock(DatabaseChatMemoryStore.class);
        IntentRecognitionService intent = mock(IntentRecognitionService.class);
        CommonChatService common = mock(com.chaconez.knowengine.ai.service.CommonChatService.class);
        ReflectionTestUtils.setField(chat, "qaCacheService", cache);
        ReflectionTestUtils.setField(chat, "chatMessageService", messages);
        ReflectionTestUtils.setField(chat, "databaseChatMemoryStore", memory);
        ReflectionTestUtils.setField(chat, "intentRecognitionService", intent);
        ReflectionTestUtils.setField(chat, "commonChatService", common);
        when(messages.saveUserMessage("conversation", "你好")).thenReturn("q");
        when(messages.saveAssistantMessage("conversation")).thenReturn("a");
        when(cache.lookup("你好", "conversation", "q", "a")).thenReturn(Optional.empty());
        when(intent.chat("conversation", "你好"))
                .thenReturn(
                        new com.chaconez.knowengine.ai.model.IntentRecognitionResult(
                                "", false, "", null));
        when(common.streamChat("u", "你好")).thenReturn(reactor.core.publisher.Flux.just("你好！"));
        ChatMessage answer = new com.chaconez.knowengine.chat.entity.ChatMessage();
        answer.setContent("你好！");
        when(messages.getByMessageId("a")).thenReturn(answer);
        List<String> events =
                chat.chat("u", "你好", "conversation", ChatSource.USER_WEB).collectList().block();
        assertTrue(events.contains("[ANSWER_MESSAGE]:a"));
        assertFalse(events.stream().anyMatch(e -> e.contains("命中")));
        verify(messages).updateContent("a", "你好！");
        answer.setContent("");
        List<String> emptyEvents =
                chat.chat("u", "你好", "conversation", ChatSource.USER_WEB).collectList().block();
        assertFalse(emptyEvents.contains("[ANSWER_MESSAGE]:a"));
    }
}
