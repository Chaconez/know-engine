package com.chaconez.knowengine.cache.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.util.List;

/**
 * 历史问题语义归组与答案整理的模型接口。
 */
public interface QaCacheCurator {

    record Candidate(
            String question,
            String answer,
            List<String> sourceIds,
            double quality,
            String reason,
            boolean publicReusable) {}

    record Result(List<Candidate> candidates) {}

    record Group(List<String> questionIds) {}

    record Grouping(List<Group> groups) {}

    /**
     * 按语义对历史问题归组。
     */
    @SystemMessage(fromResource = "prompts/qa-cache-group-prompt.txt")
    Grouping group(@UserMessage String questions);

    /**
     * 根据高频问答来源整理候选答案。
     */
    @SystemMessage(fromResource = "prompts/qa-cache-curate-prompt.txt")
    Result curate(@UserMessage String history);
}
