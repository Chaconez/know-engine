package com.chaconez.knowengine.document.service.impl;

import com.chaconez.knowengine.document.entity.KnowledgeSegment;
import com.chaconez.knowengine.document.service.VectorStoreService;
import com.chaconez.knowengine.rag.constant.MetadataKeyConstant;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 向量存储服务实现
 * 统一负责文本嵌入、向量写入、向量删除等与 Elasticsearch 向量库的交互。
 */
@Slf4j
@Service
public class VectorStoreServiceImpl implements VectorStoreService {

    @Autowired
    private ElasticsearchEmbeddingStore embeddingStore;

    @Autowired
    private OpenAiEmbeddingModel openAiEmbeddingModel;

    @Override
    public List<String> embedAndStore(List<KnowledgeSegment> segments) {
        if (CollectionUtils.isEmpty(segments)) {
            return Collections.emptyList();
        }

        List<TextSegment> textSegments = segments.stream()
                .map(this::toTextSegment)
                .toList();

        Response<List<Embedding>> embeddingResponse = openAiEmbeddingModel.embedAll(textSegments);
        List<String> embeddingIds = embeddingStore.addAll(embeddingResponse.content(), textSegments);

        Assert.isTrue(embeddingIds.size() == segments.size(),
                "向量存储失败，向量数量与分段数量不一致");

        log.info("批量向量化完成, count: {}", segments.size());
        return embeddingIds;
    }

    @Override
    public String embedAndStore(KnowledgeSegment segment) {
        Assert.notNull(segment, "分段不能为空");
        TextSegment textSegment = toTextSegment(segment);
        Response<Embedding> embeddingResponse = openAiEmbeddingModel.embed(textSegment.text());
        String embeddingId = embeddingStore.add(embeddingResponse.content(), textSegment);
        log.info("单条向量化完成, segmentId: {}, embeddingId: {}", segment.getId(), embeddingId);
        return embeddingId;
    }

    @Override
    public void remove(String embeddingId) {
        if (embeddingId == null) {
            return;
        }
        try {
            embeddingStore.remove(embeddingId);
            log.info("删除向量成功, embeddingId: {}", embeddingId);
        } catch (Exception e) {
            log.warn("删除向量失败, embeddingId: {}, error: {}", embeddingId, e.getMessage());
        }
    }

    @Override
    public void removeAll(List<String> embeddingIds) {
        if (CollectionUtils.isEmpty(embeddingIds)) {
            return;
        }
        try {
            embeddingStore.removeAll(embeddingIds);
            log.info("批量删除向量成功, count: {}", embeddingIds.size());
        } catch (Exception e) {
            log.warn("批量删除向量失败, count: {}, error: {}", embeddingIds.size(), e.getMessage());
        }
    }

    @Override
    public void removeByDocId(Long docId) {
        try {
            Filter filter = metadataKey(MetadataKeyConstant.DOC_ID).isEqualTo(docId);
            embeddingStore.removeAll(filter);
            log.info("按docId删除向量成功, docId: {}", docId);
        } catch (Exception e) {
            log.warn("按docId删除向量失败, docId: {}, error: {}", docId, e.getMessage());
        }
    }

    @Override
    public void removeByDocIds(List<Long> docIds) {
        if (CollectionUtils.isEmpty(docIds)) {
            return;
        }
        try {
            Filter filter = metadataKey(MetadataKeyConstant.DOC_ID).isIn(docIds);
            embeddingStore.removeAll(filter);
            log.info("按docIds批量删除向量成功, count: {}", docIds.size());
        } catch (Exception e) {
            log.warn("按docIds批量删除向量失败, count: {}, error: {}", docIds.size(), e.getMessage());
        }
    }

    @Override
    public void removeByDocIdAndVersion(Long docId, Long versionId) {
        try {
            Filter filter = metadataKey(MetadataKeyConstant.DOC_ID).isEqualTo(docId)
                    .and(metadataKey(MetadataKeyConstant.VERSION).isEqualTo(versionId));
            embeddingStore.removeAll(filter);
            log.info("按docId+versionId删除向量成功, docId: {}, versionId: {}", docId, versionId);
        } catch (Exception e) {
            log.warn("按docId+versionId删除向量失败, docId: {}, versionId: {}, error: {}",
                    docId, versionId, e.getMessage());
        }
    }

    @Override
    public String search(String query, double minScore) {
        Embedding queryEmbedding = openAiEmbeddingModel.embed(query).content();
        EmbeddingSearchResult<TextSegment> relevant = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .minScore(minScore)
                        .build());
        if (relevant.matches().isEmpty()) {
            return null;
        }
        EmbeddingMatch<TextSegment> embeddingMatch = relevant.matches().get(0);
        return embeddingMatch.embedded().text();
    }

    @Override
    public TextSegment toTextSegment(KnowledgeSegment segment) {
        Map<String, String> metadataMap = segment.getMetadataMap();
        Metadata metadata = metadataMap != null ? Metadata.from(metadataMap) : new Metadata();
        return TextSegment.from(segment.getText(), metadata);
    }
}
