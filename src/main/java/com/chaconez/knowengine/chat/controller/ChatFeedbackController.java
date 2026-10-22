package com.chaconez.knowengine.chat.controller;

import com.chaconez.knowengine.auth.service.AuthService;
import com.chaconez.knowengine.chat.service.ChatMessageService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 聊天回答评价接口。
 */
@RestController
@RequestMapping("/chat/message")
@RequiredArgsConstructor
public class ChatFeedbackController {

    private final ChatMessageService chatMessageService;

    private final AuthService authService;

    public record Feedback(Boolean helpful, String comment) {}

    /**
     * 保存用户对本人回答的首次评价。
     */
    @PostMapping("/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@PathVariable String messageId, @RequestBody Feedback feedback) {
        if (feedback.helpful() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有帮助或没帮助");
        }
        chatMessageService.feedback(
                messageId, authService.getCurrentUserId(), feedback.helpful(), feedback.comment());
    }
}
