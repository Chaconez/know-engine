package com.chaconez.knowengine.chat.entity;

import com.chaconez.knowengine.ai.model.IntentRecognitionResult;
import com.chaconez.knowengine.chat.constant.ChatSource;

public record ChatParam(String userId, String conversationId, String messageId, String content, String assistantMessageId,
                        IntentRecognitionResult intentRecognitionResult, ChatSource chatSource) {
}
