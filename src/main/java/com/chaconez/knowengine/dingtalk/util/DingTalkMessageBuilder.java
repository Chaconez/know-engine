package com.chaconez.knowengine.dingtalk.util;

import com.alibaba.fastjson2.JSONObject;

/**
 * 钉钉机器人消息体构造工具。
 * <p>
 * 企业内部应用机器人（群聊/单聊）支持的 msgKey 与 msgParam 格式相同，
 * 本类统一封装文本、Markdown、链接、ActionCard 等常用消息模板。
 *
 * @author Chaconez
 */
public final class DingTalkMessageBuilder {

    private DingTalkMessageBuilder() {
    }

    /**
     * 文本消息：msgKey = sampleText
     */
    public static JSONObject text(String content) {
        JSONObject msgParam = new JSONObject();
        msgParam.put("content", content);
        return msgParam;
    }

    /**
     * Markdown 消息：msgKey = sampleMarkdown
     */
    public static JSONObject markdown(String title, String text) {
        JSONObject msgParam = new JSONObject();
        msgParam.put("title", title);
        msgParam.put("text", text);
        return msgParam;
    }

    /**
     * 链接消息：msgKey = sampleLink
     */
    public static JSONObject link(String title, String text, String messageUrl, String picUrl) {
        JSONObject msgParam = new JSONObject();
        msgParam.put("title", title);
        msgParam.put("text", text);
        msgParam.put("messageUrl", messageUrl);
        if (picUrl != null && !picUrl.isBlank()) {
            msgParam.put("picUrl", picUrl);
        }
        return msgParam;
    }

    /**
     * ActionCard 卡片（单按钮）：msgKey = sampleActionCard
     */
    public static JSONObject actionCard(String title, String text, String singleTitle, String singleUrl) {
        JSONObject msgParam = new JSONObject();
        msgParam.put("title", title);
        msgParam.put("text", text);
        msgParam.put("singleTitle", singleTitle);
        msgParam.put("singleURL", singleUrl);
        return msgParam;
    }
}
