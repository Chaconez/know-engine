package com.chaconez.knowengine.rag.modules;

import com.chaconez.knowengine.infra.json.JsonUtil;
import com.chaconez.knowengine.rag.model.QueryRouteResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jText2CypherRetriever;
import dev.langchain4j.experimental.rag.content.retriever.sql.SqlDatabaseContentRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.store.embedding.elasticsearch.AbstractElasticsearchEmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Utils.getOrDefault;

/**
 * 查询路由器
 * <p>
 * 基于 LLM 智能判断用户查询意图，将查询路由到最合适的内容检索器。
 * 支持三种数据源路由策略：
 * <ul>
 *   <li><b>关系型数据库 (relational_db)</b>：适用于结构化数据查询，如车辆信息、保险信息、订单信息等</li>
 *   <li><b>图数据库 (graph_db)</b>：适用于实体关系查询，如车型关系、影响链、层级结构等</li>
 *   <li><b>知识库 (knowledge_base)</b>：适用于语义相似性查询，如售前咨询、售后支持、技术问题等</li>
 * </ul>
 * <p>
 * <b>路由决策流程：</b>
 * <ol>
 *   <li>使用 LLM 分析用户查询语义</li>
 *   <li>根据预定义的 Prompt 模板判断最适合的数据源策略</li>
 *   <li>返回对应类型的 ContentRetriever 集合</li>
 * </ol>
 * <p>
 * 当路由决策失败（JSON 解析异常或其他错误）时，返回全部内容检索器作为降级处理，避免直接无结果。
 *
 * @see QueryRouter
 * @see ContentRetriever
 */
@Slf4j
public class KnowEngineQueryRouter implements QueryRouter {

    private final Collection<ContentRetriever> contentRetrievers;

    protected final PromptTemplate promptTemplate;

    private final ChatModel chatModel;

    /**
     * 进度回调，用于流式返回前端进度信息
     */
    private final Consumer<String> progressCallback;

    /**
     * 确保路由进度只发送一次（DefaultRetrievalAugmentor 可能对多个 query 多次调用 route）
     */
    private final AtomicBoolean routeProgressSent = new AtomicBoolean(false);

    public KnowEngineQueryRouter(Collection<ContentRetriever> contentRetrievers, ChatModel chatModel) {
        this(contentRetrievers, QUERY_ROUTE_PROMPT, chatModel, null);
    }

    public KnowEngineQueryRouter(Collection<ContentRetriever> contentRetrievers, ChatModel chatModel, Consumer<String> progressCallback) {
        this(contentRetrievers, QUERY_ROUTE_PROMPT, chatModel, progressCallback);
    }

    public KnowEngineQueryRouter(Collection<ContentRetriever> contentRetrievers, PromptTemplate promptTemplate, ChatModel chatModel, Consumer<String> progressCallback) {
        this.promptTemplate = getOrDefault(promptTemplate, QUERY_ROUTE_PROMPT);
        this.contentRetrievers = contentRetrievers;
        this.chatModel = chatModel;
        this.progressCallback = progressCallback;
    }

    private static final PromptTemplate QUERY_ROUTE_PROMPT = PromptTemplate.from("""
            你是一个汽车领域的智能助手，负责理解用户的问题，并智能判断最适合的数据查询方式。你的任务不是直接回答问题，而是分析问题语义，决定应调用哪种或哪几种数据源来获取答案。
            
            请根据以下规则进行判断：
            1、关系型数据库（Relational DB）适用场景：
            问题涉及结构化数据查询（如“车辆信息”、“保险信息”、“订单信息”等）
            问题涉及到用户个人拥有的车辆相关信息的查询的，如查询发动机号、查询下次保养时间、查询车辆里程等
            包含明确的实体属性、时间范围、数值比较、聚合操作（如 SUM、COUNT、AVG）
            示例：“我的保险还有多少天到期？”
            
            2、图数据库（Graph DB）适用场景：
            问题关注实体之间的关系、路径、连接性、层级或网络结构
            出现关键词如“谁的发动机是...”、“A和B之间有什么联系？”、“最短路径”、“影响链”
            示例：“纯电车型都有哪些？”、“型号A和型号B有什么关系？”
            
            3、知识库检索适用场景：
            问题基于语义相似性、模糊匹配、非结构化文本理解
            涉及“类似”、“相关”、“推荐”、“总结”、“解释某段内容”等意图
            涉及到汽车相关售前、售后、技术支持、营销政策等问题
            示例：“发动机异响怎么处理？”、“如何打开零重力座椅？”
            
            请严格按以下 JSON 格式输出决策结果，不要添加额外解释，不要添加任何markdown符号，如[```]：
            
            {
              "intent": "简要概括用户问题的核心意图",
              "strategy": "relational_db"
              "reasoning": "简明说明判断依据",
              "confidence": 置信度，0-1之间的小数
            }
            
            注意：
            strategy 仅使用以下三个字符串值："relational_db"、"graph_db"、"knowledge_base"，其一次只返回一个。
            confidence 表示你对策略推荐的置信度（0–1），评分保留两位小数
            reasoning 应简洁说明判断依据
            
            用户的原始查询：{{query}}
            """);


    @Override
    public Collection<ContentRetriever> route(Query query) {
        // 发送进度：开始问题路由（仅发送一次，避免多个 query 导致重复）
        if (progressCallback != null && routeProgressSent.compareAndSet(false, true)) {
            progressCallback.accept("[PROGRESS]:正在路由您的问题...");
            System.out.println("[PROGRESS]:正在路由您的问题...");
        }

        String response = chatModel.chat(createPrompt(query).text());

        try {
            QueryRouteResult queryRouteResult = JSON.parseObject(JsonUtil.fixJson(response), QueryRouteResult.class);
            String strategy = queryRouteResult.strategy();
            log.info("Route Success , query: {} , strategy: {}", query, strategy);

            switch (strategy) {
                case "relational_db":
                    return contentRetrievers.stream().filter(retriever ->
                    {
                        if (retriever instanceof ProgressAwareContentRetriever) {
                            ContentRetriever delegate = ((ProgressAwareContentRetriever) retriever).getDelegate();
                            return delegate instanceof SqlDatabaseContentRetriever || delegate instanceof KnowEngineSqlDatabaseContentRetriever;
                        }

                        return retriever instanceof SqlDatabaseContentRetriever || retriever instanceof KnowEngineSqlDatabaseContentRetriever;

                    }).collect(Collectors.toList());
                case "graph_db":
                    return contentRetrievers.stream().filter(retriever ->
                    {
                        if (retriever instanceof ProgressAwareContentRetriever) {
                            ContentRetriever delegate = ((ProgressAwareContentRetriever) retriever).getDelegate();
                            return delegate instanceof Neo4jText2CypherRetriever || delegate instanceof KnowEngineNeo4jContentRetriever;
                        }
                        return retriever instanceof Neo4jText2CypherRetriever || retriever instanceof KnowEngineNeo4jContentRetriever;

                    }).collect(Collectors.toList());
                case "knowledge_base":
                    return contentRetrievers.stream().filter(retriever -> {
                        if (retriever instanceof ProgressAwareContentRetriever) {
                            return ((ProgressAwareContentRetriever) retriever).getDelegate() instanceof AbstractElasticsearchEmbeddingStore;
                        }
                        return retriever instanceof AbstractElasticsearchEmbeddingStore;
                    }).collect(Collectors.toList());
                default:
                    return contentRetrievers;
            }

        } catch (JSONException jsonException) {
            log.error("Route Failed due to invalid JSON, query: {}, response: {}", query, response, jsonException);
        } catch (Exception e) {
            log.error("Route Failed due to unexpected error, query: {}, response: {}", query, response, e);
        }
        // 路由决策异常时降级为全量检索，避免直接无结果
        return contentRetrievers;
    }

    protected Prompt createPrompt(Query query) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("query", query.text());
        return promptTemplate.apply(variables);
    }
}
