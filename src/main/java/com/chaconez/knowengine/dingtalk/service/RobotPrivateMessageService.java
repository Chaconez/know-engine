package com.chaconez.knowengine.dingtalk.service;

import com.chaconez.knowengine.dingtalk.util.DingTalkMessageBuilder;
import com.aliyun.dingtalkrobot_1_0.Client;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTOHeaders;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTORequest;
import com.aliyun.dingtalkrobot_1_0.models.BatchSendOTOResponse;
import com.aliyun.tea.TeaException;
import com.aliyun.teautil.models.RuntimeOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Slf4j
@Service
@ConditionalOnProperty(name = "dingtalk.enabled", havingValue = "true")
public class RobotPrivateMessageService {
    private Client robotClient;
    private final AccessTokenService accessTokenService;

    @Value("${dingtalk.robotCode}")
    private String robotCode;

    @Autowired
    public RobotPrivateMessageService(AccessTokenService accessTokenService) {
        this.accessTokenService = accessTokenService;
    }

    @PostConstruct
    public void init() throws Exception {
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
        config.protocol = "https";
        config.regionId = "central";
        robotClient = new Client(config);
    }

    public String send(String text, String userId) throws Exception {
        return send(userId, "sampleText", DingTalkMessageBuilder.text(text).toJSONString());
    }

    /**
     * 发送 Markdown 单聊消息。
     */
    public String sendMarkdown(String title, String text, String userId) throws Exception {
        return send(userId, "sampleMarkdown", DingTalkMessageBuilder.markdown(title, text).toJSONString());
    }

    /**
     * 发送 ActionCard 卡片单聊消息。
     */
    public String sendActionCard(String title, String text, String singleTitle, String singleUrl, String userId) throws Exception {
        return send(userId, "sampleActionCard",
                DingTalkMessageBuilder.actionCard(title, text, singleTitle, singleUrl).toJSONString());
    }

    /**
     * 通用单聊消息发送。
     *
     * @param userId       用户ID
     * @param msgKey       消息模板Key
     * @param msgParamJson 消息模板参数JSON字符串
     * @return messageId
     * @throws Exception e
     */
    public String send(String userId, String msgKey, String msgParamJson) throws Exception {
        BatchSendOTOHeaders batchSendOTOHeaders = new BatchSendOTOHeaders();
        batchSendOTOHeaders.setXAcsDingtalkAccessToken(accessTokenService.getAccessToken());
        BatchSendOTORequest batchSendOTORequest = new BatchSendOTORequest();
        batchSendOTORequest.setMsgKey(msgKey);
        batchSendOTORequest.setRobotCode(robotCode);
        batchSendOTORequest.setUserIds(java.util.Arrays.asList(userId));
        batchSendOTORequest.setMsgParam(msgParamJson);

        try {
            BatchSendOTOResponse batchSendOTOResponse = robotClient.batchSendOTOWithOptions(batchSendOTORequest, batchSendOTOHeaders, new RuntimeOptions());
            if (Objects.isNull(batchSendOTOResponse) || Objects.isNull(batchSendOTOResponse.getBody())) {
                log.error("RobotPrivateMessages_send batchSendOTOResponse return error, response={}",
                        batchSendOTOResponse);
                return null;
            }
            return batchSendOTOResponse.getBody().getProcessQueryKey();
        } catch (TeaException e) {
            log.error("RobotPrivateMessages_send batchSendOTOResponse throw TeaException, errCode={}, " +
                    "errorMessage={}", e.getCode(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("RobotPrivateMessages_send batchSendOTOResponse throw Exception", e);
            throw e;
        }
    }

}