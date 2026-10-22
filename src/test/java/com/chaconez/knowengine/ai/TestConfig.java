package com.chaconez.knowengine.ai;

import com.chaconez.knowengine.rag.controller.RagModuleController;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.elasticsearch.client.RestClient;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 测试配置类
 * 用于排除 Elasticsearch 相关组件，避免测试时连接 ES
 *
 * @author Chaconez
 */
@TestConfiguration
@Import({
        // 不导入 ElasticSearchConfiguration，避免创建 ES 相关 Bean
})
public class TestConfig {

    // 模拟 ES 相关 Bean，避免启动时连接
    @MockBean
    private RestClient restClient;

    @MockBean
    private RagModuleController ragModuleController;

    /**
     * 创建一个模拟的 ElasticsearchEmbeddingStore
     * 配置 search 方法返回空的 EmbeddingSearchResult，避免空指针异常
     */
    @Bean
    @Primary
    public ElasticsearchEmbeddingStore mockElasticsearchEmbeddingStore() {
        ElasticsearchEmbeddingStore mockStore = Mockito.mock(ElasticsearchEmbeddingStore.class);
        // 配置 search 方法返回空结果，避免空指针
        when(mockStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(java.util.Collections.emptyList()));
        return mockStore;
    }

    /**
     * 创建一个模拟的 DataSource，避免测试时连接数据库
     */
    @Bean
    @Primary
    public DataSource mockDataSource() {
        return Mockito.mock(DataSource.class);
    }
}
