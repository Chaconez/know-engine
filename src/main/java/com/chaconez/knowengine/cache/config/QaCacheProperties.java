package com.chaconez.knowengine.cache.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 问答缓存配置，对应 application.yml 中的 qa-cache 配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "qa-cache")
public class QaCacheProperties {

    /**
     * 是否启用问答缓存。
     */
    private boolean enabled = false;

    /**
     * 缓存问题使用的独立 Elasticsearch 索引。
     */
    private String indexName = "know-engine-qa-cache-v1";

    /**
     * 缓存检索最低相似度。
     */
    private double minScore = 0.95;

    /**
     * 提取所需的最少独立会话数。
     */
    private int minFrequency = 3;
}
