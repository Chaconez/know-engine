package com.chaconez.knowengine.ai.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 会话标题生成服务
 */
public interface TitleSummaryService {

    @SystemMessage("你是一个对话标题生成助手。根据用户的第一句话，生成一个简洁的中文会话标题，要求：不超过20个字，不加引号，直接输出标题内容。")
    @UserMessage("请根据以下用户问题生成会话标题：{{it}}")
    String generateTitle(String userQuestion);
}
