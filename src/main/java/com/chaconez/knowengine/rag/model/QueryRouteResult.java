package com.chaconez.knowengine.rag.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * @param intent
 * @param strategy
 * @param confidence
 * @param reasoning
 * @author Chaconez
 */
public record QueryRouteResult(
        @JsonPropertyDescription("用户问题的核心意图，仅使用以下三个字符串值：relational_db、graph_db、knowledge_base") String intent,
        @JsonPropertyDescription("推荐的查询策略") String strategy,
        @JsonPropertyDescription("策略推荐的置信度（0–1），评分保留两位小数") double confidence,
        @JsonPropertyDescription("推理理由") String reasoning) {
}
