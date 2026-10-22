package com.chaconez.knowengine.dingtalk.service;

import com.chaconez.knowengine.dingtalk.util.DingTalkMessageBuilder;
import com.aliyun.dingtalkrobot_1_0.Client;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendHeaders;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendRequest;
import com.aliyun.dingtalkrobot_1_0.models.OrgGroupSendResponse;
import com.aliyun.tea.TeaException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * @author zeymo
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "dingtalk.enabled", havingValue = "true")
public class RobotGroupMessagesService {
    private Client robotClient;
    private final AccessTokenService accessTokenService;

    @Value("${dingtalk.robotCode}")
    private String robotCode;

    @Autowired
    public RobotGroupMessagesService(AccessTokenService accessTokenService) {
        this.accessTokenService = accessTokenService;
    }

    @PostConstruct
    public void init() throws Exception {
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
        config.protocol = "https";
        config.regionId = "central";
        robotClient = new Client(config);
    }

    /**
     * send message to group with openConversationId
     *
     * @param openConversationId conversationId
     * @return messageId
     * @throws Exception e
     */
    public String send(String openConversationId, String text) throws Exception {
        return send(openConversationId, "sampleText", DingTalkMessageBuilder.text(text).toJSONString());
    }

    /**
     * 发送 Markdown 群消息。
     *
     * @param openConversationId 群会话ID
     * @param title              消息标题
     * @param text               markdown 内容
     * @return messageId
     * @throws Exception e
     */
    public String sendMarkdown(String openConversationId, String title, String text) throws Exception {
        return send(openConversationId, "sampleMarkdown", DingTalkMessageBuilder.markdown(title, text).toJSONString());
    }

    /**
     * 发送 ActionCard 卡片群消息。
     *
     * @param openConversationId 群会话ID
     * @param title              卡片标题
     * @param text               卡片内容（支持 markdown）
     * @param singleTitle        按钮文本
     * @param singleUrl          按钮跳转链接
     * @return messageId
     * @throws Exception e
     */
    public String sendActionCard(String openConversationId, String title, String text,
                                 String singleTitle, String singleUrl) throws Exception {
        return send(openConversationId, "sampleActionCard",
                DingTalkMessageBuilder.actionCard(title, text, singleTitle, singleUrl).toJSONString());
    }

    /**
     * 通用群消息发送。
     *
     * @param openConversationId 群会话ID
     * @param msgKey             消息模板Key，如 sampleText/sampleMarkdown/sampleActionCard
     * @param msgParamJson       消息模板参数JSON字符串
     * @return messageId
     * @throws Exception e
     */
    public String send(String openConversationId, String msgKey, String msgParamJson) throws Exception {
        OrgGroupSendHeaders orgGroupSendHeaders = new OrgGroupSendHeaders();
        orgGroupSendHeaders.setXAcsDingtalkAccessToken(accessTokenService.getAccessToken());

        OrgGroupSendRequest orgGroupSendRequest = new OrgGroupSendRequest();
        orgGroupSendRequest.setMsgKey(msgKey);
        orgGroupSendRequest.setRobotCode(robotCode);
        orgGroupSendRequest.setOpenConversationId(openConversationId);
        orgGroupSendRequest.setMsgParam(msgParamJson);

        try {
            OrgGroupSendResponse orgGroupSendResponse = robotClient.orgGroupSendWithOptions(orgGroupSendRequest,
                    orgGroupSendHeaders, new com.aliyun.teautil.models.RuntimeOptions());
            if (Objects.isNull(orgGroupSendResponse) || Objects.isNull(orgGroupSendResponse.getBody())) {
                log.error("RobotGroupMessagesService_send orgGroupSendWithOptions return error, response={}",
                        orgGroupSendResponse);
                return null;
            }
            return orgGroupSendResponse.getBody().getProcessQueryKey();
        } catch (TeaException e) {
            log.error("RobotGroupMessagesService_send orgGroupSendWithOptions throw TeaException, errCode={}, " +
                    "errorMessage={}", e.getCode(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("RobotGroupMessagesService_send orgGroupSendWithOptions throw Exception", e);
            throw e;
        }
    }
}