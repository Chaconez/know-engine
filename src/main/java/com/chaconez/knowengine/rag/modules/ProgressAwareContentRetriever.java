package com.chaconez.knowengine.rag.modules;

import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jText2CypherRetriever;
import dev.langchain4j.experimental.rag.content.retriever.sql.SqlDatabaseContentRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.elasticsearch.AbstractElasticsearchEmbeddingStore;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 带进度通知的检索器
 * <p>
 * 在委托执行 {@link ContentRetriever#retrieve} 前发送进度通知，
 * 用于流式返回前端当前处理阶段，减少用户等待焦虑。
 * <p>
 *
 * @see ContentAggregator
 */
public class ProgressAwareContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;
    private final Consumer<String> progressCallback;

    /**
     * 确保路由进度只发送一次
     */
    private final AtomicBoolean embeddingProgressSent = new AtomicBoolean(false);
    private final AtomicBoolean sqlProgressSent = new AtomicBoolean(false);
    private final AtomicBoolean neo4jProgressSent = new AtomicBoolean(false);

    public ProgressAwareContentRetriever(ContentRetriever delegate, Consumer<String> progressCallback) {
        this.delegate = delegate;
        this.progressCallback = progressCallback;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (progressCallback != null) {

            switch (delegate) {
                case KnowEngineSqlDatabaseContentRetriever knowEngineSqlDatabaseContentRetriever -> {
                    if (sqlProgressSent.compareAndSet(false, true)) {
                        progressCallback.accept("[PROGRESS]:正在检索数据库内容...");
                        System.out.println("[PROGRESS]:正在检索数据库内容...");
                    }
                }
                case SqlDatabaseContentRetriever sqlDatabaseContentRetriever -> {
                    if (sqlProgressSent.compareAndSet(false, true)) {
                        progressCallback.accept("[PROGRESS]:正在检索数据库内容...");
                        System.out.println("[PROGRESS]:正在检索数据库内容...");
                    }
                }
                case Neo4jText2CypherRetriever neo4jText2CypherRetriever -> {
                    if (neo4jProgressSent.compareAndSet(false, true)) {
                        progressCallback.accept("[PROGRESS]:正在检索图数据库内容...");
                        System.out.println("[PROGRESS]:正在检索图数据库内容...");
                    }
                }
                case KnowEngineNeo4jContentRetriever knowEngineNeo4jContentRetriever -> {
                    if (neo4jProgressSent.compareAndSet(false, true)) {
                        progressCallback.accept("[PROGRESS]:正在检索图数据库内容...");
                        System.out.println("[PROGRESS]:正在检索图数据库内容...");
                    }
                }
                case AbstractElasticsearchEmbeddingStore abstractElasticsearchEmbeddingStore -> {
                    if (embeddingProgressSent.compareAndSet(false, true)) {
                        progressCallback.accept("[PROGRESS]:正在检索知识库内容...");
                        System.out.println("[PROGRESS]:正在检索知识库内容...");
                    }
                }
                case null, default -> {
                    if (embeddingProgressSent.compareAndSet(false, true)) {
                        progressCallback.accept("[PROGRESS]:正在检索文档内容...");
                        System.out.println("[PROGRESS]:正在检索文档内容...");
                    }
                }
            }
        }
        return delegate.retrieve(query);
    }

    public ContentRetriever getDelegate() {
        return delegate;
    }
}
