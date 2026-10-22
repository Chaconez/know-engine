package com.chaconez.knowengine.rag.modules;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;

import static com.chaconez.knowengine.rag.constant.MetadataKeyConstant.SKIP_RERANK;

/**
 * RAG 内容工具类
 */
public class ContentUtil {

    private ContentUtil() {
    }

    /**
     * 将内容标记为跳过重排序/融合
     * <p>
     * SQL/Cypher 等结构化查询结果带有该标记后，聚合器会直接透传，不再进行 RRF 融合和 scoring model 重排序。
     *
     * @param content 原始内容
     * @return 带有 skipRerank 标记的内容
     */
    public static Content markAsSkipRerank(Content content) {
        TextSegment originalSegment = content.textSegment();
        Metadata metadata = originalSegment.metadata() != null
                ? Metadata.from(originalSegment.metadata().toMap())
                : new Metadata();
        metadata.put(SKIP_RERANK, "true");
        return Content.from(TextSegment.from(originalSegment.text(), metadata), content.metadata());
    }

    /**
     * 判断内容是否标记为跳过重排序/融合
     */
    public static boolean isSkipRerank(Content content) {
        if (content == null || content.textSegment() == null || content.textSegment().metadata() == null) {
            return false;
        }
        return "true".equals(String.valueOf(content.textSegment().metadata().toMap().get(SKIP_RERANK)));
    }
}
