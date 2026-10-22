package com.chaconez.knowengine.dingtalk.callback.chatbot;

import com.chaconez.knowengine.business.vo.CarInfoVO;
import com.chaconez.knowengine.business.vo.MyCarVO;
import com.chaconez.knowengine.chat.constant.ChatSource;
import com.chaconez.knowengine.chat.entity.ChatMessage;
import com.chaconez.knowengine.chat.service.ChatApplicationService;
import com.chaconez.knowengine.dingtalk.service.RobotGroupMessagesService;
import com.chaconez.knowengine.dingtalk.service.RobotPrivateMessageService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 机器人消息回调(支持单聊和群聊）
 *
 * @author Chaconez
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingtalk.enabled", havingValue = "true")
public class ChatBotCallbackListener implements OpenDingTalkCallbackListener<ChatbotMessage, JSONObject> {

    private static final String DINGTALK_USER_PREFIX = "dingtalk_";

    @Autowired
    private RobotGroupMessagesService robotGroupMessagesService;

    @Autowired
    private RobotPrivateMessageService robotPrivateMessageService;

    @Autowired
    private ChatApplicationService chatApplicationService;

    @Override
    public JSONObject execute(ChatbotMessage message) {
        try {
            MessageContent text = message.getText();
            if (text != null) {
                String conversationType = message.getConversationType();

                String msg = text.getContent();
                String openConversationId = message.getConversationId();
                String senderUserId = message.getSenderStaffId();
                log.info("receive bot message,conversationType={}, msg={}", conversationType, msg);

                String userId = DINGTALK_USER_PREFIX + senderUserId;

                if (conversationType.equals("2")) {
                    log.info("receive bot message from group={}, msg={}", openConversationId, msg);
                    robotGroupMessagesService.send(openConversationId, "已接到您的请求，正在思考中...");
                } else if (conversationType.equals("1")) {
                    log.info("receive bot message from user={}, msg={}", userId, msg);
                    robotPrivateMessageService.send("已接到您的请求，正在思考中...", senderUserId);
                }

                // 复用 ChatApplicationService 的完整对话链路（会话创建、意图识别、RAG/兜底通用对话）
                Flux<String> chatFlux = chatApplicationService.chat(userId, msg, null, ChatSource.STAFF_DING);

                // 钉钉机器人为请求-响应模型，无法直接消费 SSE 流，阻塞聚合为完整结果
                ChatResult chatResult = aggregateChatResult(chatFlux);

                // 这里需要注意，官网中说0是单聊，1是群聊，我实测发现1是单聊，2是群聊。
                if (conversationType.equals("2")) {
                    sendGroupResult(openConversationId, chatResult);
                } else if (conversationType.equals("1")) {
                    sendPrivateResult(senderUserId, chatResult);
                }
            }
            return new JSONObject();
        } catch (Exception e) {
            log.error("receive bot message failed", e);
            return new JSONObject();
        }
    }

    /**
     * 将流式对话结果聚合为结构化结果。
     * <p>
     * 除了 LLM 答案 token，还会收集 [REFERENCE] 引用、[CARD] 提示以及 [CARD_CHOICE_*] 车辆选择卡片，
     * 便于后续根据内容类型选择钉钉消息模板（文本/Markdown/卡片）。
     * 设置 120 秒超时，避免 RAG 链路异常时无限阻塞钉钉回调线程。
     */
    private ChatResult aggregateChatResult(Flux<String> chatFlux) {
        ChatResult result = new ChatResult();
        chatFlux.doOnNext(event -> {
            if (event == null || event.isEmpty()) {
                return;
            }
            if (event.startsWith("[PROGRESS]") || event.startsWith("[DONE]")) {
                return;
            }
            if (event.startsWith("[WARN]:")) {
                result.warnMessage = event.substring("[WARN]:".length()).trim();
                return;
            }
            if (event.startsWith("[REFERENCE]:")) {
                parseReferences(event.substring("[REFERENCE]:".length()), result);
                return;
            }
            if (event.startsWith("[CARD]:")) {
                result.cardPrompt = event.substring("[CARD]:".length()).trim();
                return;
            }
            if (event.startsWith("[CARD_CHOICE_MYCAR]:")) {
                result.myCarChoices = parseMyCarChoices(event.substring("[CARD_CHOICE_MYCAR]:".length()));
                return;
            }
            if (event.startsWith("[CARD_CHOICE_CAR]:")) {
                result.carChoices = parseCarChoices(event.substring("[CARD_CHOICE_CAR]:".length()));
                return;
            }
            result.answerBuilder.append(event);
        }).then().block(Duration.ofSeconds(120));
        return result;
    }

    private void parseReferences(String json, ChatResult result) {
        try {
            List<ChatMessage.RagReference> refs = JSON.parseArray(json, ChatMessage.RagReference.class);
            if (!CollectionUtils.isEmpty(refs)) {
                result.references.addAll(refs);
            }
        } catch (Exception e) {
            log.warn("解析RAG引用失败: {}", json, e);
        }
    }

    private List<MyCarVO> parseMyCarChoices(String json) {
        try {
            return JSON.parseArray(json, MyCarVO.class);
        } catch (Exception e) {
            log.warn("解析我的车辆选择卡片失败: {}", json, e);
            return null;
        }
    }

    private List<CarInfoVO> parseCarChoices(String json) {
        try {
            return JSON.parseArray(json, CarInfoVO.class);
        } catch (Exception e) {
            log.warn("解析车型选择卡片失败: {}", json, e);
            return null;
        }
    }

    private void sendGroupResult(String openConversationId, ChatResult result) throws Exception {
        if (!CollectionUtils.isEmpty(result.myCarChoices)) {
            String markdown = buildMyCarChoicesMarkdown(result.cardPrompt, result.myCarChoices);
            robotGroupMessagesService.sendMarkdown(openConversationId, "请选择车辆", markdown);
            return;
        }
        if (!CollectionUtils.isEmpty(result.carChoices)) {
            String markdown = buildCarChoicesMarkdown(result.cardPrompt, result.carChoices);
            robotGroupMessagesService.sendMarkdown(openConversationId, "请选择车型", markdown);
            return;
        }
        String answer = resolveAnswer(result);
        if (!CollectionUtils.isEmpty(result.references)) {
            String markdown = buildAnswerWithReferences(answer, result.references);
            robotGroupMessagesService.sendMarkdown(openConversationId, "智能问答", markdown);
        } else {
            robotGroupMessagesService.send(openConversationId, answer);
        }
    }

    private void sendPrivateResult(String senderUserId, ChatResult result) throws Exception {
        if (!CollectionUtils.isEmpty(result.myCarChoices)) {
            String markdown = buildMyCarChoicesMarkdown(result.cardPrompt, result.myCarChoices);
            robotPrivateMessageService.sendMarkdown("请选择车辆", markdown, senderUserId);
            return;
        }
        if (!CollectionUtils.isEmpty(result.carChoices)) {
            String markdown = buildCarChoicesMarkdown(result.cardPrompt, result.carChoices);
            robotPrivateMessageService.sendMarkdown("请选择车型", markdown, senderUserId);
            return;
        }
        String answer = resolveAnswer(result);
        if (!CollectionUtils.isEmpty(result.references)) {
            String markdown = buildAnswerWithReferences(answer, result.references);
            robotPrivateMessageService.sendMarkdown("智能问答", markdown, senderUserId);
        } else {
            robotPrivateMessageService.send(answer, senderUserId);
        }
    }

    private String resolveAnswer(ChatResult result) {
        String answer = result.getAnswer();
        if (!answer.isBlank()) {
            return answer;
        }
        if (result.warnMessage != null && !result.warnMessage.isBlank()) {
            return result.warnMessage;
        }
        return "抱歉，我暂时无法回答您的问题，请稍后再试。";
    }

    /**
     * 构建带引用来源的 Markdown 回答。
     */
    private String buildAnswerWithReferences(String answer, List<ChatMessage.RagReference> references) {
        List<ChatMessage.RagReference> uniqueRefs = references.stream()
                .filter(ref -> ref.getDocumentTitle() != null || ref.getUrl() != null)
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(uniqueRefs)) {
            return answer;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(answer);
        sb.append("\n\n---\n**参考来源：**\n");
        int idx = 1;
        for (ChatMessage.RagReference ref : uniqueRefs) {
            String title = ref.getDocumentTitle() != null ? ref.getDocumentTitle() : "来源" + idx;
            String url = ref.getUrl();
            if (url != null && !url.isBlank()) {
                sb.append(idx).append(". [").append(escapeMarkdown(title)).append("](").append(url).append(")\n");
            } else {
                sb.append(idx).append(". ").append(escapeMarkdown(title)).append("\n");
            }
            idx++;
        }
        return sb.toString();
    }

    @Deprecated
    private String buildMyCarChoicesMarkdown(String prompt, List<MyCarVO> cars) {
        String header = (prompt != null && !prompt.isBlank()) ? prompt : "请选择您的车辆";
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(escapeMarkdown(header)).append("**\n\n");
        for (int i = 0; i < cars.size(); i++) {
            MyCarVO car = cars.get(i);
            sb.append(i + 1).append(". ");
            if (car.getFullName() != null) {
                sb.append(escapeMarkdown(car.getFullName()));
            }
            if (car.getPlateNumber() != null) {
                sb.append(" （车牌：").append(escapeMarkdown(car.getPlateNumber())).append("）");
            }
            sb.append("\n");
        }
        sb.append("\n请回复\"选择第N个\"或直接回复车辆名称。");
        return sb.toString();
    }

    @Deprecated
    private String buildCarChoicesMarkdown(String prompt, List<CarInfoVO> cars) {
        String header = (prompt != null && !prompt.isBlank()) ? prompt : "请选择您要咨询的车型";
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(escapeMarkdown(header)).append("**\n\n");
        for (int i = 0; i < cars.size(); i++) {
            CarInfoVO car = cars.get(i);
            sb.append(i + 1).append(". ");
            if (car.getFullName() != null) {
                sb.append(escapeMarkdown(car.getFullName()));
            } else if (car.getBrand() != null || car.getModelName() != null) {
                sb.append(escapeMarkdown((car.getBrand() != null ? car.getBrand() + " " : "") +
                        (car.getModelName() != null ? car.getModelName() : "")));
            }
            if (car.getGuidePrice() != null) {
                sb.append(" （指导价：").append(car.getGuidePrice()).append("万）");
            }
            sb.append("\n");
        }
        sb.append("\n请回复\"选择第N个\"或直接回复车型名称。");
        return sb.toString();
    }

    /**
     * 转义 Markdown 特殊字符，避免标题/链接等被破坏。
     */
    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("`", "\\`");
    }

    private static class ChatResult {
        private final StringBuilder answerBuilder = new StringBuilder();
        private final List<ChatMessage.RagReference> references = new ArrayList<>();
        private String warnMessage;
        private String cardPrompt;
        private List<MyCarVO> myCarChoices;
        private List<CarInfoVO> carChoices;

        String getAnswer() {
            return answerBuilder.toString().trim();
        }
    }
}