package com.chaconez.knowengine.rag.modules;

import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jGraph;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jText2CypherRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 自定义 Neo4j 图数据库内容检索器（Text2Cypher）
 * <p>
 * 基于 {@link Neo4jText2CypherRetriever}，增加了兜底检索逻辑：
 * 当 Cypher 查询结果为空或执行异常时，自动降级使用知识库检索器（fallbackRetriever）进行检索，
 * 确保用户总能获得有意义的回答。
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>图数据库中没有匹配数据时，自动切换到向量/全文知识库检索</li>
 *   <li>Cypher 生成或执行异常时，优雅降级到知识库检索</li>
 *   <li>支持自定义 Prompt 模板和 few-shot 示例，提升特定领域 Cypher 生成质量</li>
 * </ul>
 *
 * @see Neo4jText2CypherRetriever
 * @see ContentRetriever
 */
@Slf4j
public class KnowEngineNeo4jContentRetriever implements ContentRetriever {

    private final Neo4jText2CypherRetriever neo4jText2CypherRetriever;
    private final ContentRetriever fallbackRetriever;

    public KnowEngineNeo4jContentRetriever(Neo4jText2CypherRetriever neo4jText2CypherRetriever,
                                           ContentRetriever fallbackRetriever) {
        this.neo4jText2CypherRetriever = neo4jText2CypherRetriever;
        this.fallbackRetriever = fallbackRetriever;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> results;
        try {
            // 与 SQL 检索器保持一致：在问题中补充用户上下文与时间信息，辅助 LLM 生成更准确的 Cypher
            String newQuery = "我的问题是：" + query.text() + ", 我的用户Id是: 123321" + ", 现在是：" + LocalDateTime.now();
            query = new Query(newQuery, query.metadata());
            results = neo4jText2CypherRetriever.retrieve(query);
        } catch (Exception e) {
            log.warn("Neo4j 图数据库检索异常，降级使用知识库检索, query: {}", query.text(), e);
            return fallbackRetriever.retrieve(query);
        }

        if (results == null || results.isEmpty() || isCypherResultEmpty(results)) {
            log.info("Neo4j 图数据库检索结果为空，降级使用知识库检索, query: {}", query.text());
            return fallbackRetriever.retrieve(query);
        }

        // Cypher 结构化查询结果直接透传，不参与后续重排序/融合
        return results.stream()
                .map(ContentUtil::markAsSkipRerank)
                .collect(Collectors.toList());
    }

    /**
     * 判断 Cypher 查询结果是否实际为空
     * <p>
     * Neo4jText2CypherRetriever 返回的 Content 文本通常形如：
     * <pre>
     * Result of executing '...CYPHER...':
     * key1,key2
     * </pre>
     * 当只有列名头部而没有实际数据行时，认为结果为空。
     * <p>
     * 通过定位最后一个 "':\n" 标记（Cypher 语句描述结束位置），
     * 判断列名行之后是否存在实际数据行，避免 Cypher 语句本身含换行符导致误判。
     */
    private boolean isCypherResultEmpty(List<Content> results) {
        if (results.size() != 1) {
            return false;
        }
        String text = results.get(0).textSegment().text();
        if (!text.startsWith("Result of executing '")) {
            return false;
        }
        // ":\n" 标记列名开始，列名后的第一个 "\n" 标记数据开始
        int columnStartIndex = text.indexOf(":\n");
        if (columnStartIndex == -1) {
            return false;
        }
        // ":\n" 之后是列名行，找列名行结束的 "\n"（即数据开始位置）
        int dataStartIndex = text.indexOf('\n', columnStartIndex + 2);
        // 列名后没有换行符，或换行符后无实际内容，则表示无数据
        return dataStartIndex == -1 || text.substring(dataStartIndex + 1).trim().isEmpty();
    }

    /**
     * 获取内部的 Neo4jText2CypherRetriever 实例
     */
    public Neo4jText2CypherRetriever getNeo4jText2CypherRetriever() {
        return neo4jText2CypherRetriever;
    }

    /**
     * 获取兜底的知识库检索器
     */
    public ContentRetriever getFallbackRetriever() {
        return fallbackRetriever;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Neo4jGraph graph;
        private PromptTemplate promptTemplate;
        private List<String> examples;
        private List<String> relationships;
        private String dialect;
        private int maxRetries = 1;
        private ChatModel chatModel;
        private ContentRetriever fallbackRetriever;

        public Builder graph(Neo4jGraph graph) {
            this.graph = graph;
            return this;
        }

        public Builder promptTemplate(PromptTemplate promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        public Builder examples(List<String> examples) {
            this.examples = examples;
            return this;
        }

        public Builder relationships(List<String> relationships) {
            this.relationships = relationships;
            return this;
        }

        public Builder dialect(String dialect) {
            this.dialect = dialect;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder fallbackRetriever(ContentRetriever fallbackRetriever) {
            this.fallbackRetriever = fallbackRetriever;
            return this;
        }

        public KnowEngineNeo4jContentRetriever build() {
            Neo4jText2CypherRetriever.Builder neo4jBuilder = Neo4jText2CypherRetriever.builder()
                    .graph(graph)
                    .chatModel(chatModel)
                    .maxRetries(maxRetries);

            if (promptTemplate != null) {
                neo4jBuilder.promptTemplate(promptTemplate);
            }
            if (examples != null) {
                neo4jBuilder.examples(examples);
            }
            if (relationships != null) {
                neo4jBuilder.relationships(relationships);
            }
            if (dialect != null) {
                neo4jBuilder.dialect(dialect);
            }

            return new KnowEngineNeo4jContentRetriever(neo4jBuilder.build(), fallbackRetriever);
        }
    }
}
