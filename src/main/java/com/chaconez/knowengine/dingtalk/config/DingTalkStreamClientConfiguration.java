package com.chaconez.knowengine.dingtalk.config;

import com.chaconez.knowengine.dingtalk.callback.chatbot.ChatBotCallbackListener;
import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author zeymo
 */
@Configuration
@ConditionalOnProperty(name = "dingtalk.enabled", havingValue = "true")
public class DingTalkStreamClientConfiguration {

    @Value("${dingtalk.appKey}")
    private String clientId;
    @Value("${dingtalk.appSecret}")
    private String clientSecret;

    /**
     * 配置OpenDingTalkClient客户端并配置初始化方法(start)
     *
     * @param chatBotCallbackListener
     * @return
     * @throws Exception
     */
    @Bean(initMethod = "start")
    public OpenDingTalkClient configureStreamClient(@Autowired ChatBotCallbackListener chatBotCallbackListener) throws Exception {
        // init stream client
        return OpenDingTalkStreamClientBuilder.custom()
                //配置应用的身份信息, 企业内部应用分别为appKey和appSecret, 三方应用为suiteKey和suiteSecret
                .credential(new AuthClientCredential(clientId, clientSecret))
                //注册机器人回调
                .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, chatBotCallbackListener).build();
    }
}