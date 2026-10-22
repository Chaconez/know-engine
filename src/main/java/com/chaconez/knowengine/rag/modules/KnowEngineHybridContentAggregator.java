package com.chaconez.knowengine.rag.modules;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合内容聚合器
 * <p>
 * 将检索结果分为两类：
 * <ul>
 *   <li><b>结构化结果</b>：来自 SQL/Cypher 查询，标记了 {@code skipRerank}，直接透传，不参与 RRF 融合和 scoring model 重排序</li>
 *   <li><b>非结构化结果</b>：来自向量/全文检索，委托给底层聚合器（如重排序聚合器）进行融合和重排序</li>
 * </ul>
 * 最终输出顺序：结构化结果在前，非结构化结果在后。
 */
public class KnowEngineHybridContentAggregator implements ContentAggregator {

    private final ContentAggregator unstructuredAggregator;

    public KnowEngineHybridContentAggregator(ContentAggregator unstructuredAggregator) {
        this.unstructuredAggregator = unstructuredAggregator;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        if (queryToContents == null || queryToContents.isEmpty()) {
            return new ArrayList<>();
        }

        List<Content> structuredContents = new ArrayList<>();
        Map<Query, Collection<List<Content>>> unstructuredQueryToContents = new LinkedHashMap<>();

        for (Map.Entry<Query, Collection<List<Content>>> entry : queryToContents.entrySet()) {
            Query query = entry.getKey();
            Collection<List<Content>> contentLists = entry.getValue();

            List<List<Content>> unstructuredLists = new ArrayList<>();
            for (List<Content> contents : contentLists) {
                List<Content> unstructured = new ArrayList<>();
                for (Content content : contents) {
                    if (ContentUtil.isSkipRerank(content)) {
                        structuredContents.add(content);
                    } else {
                        unstructured.add(content);
                    }
                }
                if (!unstructured.isEmpty()) {
                    unstructuredLists.add(unstructured);
                }
            }

            if (!unstructuredLists.isEmpty()) {
                unstructuredQueryToContents.put(query, unstructuredLists);
            }
        }

        List<Content> unstructuredResults = unstructuredAggregator.aggregate(unstructuredQueryToContents);

        List<Content> combined = new ArrayList<>(structuredContents.size() + unstructuredResults.size());
        combined.addAll(structuredContents);
        combined.addAll(unstructuredResults);
        return combined;
    }
}
