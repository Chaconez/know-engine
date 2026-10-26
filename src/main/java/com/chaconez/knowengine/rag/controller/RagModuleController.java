package com.chaconez.knowengine.rag.controller;

import com.chaconez.knowengine.ai.model.IntentRecognitionResult;
import com.chaconez.knowengine.ai.service.CommonChatService;
import com.chaconez.knowengine.ai.service.IntentRecognitionService;
import com.chaconez.knowengine.ai.service.PromptService;
import com.chaconez.knowengine.chat.service.ChatMessageService;
import com.chaconez.knowengine.document.service.KnowledgeSegmentService;
import com.chaconez.knowengine.rag.modules.KnowEngineElasticsearchContentRetriever;
import com.chaconez.knowengine.rag.modules.KnowEngineNeo4jContentRetriever;
import com.chaconez.knowengine.rag.modules.KnowEngineQueryRouter;
import com.chaconez.knowengine.rag.modules.KnowEngineQueryTransformer;
import com.chaconez.knowengine.rag.modules.reranker.BgeScoringModel;
import com.alibaba.fastjson2.JSON;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jGraph;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.experimental.rag.content.retriever.sql.SqlDatabaseContentRetriever;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.elasticsearch.ElasticsearchContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationFullText;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationKnn;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.elasticsearch.client.RestClient;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.chaconez.knowengine.rag.config.ElasticSearchConfiguration.INDEX_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * 用于ai的各个模块的功能测试
 */
@RestController
@RequestMapping("/ai/module")
public class RagModuleController {


    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private OpenAiEmbeddingModel openAiEmbeddingModel;

    @Autowired
    private RestClient restClient;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Driver neo4jDriver;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private PromptService promptService;

    @Autowired
    private ChatMessageService chatMessageService;

    private ElasticsearchContentRetriever fullTextRetriever;

    private SqlDatabaseContentRetriever sqlRetriever;

    private KnowEngineNeo4jContentRetriever neo4jRetriever;

    private static final int MAX_RESULT = 5;

    private static final double MIN_SCORE = 0.5;

    @Value("classpath:prompts/text-to-cypher-prompt.txt")
    private Resource textToCypherPrompt;


    @PostConstruct
    public void init() throws IOException {

        this.fullTextRetriever = ElasticsearchContentRetriever.builder()
                .restClient(restClient)
                .configuration(ElasticsearchConfigurationFullText.builder().build())
                .maxResults(MAX_RESULT)
                .indexName(INDEX_NAME)
                .minScore(MIN_SCORE)
                .build();

        this.sqlRetriever = SqlDatabaseContentRetriever.builder().dataSource(dataSource)
                .promptTemplate(new PromptTemplate("textToSqlPrompt.getContentAsString(UTF_8)"))
                .databaseStructure("tablesSql.getContentAsString(UTF_8)")
                .chatModel(chatModel)
                .build();

        this.neo4jRetriever = KnowEngineNeo4jContentRetriever.builder()
                .graph(Neo4jGraph.builder()
                        .driver(neo4jDriver)
                        .build())
                .chatModel(chatModel)
                .promptTemplate(new PromptTemplate(textToCypherPrompt.getContentAsString(UTF_8)))
                .fallbackRetriever(fullTextRetriever)
                .build();
    }

    @GetMapping("/router")
    public String testRouter(String query) {

        KnowEngineElasticsearchContentRetriever embeddingRetriever = KnowEngineElasticsearchContentRetriever.builder()
                .restClient(restClient)
                .embeddingModel(openAiEmbeddingModel)
                .configuration(ElasticsearchConfigurationKnn.builder().build())
                .maxResults(MAX_RESULT)
                .indexName(INDEX_NAME)
                .minScore(MIN_SCORE)
                .knowledgeSegmentService(knowledgeSegmentService)
                .build();

        KnowEngineQueryRouter knowEngineQueryRouter = new KnowEngineQueryRouter(List.of(embeddingRetriever, fullTextRetriever, sqlRetriever, neo4jRetriever), chatModel);
        Collection<ContentRetriever> contentRetrievers = knowEngineQueryRouter.route(new Query(query));
        return contentRetrievers.toString();
    }


    @GetMapping("testTransformer")
    public String testTransformer(String content) {
        String messageId = chatMessageService.saveUserMessage(UUID.randomUUID().toString(), content);
        KnowEngineQueryTransformer knowEngineQueryTransformer = new KnowEngineQueryTransformer(chatModel, messageId);
        Collection<Query> queries = knowEngineQueryTransformer.transform(new Query(content));
        System.out.println(queries);
        return JSON.toJSONString(queries);
    }

    @GetMapping("testRetriever")
    public Flux<String> testRetriever(String query, String chatMessageId, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");

//        ElasticsearchConfiguration configuration = ElasticsearchConfigurationHybrid.builder().build();

//        ElasticsearchContentRetriever contentRetriever = ElasticsearchContentRetriever.builder()
//                .restClient(restClient)
//                .embeddingModel(openAiEmbeddingModel)
//                .configuration(configuration)
//                .maxResults(5)
//                .indexName("know-engine")
//                .minScore(0.5)
//                .build();

        ElasticsearchContentRetriever fullTextRetriever = ElasticsearchContentRetriever.builder()
                .restClient(restClient)
                .configuration(ElasticsearchConfigurationFullText.builder().build())
                .maxResults(MAX_RESULT)
                .indexName(INDEX_NAME)
                .minScore(MIN_SCORE)
                .build();

        KnowEngineElasticsearchContentRetriever embeddingRetriever = KnowEngineElasticsearchContentRetriever
                .builder()
                .restClient(restClient)
                .embeddingModel(openAiEmbeddingModel)
                .configuration(ElasticsearchConfigurationKnn.builder().build())
                .maxResults(MAX_RESULT)
                .indexName(INDEX_NAME)
                .minScore(MIN_SCORE)
                .knowledgeSegmentService(knowledgeSegmentService)
                .build();


        KnowEngineQueryTransformer queryTransformer = new KnowEngineQueryTransformer(chatModel, chatMessageId);

        // 创建检索增强器
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(new DefaultQueryRouter(embeddingRetriever, fullTextRetriever))
                .queryTransformer(queryTransformer)
                .build();

        CommonChatService aiService = AiServices.builder(CommonChatService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .retrievalAugmentor(retrievalAugmentor)
                .build();

        Flux<String> result = aiService.streamChat(UUID.randomUUID().toString(), query);

        return result;

    }

    @GetMapping("testPromptRouter")
    public String testPromptRouter(String query) {
        IntentRecognitionResult intentRecognitionResult = AiServices.builder(IntentRecognitionService.class).chatModel(chatModel).build().chat(UUID.randomUUID().toString(), query);
        return promptService.getPrompt(intentRecognitionResult);
    }

    @GetMapping("testPromptRouter1")
    public String testPromptRouter1(String query) {


        IntentRecognitionResult intentRecognitionResult = AiServices.builder(IntentRecognitionService.class).chatModel(chatModel).build().chat(UUID.randomUUID().toString(), query);
        String prompt = promptService.getPrompt(intentRecognitionResult);
        ContentInjector contentInjector = new DefaultContentInjector(PromptTemplate.from(prompt));

        List<Content> testContents = List.of(
                Content.from(TextSegment.from("Java是一种面向对象的编程语言，具有跨平台、安全性高等特点，广泛应用于企业级开发。")),
                Content.from(TextSegment.from("Python是一种解释型的高级编程语言，以简洁易读的语法著称，常用于数据科学和人工智能领域。")),
                Content.from(TextSegment.from("JavaScript是一种脚本语言，主要用于Web前端开发，也可以通过Node.js进行服务端编程。")),
                Content.from(TextSegment.from("Java虚拟机（JVM）是运行Java字节码的虚拟机，它使得Java具有跨平台能力。Spring是最流行的Java开发框架。")),
                Content.from(TextSegment.from("Go语言由Google开发，以高并发和简洁语法为特色，常用于微服务和云原生开发。"))
        );
        System.out.println(JSON.toJSONString(contentInjector.inject(testContents, new UserMessage(query))));
        return ((UserMessage) contentInjector.inject(testContents, new UserMessage(query))).singleText();
    }

    @GetMapping("debugRetriever")
    public String debugRetriever(String query) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();

        KnowEngineElasticsearchContentRetriever knnRetriever = KnowEngineElasticsearchContentRetriever.builder()
                .restClient(restClient)
                .embeddingModel(openAiEmbeddingModel)
                .configuration(ElasticsearchConfigurationKnn.builder().build())
                .maxResults(MAX_RESULT)
                .indexName(INDEX_NAME)
                .minScore(MIN_SCORE)
                .knowledgeSegmentService(knowledgeSegmentService)
                .build();

        KnowEngineElasticsearchContentRetriever fullTextRetriever = KnowEngineElasticsearchContentRetriever.builder()
                .restClient(restClient)
                .embeddingModel(openAiEmbeddingModel)
                .configuration(ElasticsearchConfigurationFullText.builder().build())
                .maxResults(MAX_RESULT)
                .indexName(INDEX_NAME)
                .knowledgeSegmentService(knowledgeSegmentService)
                .build();

        try {
            result.put("knn", describe(knnRetriever.retrieve(new Query(query))));
        } catch (Exception e) {
            result.put("knn", "ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        try {
            result.put("fullText", describe(fullTextRetriever.retrieve(new Query(query))));
        } catch (Exception e) {
            result.put("fullText", "ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        return JSON.toJSONString(result);
    }

    private List<Map<String, Object>> describe(List<Content> contents) {
        return contents.stream().map(c -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            String text = c.textSegment().text();
            m.put("score", c.metadata().get(ContentMetadata.SCORE));
            m.put("fileName", c.textSegment().metadata().getString("fileName"));
            m.put("chunkId", c.textSegment().metadata().getString("chunkId"));
            m.put("text", text.substring(0, Math.min(90, text.length())));
            return m;
        }).collect(Collectors.toList());
    }

    @GetMapping("testReranker")
    public String testReranker(String query) {
        if (query == null || query.isBlank()) {
            query = "什么是Java？";
        }

        // 1. 获取 BGE-RERANKER 单例
        OnnxScoringModel scoringModel = BgeScoringModel.getInstance();

        // 2. 构造测试文档，模拟检索结果
        List<Content> testContents = List.of(
                Content.from(TextSegment.from("Java是一种面向对象的编程语言，具有跨平台、安全性高等特点，广泛应用于企业级开发。")),
                Content.from(TextSegment.from("Python是一种解释型的高级编程语言，以简洁易读的语法著称，常用于数据科学和人工智能领域。")),
                Content.from(TextSegment.from("JavaScript是一种脚本语言，主要用于Web前端开发，也可以通过Node.js进行服务端编程。")),
                Content.from(TextSegment.from("Java虚拟机（JVM）是运行Java字节码的虚拟机，它使得Java具有跨平台能力。Spring是最流行的Java开发框架。")),
                Content.from(TextSegment.from("Go语言由Google开发，以高并发和简洁语法为特色，常用于微服务和云原生开发。"))
        );

        // 3. 构建 ReRankingContentAggregator
        ContentAggregator aggregator = ReRankingContentAggregator.builder()
                .scoringModel(scoringModel)
                .build();

        // 4. 直接调用 ContentAggregator 进行重排序
        Query queryObj = new Query(query);
        List<Content> rerankedContents = aggregator.aggregate(Map.of(queryObj, List.of(testContents)));

        // 5. 格式化输出结果
        return rerankedContents.stream()
                .map(content -> {
                    TextSegment segment = content.textSegment();
                    Double rerankedScore = (Double) content.metadata().get(ContentMetadata.RERANKED_SCORE);
                    return String.format("[rerankedScore=%.4f] %s",
                            rerankedScore != null ? rerankedScore : 0.0,
                            segment.text());
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
