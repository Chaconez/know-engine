package com.chaconez.knowengine.cache.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 问答缓存 JSON 工具。
 */
public final class QaCacheJsonUtil {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private QaCacheJsonUtil() {}

    /**
     * 将对象序列化为 JSON。
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("问答来源无法序列化", e);
        }
    }
}
