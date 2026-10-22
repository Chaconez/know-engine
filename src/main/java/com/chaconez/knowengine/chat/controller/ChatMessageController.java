package com.chaconez.knowengine.chat.controller;

import com.chaconez.knowengine.chat.entity.ChatMessage;
import com.chaconez.knowengine.chat.service.ChatMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI对话消息 Controller
 */
@RestController
@RequestMapping("/chat/message")
public class ChatMessageController {

    @Autowired
    private ChatMessageService chatMessageService;

    /**
     * 获取会话的消息列表
     *
     * @param conversationId 会话ID
     * @return 消息列表
     */
    @GetMapping("/list")
    public Map<String, Object> getMessageList(@RequestParam String conversationId) {
        List<ChatMessage> messages = chatMessageService.getMessagesByConversationId(conversationId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", messages);
        result.put("total", messages.size());
        return result;
    }

    /**
     * 根据消息ID获取消息详情
     *
     * @param messageId 消息ID
     * @return 消息信息
     */
    @GetMapping("/detail")
    public Map<String, Object> getMessageDetail(@RequestParam String messageId) {
        ChatMessage message = chatMessageService.getByMessageId(messageId);

        Map<String, Object> result = new HashMap<>();
        if (message != null) {
            result.put("success", true);
            result.put("data", message);
        } else {
            result.put("success", false);
            result.put("message", "消息不存在");
        }
        return result;
    }

    /**
     * 保存用户消息
     *
     * @param conversationId 会话ID
     * @param content        消息内容
     * @return 消息ID
     */
    @PostMapping("/saveUser")
    public Map<String, Object> saveUserMessage(@RequestParam String conversationId,
                                               @RequestParam String content) {
        String messageId = chatMessageService.saveUserMessage(conversationId, content);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("messageId", messageId);
        result.put("message", "用户消息保存成功");
        return result;
    }

    /**
     * 获取最近N条消息
     *
     * @param conversationId 会话ID
     * @param limit          消息数量
     * @return 消息列表
     */
    @GetMapping("/recent")
    public Map<String, Object> getRecentMessages(@RequestParam String conversationId,
                                                 @RequestParam(defaultValue = "10") int limit) {
        List<ChatMessage> messages = chatMessageService.getRecentMessages(conversationId, limit);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", messages);
        result.put("total", messages.size());
        return result;
    }

    /**
     * 删除会话的所有消息
     *
     * @param conversationId 会话ID
     * @return 操作结果
     */
    @DeleteMapping("/deleteByConversation")
    public Map<String, Object> deleteMessagesByConversation(@RequestParam String conversationId) {
        boolean success = chatMessageService.deleteMessagesByConversationId(conversationId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "消息已删除" : "删除失败");
        return result;
    }
}
