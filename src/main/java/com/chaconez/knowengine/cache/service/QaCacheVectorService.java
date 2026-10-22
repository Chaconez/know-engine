package com.chaconez.knowengine.cache.service;

import com.chaconez.knowengine.cache.config.QaCacheProperties;
import com.chaconez.knowengine.cache.entity.QaCacheEntry;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;

import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 问答缓存向量服务，独立索引仅保存问题和缓存条目 ID。
 */
@Component
public class QaCacheVectorService {

    private final OpenAiEmbeddingModel model;

    private final ElasticsearchEmbeddingStore store;

    public record Match(String id, double score) {}

    /**
     * 创建缓存向量服务。
     */
    public QaCacheVectorService(
            OpenAiEmbeddingModel model, RestClient client, QaCacheProperties properties) {
        this.model = model;
        this.store =
                ElasticsearchEmbeddingStore.builder()
                        .restClient(client)
                        .indexName(properties.getIndexName())
                        .build();
    }

    /**
     * 生成问题向量并写入缓存索引。
     */
    public void publish(QaCacheEntry entry) {
        // 固定向量 ID，支持幂等重试。
        store.addAll(
                List.of(entry.getId()),
                List.of(model.embed(entry.getQuestion()).content()),
                List.of(
                        TextSegment.from(
                                entry.getQuestion(), Metadata.from("entry_id", entry.getId()))));
    }

    /**
     * 删除指定条目的缓存向量。
     */
    public void remove(String id) {
        store.remove(id);
    }

    /**
     * 检索达到相似度阈值的问题向量。
     */
    public List<Match> search(String question, double minScore) {
        return store
                .search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(model.embed(question).content())
                                .minScore(minScore)
                                .maxResults(5)
                                .build())
                .matches()
                .stream()
                .map(m -> new Match(m.embeddingId(), m.score()))
                .toList();
    }
}
