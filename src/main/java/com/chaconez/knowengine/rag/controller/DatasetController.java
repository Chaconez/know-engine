package com.chaconez.knowengine.rag.controller;

import com.chaconez.knowengine.ai.model.IntentRecognitionResult;
import com.chaconez.knowengine.ai.service.IntentRecognitionService;
import com.chaconez.knowengine.chat.constant.ChatSource;
import com.chaconez.knowengine.chat.entity.ChatMessage;
import com.chaconez.knowengine.chat.entity.ChatParam;
import com.chaconez.knowengine.chat.service.ChatApplicationService;
import com.chaconez.knowengine.chat.service.ChatConversationService;
import com.chaconez.knowengine.chat.service.ChatMessageService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据集生成接口
 * <p>
 * 用于根据用户问题生成回答，构建 Q&A 数据集。
 */
@RestController
@RequestMapping("/dataset")
@Slf4j
public class DatasetController {

    /**
     * 数据集生成默认用户ID
     */
    private static final String DATASET_USER_ID = "dataset-generator";

    /**
     * 临时会话标题最大长度
     */
    private static final int TITLE_MAX_LENGTH = 20;

    @Autowired
    private ChatApplicationService chatApplicationService;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private ChatMessageService chatMessageService;

    private IntentRecognitionService intentRecognitionService;

    @Autowired
    private ChatModel chatModel;

    @PostConstruct
    public void init() {
        intentRecognitionService = AiServices.builder(IntentRecognitionService.class).chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder().id(memoryId).maxMessages(10).build()).build();
    }

    /**
     * 生成数据集：基于用户问题，复用知识库 RAG 流程生成完整回答。
     * <p>
     * 入参：question（用户问题）
     * 出参：JSON 包含 question（用户问题）、answer（回答结果）、references（引用的参考资料）
     *
     * @param question 用户问题
     * @return 数据集结果（JSON）
     */
    @GetMapping("/generate")
    public DatasetResult generate(@RequestParam String question) {

        // 1. 创建临时会话，复用流式对话所需的上下文
        String tempTitle = question.substring(0, Math.min(question.length(), TITLE_MAX_LENGTH));
        String conversationId = chatConversationService.createConversation(DATASET_USER_ID, tempTitle);

        log.info("数据集生成开始: conversationId={}, question={}", conversationId, question);

        String messageId = chatMessageService.saveUserMessage(conversationId, question);
        String assistantMessageId = chatMessageService.saveAssistantMessage(conversationId);

        IntentRecognitionResult intentRecognitionResult = intentRecognitionService.chat(conversationId, question);
        // 2. 构造请求：仅依赖用户问题，其他业务字段置空
        ChatParam chatParam = new ChatParam(DATASET_USER_ID, conversationId, messageId, question, assistantMessageId, intentRecognitionResult, ChatSource.STAFF_DING);

        // 3. 调用流式对话，分别收集回答 token 和参考资料
        StringBuilder answerBuilder = new StringBuilder();


        chatApplicationService.doChat(chatParam)
                .doOnNext(event -> {
                    if (isAnswerToken(event)) {
                        answerBuilder.append(event);
                    }
                })
                .doOnError(e -> log.error("数据集生成异常: conversationId={}", conversationId, e))
                .blockLast();

        String answer = answerBuilder.toString();
        List<String> referenceContents = new ArrayList<>();
        if (!CollectionUtils.isEmpty(chatMessageService.getByMessageId(assistantMessageId).getRagReferences())){
            referenceContents = chatMessageService.getByMessageId(assistantMessageId).getRagReferences().stream().map(ChatMessage.RagReference::getChunkContent).collect(Collectors.toList());
        }

        log.info("数据集生成完成: conversationId={}, answerLength={}, refsCount={}",
                conversationId, answer.length(), referenceContents.size());

        // 4. 组装结果
        DatasetResult result = new DatasetResult();
        result.setQuestion(question);
        result.setAnswer(answer);
        result.setReferences(referenceContents);
        return result;
    }

    /**
     * 判断流事件是否为最终答案 token，过滤掉控制事件
     */
    private boolean isAnswerToken(String event) {
        if (event == null || event.isEmpty()) {
            return false;
        }
        return !event.startsWith("[PROGRESS]")
                && !event.startsWith("[REFERENCE]")
                && !event.startsWith("[CARD_")
                && !event.startsWith("[DONE]");
    }

    /**
     * 数据集生成结果
     */
    @Data
    public static class DatasetResult {
        /**
         * 用户问题
         */
        private String question;
        /**
         * 回答结果
         */
        private String answer;
        /**
         * 引用的参考资料
         */
        private List<String> references;
    }
}
