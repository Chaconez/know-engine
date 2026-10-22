package com.chaconez.knowengine.rag.util;

import com.chaconez.knowengine.chat.constant.RetrievalSource;
import com.chaconez.knowengine.chat.entity.ChatMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.chaconez.knowengine.rag.constant.MetadataKeyConstant.*;


public class ReferenceUtil {

    public static List<ChatMessage.RagReference> getRagReferences(List<Content> contents, RetrievalSource retrievalSource) {
        return contents.stream().map(content ->
                ChatMessage.RagReference.builder()
                        .documentId(content.textSegment().metadata().getInteger(DOC_ID) + "")
                        .documentTitle(content.textSegment().metadata().getString(FILE_NAME))
                        .url(content.textSegment().metadata().getString(URL))
                        .chunkId(content.textSegment().metadata().getString(CHUNK_ID))
                        .embeddingId(content.textSegment().metadata().getString(EMBEDDING_ID))
                        .version(Objects.toString(content.textSegment().metadata().getInteger(VERSION), null))
                        .chunkContent(content.textSegment().text())
                        .similarityScore((Double) content.metadata().get(ContentMetadata.SCORE))
                        .retrievalSource(retrievalSource)
                        .rerankScore((Double) content.metadata().get(ContentMetadata.RERANKED_SCORE))
                        .build()).collect(Collectors.toList());
    }

    public static ChatMessage.RagReference getRagReference(Content content, RetrievalSource retrievalSource) {
        return ChatMessage.RagReference.builder()
                .documentId(content.textSegment().metadata().getInteger(DOC_ID) + "")
                .documentTitle(content.textSegment().metadata().getString(FILE_NAME))
                .url(content.textSegment().metadata().getString(URL))
                .chunkId(content.textSegment().metadata().getString(CHUNK_ID))
                .embeddingId(content.textSegment().metadata().getString(EMBEDDING_ID))
                .version(Objects.toString(content.textSegment().metadata().getInteger(VERSION), null))
                .chunkContent(content.textSegment().text())
                .similarityScore((Double) content.metadata().get(ContentMetadata.SCORE))
                .retrievalSource(retrievalSource)
                .rerankScore((Double) content.metadata().get(ContentMetadata.RERANKED_SCORE))
                .build();
    }
}
