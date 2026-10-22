package com.chaconez.knowengine.rag.controller;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@RestController
@RequestMapping("/know/engine")
public class KnowEngineController {
    @Autowired
    private OpenAiEmbeddingModel openAiEmbeddingModel;

    @Autowired
    private ElasticsearchEmbeddingStore embeddingStore;

    @Autowired
    private RestClient restClient;

    @RequestMapping("/adder")
    public String adder(String query) throws IOException {

        TextSegment segment1 = TextSegment.from("I like football.", new Metadata(Map.of("version", "1")));
        Embedding embedding1 = openAiEmbeddingModel.embed(segment1).content();
        embeddingStore.add(embedding1, segment1);

        TextSegment segment2 = TextSegment.from("The weather is good today.");
        Embedding embedding2 = openAiEmbeddingModel.embed(segment2).content();
        embeddingStore.add(embedding2, segment2);

        Embedding queryEmbedding = openAiEmbeddingModel.embed("What is your favourite sport?").content();

        Filter version = metadataKey("version").isEqualTo("1");

        EmbeddingSearchResult<TextSegment> relevant = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .filter(version)
                        .build());

        EmbeddingMatch<TextSegment> embeddingMatch = relevant.matches().get(0);

        System.out.println(embeddingMatch.score());
        System.out.println(embeddingMatch.embedded().text());

        return embeddingMatch.embedded().text();
    }
}