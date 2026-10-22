package com.chaconez.knowengine.chat.service;

import com.chaconez.knowengine.ai.constant.KnowEngineIntent;
import com.chaconez.knowengine.ai.model.IntentRecognitionResult;
import com.chaconez.knowengine.ai.service.CommonChatService;
import com.chaconez.knowengine.ai.service.IntentRecognitionService;
import com.chaconez.knowengine.ai.service.KnowEngineChatAiService;
import com.chaconez.knowengine.ai.service.PromptService;
import com.chaconez.knowengine.ai.service.TitleSummaryService;
import com.chaconez.knowengine.business.converter.CarInfoConverter;
import com.chaconez.knowengine.business.converter.MyCarConverter;
import com.chaconez.knowengine.business.entity.CarInfo;
import com.chaconez.knowengine.business.entity.MyCar;
import com.chaconez.knowengine.business.service.CarInfoService;
import com.chaconez.knowengine.business.service.MyCarService;
import com.chaconez.knowengine.business.service.UserRoleService;
import com.chaconez.knowengine.chat.constant.ChatSource;
import com.chaconez.knowengine.cache.service.QaCacheService;
import com.chaconez.knowengine.chat.entity.ChatParam;
import com.chaconez.knowengine.chat.memory.DatabaseChatMemoryStore;
import com.chaconez.knowengine.document.entity.TableMeta;
import com.chaconez.knowengine.document.service.KnowEngineTableMetaService;
import com.chaconez.knowengine.document.service.KnowledgeSegmentService;
import com.chaconez.knowengine.document.util.DocumentPermissionUtils;
import com.chaconez.knowengine.rag.constant.RoleEnum;
import com.chaconez.knowengine.rag.modules.*;
import com.chaconez.knowengine.rag.modules.reranker.BgeScoringModel;
import com.alibaba.fastjson2.JSON;
import dev.langchain4j.community.rag.content.retriever.neo4j.Neo4jGraph;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationFullText;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationKnn;
import dev.langchain4j.store.embedding.filter.Filter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.RestClient;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.chaconez.knowengine.rag.config.ElasticSearchConfiguration.INDEX_NAME;
import static com.chaconez.knowengine.rag.constant.MetadataKeyConstant.ACCESSIBLE_BY;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static java.nio.charset.StandardCharsets.UTF_8;

@Service
@Slf4j
public class ChatApplicationService {

    @Autowired
    private QaCacheService qaCacheService;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private CommonChatService commonChatService;

    private IntentRecognitionService intentRecognitionService;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private RestClient restClient;

    @Autowired
    private Driver neo4jDriver;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private KnowEngineTableMetaService knowEngineTableMetaService;

    @Autowired
    private PromptService promptService;

    @Autowired
    private OpenAiEmbeddingModel openAiEmbeddingModel;

    @Autowired
    private MyCarService myCarService;

    @Autowired
    private CarInfoService carInfoService;

    @Autowired
    private DatabaseChatMemoryStore databaseChatMemoryStore;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String chatModelApiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String chatModelBaseUrl;

    @Value("classpath:prompts/text-to-sql-prompt.txt")
    private Resource textToSqlPrompt;

    @Value("classpath:prompts/text-to-cypher-prompt.txt")
    private Resource textToCypherPrompt;

    @Value("classpath:sql/retrieve_tables.sql")
    private Resource tablesSql;

    /**
     * RAG 对话生成专用 ChatModel，使用更强的模型和较低温度以提升回答质量
     */
    private StreamingChatModel ragChatModel;

    @PostConstruct
    public void init() {
        ragChatModel = OpenAiStreamingChatModel.builder()
                .apiKey(chatModelApiKey)
                .baseUrl(chatModelBaseUrl)
                .modelName("qwen3.6-plus")
                .logRequests(true)
                .logRequests(true)
                .temperature(0.2)
                .topP(0.9)
                .customParameters(Map.of("enable_thinking", false))
                .build();

        intentRecognitionService = AiServices.builder(IntentRecognitionService.class).chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder().id(memoryId).maxMessages(10).chatMemoryStore(databaseChatMemoryStore).build()).build();
    }

    /**
     * 统一流式对话入口。
     * <p>
     * 封装原本散落在 {@link com.chaconez.knowengine.chat.controller.ChatController} 中的完整对话流程：
     * 会话创建（可选）、异步标题生成、保存用户/助手消息、意图识别、不相关问题兜底通用对话、
     * 相关问题 RAG 对话。HTTP 接口与钉钉机器人回调均通过此方法复用同一条对话链路。
     *
     * @param userId         用户ID
     * @param content        用户问题
     * @param conversationId 会话ID（可选，为空则自动创建新会话）
     * @return 包含进度消息、[DONE] 事件及 LLM token 的 SSE 流
     */
    public Flux<String> chat(String userId, String content, String conversationId, ChatSource chatSource) {
        // 1. 处理会话：没有 conversationId 则创建新会话
        final String finalConversationId;
        if (conversationId == null || conversationId.isBlank()) {
            String tempTitle = content.substring(0, Math.min(content.length(), 20));
            finalConversationId = chatConversationService.createConversation(userId, tempTitle);
            log.info("创建新会话: conversationId={}, tempTitle={}", finalConversationId, tempTitle);

            // 异步：用虚拟线程调用 LLM 生成摘要标题，完成后回写到数据库
            Thread.ofVirtual().name("title-summary-" + finalConversationId).start(() -> {
                try {
                    OpenAiChatModel titleChatModel = OpenAiChatModel.builder()
                            .apiKey(chatModelApiKey)
                            .modelName("qwen3.5-flash")
                            .temperature(0.7)
                            .baseUrl(chatModelBaseUrl)
                            .customParameters(Map.of("enable_thinking", false))
                            .build();
                    TitleSummaryService titleSummaryService = AiServices.builder(TitleSummaryService.class)
                            .chatModel(titleChatModel)
                            .build();
                    String aiTitle = titleSummaryService.generateTitle(content);
                    chatConversationService.updateTitle(finalConversationId, aiTitle);
                    log.info("异步标题更新完成: conversationId={}, title={}", finalConversationId, aiTitle);
                } catch (Exception e) {
                    log.warn("异步标题生成失败, 保留临时标题: conversationId={}", finalConversationId, e);
                }
            });
        } else {
            finalConversationId = conversationId;
        }

        // 2. 保存用户消息
        String messageId = chatMessageService.saveUserMessage(finalConversationId, content);
        String assistantMessageId = chatMessageService.saveAssistantMessage(finalConversationId);

        // 3. 流式返回：先发送意图识别进度，再执行意图识别
        Flux<String> normalAnswer = Flux.just("[PROGRESS]:正在识别您的意图...")
                .concatWith(
                        Mono.fromCallable(() -> intentRecognitionService.chat(finalConversationId, content))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(intentRecognitionResult -> {
                                    // 意图识别完成后清除缓存，避免意图识别的AI响应污染后续RAG对话的历史记忆
                                    databaseChatMemoryStore.evictCache(finalConversationId);

                                    // 4. 如果用户问题不相关，使用一个通用的LLM做对话
                                    if (!intentRecognitionResult.related()) {
                                        StringBuilder contentBuilder = new StringBuilder();
                                        return Flux.concat(
                                                Flux.just("[PROGRESS]:正在为您生成回答..."),
                                                commonChatService.streamChat(userId, content)
                                                        .doOnNext(token -> contentBuilder.append(token))
                                                        .doOnComplete(() -> chatMessageService.updateContent(assistantMessageId, contentBuilder.toString()))
                                        );
                                    }

                                    // 5. 相关问题，走RAG流程（进度由内部组件发出）
                                    return ragChat(new ChatParam(userId, finalConversationId, messageId, content, assistantMessageId, intentRecognitionResult, chatSource));
                                })
                );

        return Mono.fromCallable(() -> qaCacheService.lookup(content, finalConversationId, messageId, assistantMessageId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(cachedOpt -> {
                    if (cachedOpt.isEmpty()) {
                        return normalAnswer;
                    }
                    var cachedAnswer = cachedOpt.get();
                    Flux<String> result = Flux.defer(() -> {
                        chatMessageService.updateContent(assistantMessageId, cachedAnswer.answer());
                        if (!CollectionUtils.isEmpty(cachedAnswer.references())) {
                            chatMessageService.updateRagReferences(assistantMessageId, cachedAnswer.references());
                        }
                        databaseChatMemoryStore.evictCache(finalConversationId);
                        return Flux.just(cachedAnswer.answer());
                    });
                    if (!CollectionUtils.isEmpty(cachedAnswer.references()) && chatSource == ChatSource.USER_WEB) {
                        result = result.concatWith(Flux.just("[REFERENCE]:" + JSON.toJSONString(cachedAnswer.references())));
                    }
                    return result;
                })
                .doOnError(e -> log.error("流式对话异常: conversationId={}", finalConversationId, e))
                .concatWith(chatSource == ChatSource.USER_WEB
                        ? Mono.fromCallable(() -> chatMessageService.getByMessageId(assistantMessageId))
                            .subscribeOn(Schedulers.boundedElastic())
                            .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                            .map(message -> "[ANSWER_MESSAGE]:" + assistantMessageId)
                        : Mono.empty())
                .concatWith(Mono.just("[DONE]:" + finalConversationId));
    }

    /**
     * RAG 流式对话。
     * <p>
     * 1. 根据意图识别结果，判断是否需要车辆信息
     * 2. 如果车辆信息不完善，则返回车辆信息不完善提示
     * 3. 根据意图识别结果，判断是否需要车辆信息
     * </p>
     */
    public Flux<String> ragChat(ChatParam chatParam) {
        KnowEngineIntent intent = KnowEngineIntent.getIntent(chatParam.intentRecognitionResult());

        /**
         * 只有用户通过网页端访问时，才需要车辆信息
         */
        if(chatParam.chatSource() == ChatSource.USER_WEB){
            // 如果是维保服务、技术支持，则需要车辆信息
            if (intent == KnowEngineIntent.CAR_MAINTENANCE
                    || intent == KnowEngineIntent.CAR_TECH_SUPPORT) {
                if (chatParam.intentRecognitionResult().entities().car_id() == null) {
                    List<MyCar> myCars = myCarService.getCarByUserId(chatParam.userId());
                    if (CollectionUtils.isEmpty(myCars)) {
                        return Flux.just("[WARN]:您还没有添加车辆信息，请先添加车辆信息");
                    } else if (myCars.size() >= 1) {
                        return Flux.just("[CARD]:请先选择车辆")
                                .concatWith(Flux.just("[CARD_CHOICE_MYCAR]:" + JSON.toJSONString(MyCarConverter.INSTANCE.toVOList(myCars))));
                    }
                }
            }

            // 如果是营销政策，则需要车辆信息
            if (intent == KnowEngineIntent.CAR_MARKETING) {
                if (chatParam.intentRecognitionResult().entities().car_model() == null) {
                    List<CarInfo> carInfoList = carInfoService.getCarInfoByBrand(null);
                    return Flux.just("[CARD]:请先选择您要咨询的车辆")
                            .concatWith(Flux.just("[CARD_CHOICE_CAR]:" + JSON.toJSONString(CarInfoConverter.INSTANCE.toVOList(carInfoList))));
                }
            }
        }

        return doChat(chatParam);
    }

    /**
     * 流式对话
     * <p>
     * 使用 Flux.create() 将 RAG 管道各环节的进度消息与 LLM 流式输出桥接到同一个 Flux 中，
     * 确保进度消息在对应的 LLM token 之前到达前端。
     * <p>
     * 进度推送环节：
     * <ol>
     *   <li>问题改写 — 由 {@link KnowEngineQueryTransformer} 发送</li>
     *   <li>问题路由 — 由 {@link KnowEngineQueryRouter} 发送</li>
     *   <li>排序筛选 — 由 {@link ProgressAwareContentAggregator} 发送</li>
     *   <li>生成回答 — 由 {@link ProgressAwareContentAggregator} 在聚合完成后发送</li>
     * </ol>
     *
     * @param chatParam 对话参数
     */
    public Flux<String> doChat(ChatParam chatParam) {

        return Flux.<String>create(sink -> {
                    // 进度回调：同时写入 sink 和外部回调
                    Consumer<String> processCallback = sink::next;

                    // 构建查询改写器（带进度回调）
                    KnowEngineQueryTransformer queryTransformer = new KnowEngineQueryTransformer(chatModel, chatParam.messageId(), processCallback);

                    Filter accessibleByFilter = buildFilter(chatParam);

                    ProgressAwareContentRetriever embeddingRetriever = new ProgressAwareContentRetriever(KnowEngineElasticsearchContentRetriever.builder()
                            .configuration(ElasticsearchConfigurationKnn.builder().build())
                            .maxResults(5)
                            .minScore(0.5)
                            .embeddingModel(openAiEmbeddingModel)
                            .restClient(restClient)
                            .indexName(INDEX_NAME)
                            .knowledgeSegmentService(knowledgeSegmentService)
                            .filter(accessibleByFilter)
                            .build(), processCallback);

                    ProgressAwareContentRetriever fullTextRetriever = new ProgressAwareContentRetriever(KnowEngineElasticsearchContentRetriever.builder()
                            .configuration(ElasticsearchConfigurationFullText.builder().build())
                            .restClient(restClient)
                            .embeddingModel(openAiEmbeddingModel)
                            .knowledgeSegmentService(knowledgeSegmentService)
                            .indexName(INDEX_NAME)
                            .filter(accessibleByFilter)
                            .maxResults(5)
                            .build(), processCallback);

                    ProgressAwareContentRetriever sqlRetriever = null;
                    try {
                        // 拼接静态表结构 + table_meta 中动态创建的表结构
                        String databaseStructure = buildDatabaseStructure();
                        sqlRetriever = new ProgressAwareContentRetriever(
                                KnowEngineSqlDatabaseContentRetriever.builder()
                                        .dataSource(dataSource)
                                        .promptTemplate(new PromptTemplate(textToSqlPrompt.getContentAsString(UTF_8)))
                                        .databaseStructure(databaseStructure)
                                        .chatModel(chatModel)
                                        .fallbackRetriever(embeddingRetriever)
                                        .build(), processCallback);
                    } catch (IOException e) {
                        log.warn("Error creating SQL retriever", e);
                    }

                    ProgressAwareContentRetriever neo4jRetriever = null;
                    try {
                        neo4jRetriever = new ProgressAwareContentRetriever(
                                KnowEngineNeo4jContentRetriever.builder()
                                        .graph(Neo4jGraph.builder()
                                                .driver(neo4jDriver)
                                                .build())
                                        .chatModel(chatModel)
                                        .promptTemplate(new PromptTemplate(textToCypherPrompt.getContentAsString(UTF_8)))
                                        .fallbackRetriever(embeddingRetriever)
                                        .build(), processCallback);
                    } catch (IOException e) {
                        log.warn("Error creating Neo4j retriever", e);
                    }

                    OnnxScoringModel scoringModel = BgeScoringModel.getInstance();

                    // 使用带进度通知的聚合器包装原始聚合器
                    // 混合聚合器：SQL/Cypher 结构化结果直接透传，仅对向量/全文检索结果做 RRF 融合和重排序
                    ContentAggregator contentAggregator = new ProgressAwareContentAggregator(
                            new KnowEngineHybridContentAggregator(
                                    KnowEngineReRankingContentAggregator.builder()
                                            .scoringModel(scoringModel)
                                            .minScore(0.6)
                                            .maxResults(5)
                                            .querySelector(queryToContents -> queryToContents.keySet().iterator().next())
                                            .build()
                            ),
                            processCallback, chatParam.assistantMessageId(), chatMessageService
                    );

                    String prompt = promptService.getPrompt(chatParam.intentRecognitionResult());

                    ContentInjector contentInjector = new DefaultContentInjector();

                    // 构建查询路由器（带进度回调）
                    RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                            .queryRouter(new KnowEngineQueryRouter(List.of(embeddingRetriever, fullTextRetriever, sqlRetriever, neo4jRetriever), chatModel, processCallback))
                            .queryTransformer(queryTransformer)
                            .contentAggregator(contentAggregator)
                            .contentInjector(contentInjector)
                            .build();

                    KnowEngineChatAiService knowEngineChatAiService = AiServices.builder(KnowEngineChatAiService.class)
//                            .chatModel(chatModel)
                            .streamingChatModel(ragChatModel)
                            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                                    .id(memoryId)
                                    .maxMessages(10)
                                    .chatMemoryStore(databaseChatMemoryStore)
                                    .build())
                            .systemMessage(prompt)
                            .retrievalAugmentor(retrievalAugmentor)
                            .build();

                    // 订阅 LLM 流式输出，桥接到 sink
                    AtomicBoolean firstToken = new AtomicBoolean(true);
                    StringBuilder contentBuilder = new StringBuilder();
                    Disposable disposable = knowEngineChatAiService.streamChat(chatParam.conversationId(), chatParam.content())
                            .doOnNext(token -> {
                                // 首个 token 到达时，如果之前没有发出"正在生成回答"，则补发
                                // （正常情况下由 ProgressAwareContentAggregator 已发出，此处为兜底）
                                if (firstToken.compareAndSet(true, false)) {
                                    // 标记已开始接收 token
                                }
                                contentBuilder.append(token);
                            })
                            .doOnComplete(() -> chatMessageService.updateContent(chatParam.assistantMessageId(), contentBuilder.toString()))
                            .subscribe(sink::next, sink::error, sink::complete);

                    // 取消时同步取消内部订阅
                    sink.onCancel(disposable::dispose);
                })
                .subscribeOn(Schedulers.boundedElastic())
                // publishOn 引入异步边界：boundedElastic 线程专用于执行阻塞 RAG 操作，
                // parallel 线程独立运行 drain loop，确保进度消息能及时推送到前端 SSE 响应
                .publishOn(Schedulers.parallel());
    }

    /**
     * 构建数据库结构描述
     * <p>
     * 将静态表结构（retrieve_tables.sql）与 table_meta 表中动态创建的表结构合并，
     * 作为 Text2SQL Prompt 的 databaseStructure 参数，使 LLM 感知所有可查询的表。
     */
    private String buildDatabaseStructure() throws IOException {
        StringBuilder sb = new StringBuilder();
        // 静态表结构
        sb.append(tablesSql.getContentAsString(UTF_8));

        // 从 table_meta 读取当前激活版本对应的动态表结构
        List<TableMeta> tableMetas = knowEngineTableMetaService.listActiveForQuery();
        if (!CollectionUtils.isEmpty(tableMetas)) {
            sb.append("\n\n");
            String dynamicSql = tableMetas.stream()
                    .filter(meta -> meta.getCreateSql() != null && !meta.getCreateSql().isBlank())
                    .map(TableMeta::getCreateSql)
                    .collect(Collectors.joining("\n\n"));
            sb.append(dynamicSql);
        }
        return sb.toString();
    }

    @Autowired
    private UserRoleService userRoleService;

    /**
     * 构造权限过滤器
     *
     * @param chatParam
     * @return
     */
    private Filter buildFilter(ChatParam chatParam) {
        // 默认权限过滤器：允许访客权限
        Filter permissionFilter = metadataKey(ACCESSIBLE_BY).isEqualTo(RoleEnum.VISITOR.name());

        // 根据用户角色获取权限
        RoleEnum roleEnum = userRoleService.getUserRole(chatParam);

        // 获取该文档支持的所有权限
        String[] permissions = DocumentPermissionUtils.getDocumentAccessiblePermission(roleEnum);

        for (String permission : permissions) {
            // 非访客权限时，将权限用or连接，表示支持多种权限
            if (!RoleEnum.VISITOR.name().equals(permission)) {
                permissionFilter = permissionFilter.or(metadataKey(ACCESSIBLE_BY).isEqualTo(permission));
            }
        }

        return permissionFilter;
    }

}
