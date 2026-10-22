package com.chaconez.knowengine.chat.service;

import com.chaconez.knowengine.chat.entity.ChatConversation;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * AI对话会话表 Service 接口
 */
public interface ChatConversationService extends IService<ChatConversation> {

    /**
     * 根据用户ID获取会话列表
     *
     * @param userId 用户ID
     * @return 会话列表
     */
    List<ChatConversation> getConversationsByUserId(String userId);

    /**
     * 根据会话ID获取会话
     *
     * @param conversationId 会话ID
     * @return 会话信息
     */
    ChatConversation getByConversationId(String conversationId);

    /**
     * 创建新会话
     *
     * @param userId 用户ID
     * @param title  会话标题
     * @return 会话ID
     */
    String createConversation(String userId, String title);

    /**
     * 更新会话标题
     *
     * @param conversationId 会话ID
     * @param title          新标题
     * @return 是否成功
     */
    boolean updateTitle(String conversationId, String title);

    /**
     * 归档会话
     *
     * @param conversationId 会话ID
     * @return 是否成功
     */
    boolean archiveConversation(String conversationId);

    /**
     * 删除会话
     *
     * @param conversationId 会话ID
     * @return 是否成功
     */
    boolean deleteConversation(String conversationId);
}
