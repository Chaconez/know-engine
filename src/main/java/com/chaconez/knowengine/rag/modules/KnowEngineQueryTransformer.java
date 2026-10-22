package com.chaconez.knowengine.rag.modules;

import com.chaconez.knowengine.chat.service.ChatMessageService;
import com.google.common.base.Stopwatch;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.joining;

/**
 * KnowEngine 查询改写器
 * <p>
 * 基于 LLM 对用户查询进行智能改写优化，结合历史对话上下文，提升 RAG 检索效果。
 * 支持5种改写策略：
 * <ul>
 *   <li><b>简洁改写</b>：删除无意义的语气词、修饰词，将疑问句转为陈述句</li>
 *   <li><b>抽象概念改写</b>：将具体问题转化为更基础、更抽象的查询表述</li>
 *   <li><b>错别字改写</b>：纠正错别字和拼音错误</li>
 *   <li><b>车型信息提取</b>：标准化提取用户提及的品牌、型号、年款等车型信息</li>
 *   <li><b>上下文补全</b>：结合历史对话识别相关细节和术语，将问题重组为独立完整的查询</li>
 * </ul>
 * <p>
 * <b>处理流程：</b>
 * <ol>
 *   <li>通过 progressCallback 发送进度事件通知前端"正在优化您的问题"</li>
 *   <li>从 Query metadata 中提取历史对话记忆，格式化为 Prompt 上下文</li>
 *   <li>使用 LLM 根据 Prompt 模板改写用户查询</li>
 *   <li>构造增强查询（附加用户ID、当前时间等上下文信息）</li>
 *   <li>通过 Java 21 虚拟线程异步回写改写结果到数据库（chat_message.transform_content）</li>
 *   <li>返回改写后的单个查询</li>
 * </ol>
 * <p>
 * <b>注意：</b>
 * <ul>
 *   <li>历史对话记忆由 {@code DatabaseChatMemoryStore} 提供，已过滤意图识别结果消息</li>
 *   <li>ChatMessageService 通过静态 ApplicationContext 延迟获取，避免构造时的循环依赖</li>
 * </ul>
 *
 * @see QueryTransformer
 * @see com.chaconez.knowengine.chat.memory.DatabaseChatMemoryStore
 */
@Slf4j
public class KnowEngineQueryTransformer implements QueryTransformer {

    protected final ChatModel chatModel;

    protected final PromptTemplate promptTemplate;

    /**
     * assistant 消息的 messageId，用于回写改写结果
     */
    private final String chatMessageId;

    /**
     * 进度回调，用于流式返回前端进度信息
     */
    private final Consumer<String> progressCallback;

    /**
     * Spring 容器，由使用方在构造时传入
     */
    private static volatile ApplicationContext applicationContext;

    /**
     * 注册全局 ApplicationContext（由 SpringContextHolder 调用一次即可）
     */
    public static void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    private ChatMessageService getChatMessageService() {
        if (applicationContext == null) {
            return null;
        }
        try {
            return applicationContext.getBean(ChatMessageService.class);
        } catch (Exception e) {
            log.warn("获取 ChatMessageService 失败", e);
            return null;
        }
    }

    private static final PromptTemplate LG_AGENT_PROMPT = PromptTemplate.from("""
            你是一个汽车智能客服助手，你的职责范围是汽车相关的咨询场景，包括购车咨询、车型信息、保养维修、保险年检、售后服务等。你需要对用户的问题进行改写，使得改写后的问题在查询向量数据库/关系型数据库/图数据库时有更好的结果，并删除任何无关信息，确保查询简洁明了、具体明确。下面有一些改写的策略。
            
            1、简洁改写。问题可能比较长，包含了一些无意义的语气词、修饰词或者重复的词语等。尤其是问题在询问车型配置、价格政策时，且包含一些无意义的日期、编号等修饰词。改写规则：删除无意义的词语使其更适合搜索引擎检索，疑问句要转成陈述句。
            2、抽象概念改写。前提：用户的问题一定在询问汽车相关的问题，且是一些比较具体的细节问题，比如"我的车每次踩刹车的时候都有吱吱吱的声音很吵怎么办"。需要改写成类似"车辆刹车异响故障排查"，将具体的问题转化为更基础、更简洁、更抽象的问题。
            3、错别字改写。用户的问题包含了错别字或者是一些常见的汽车术语用户打成了对应的拼音。大小写不一样不属于错别字。错别字需要给出纠正结果。
            4、车型信息提取。如果用户提到了具体的车型信息（品牌、型号、年款等），需要将其标准化提取。比如"特斯拉毛豆3"改写为"Tesla Model 3"，"比亚迪汉"保持不变。
            5、结合历史对话和最新提问，识别出所有相关的细节、术语和上下文信息。最后，将这条提问重新组织成一个清晰、简洁且独立完整的格式，以便于进行信息检索。
            
            上面是5种改写策略，需要逐一使用最终给出一个统一的改写结果。直接输出改写后的结果，不需要输出思考过程及额外的多余内容。如果不需要改写，则直接输出原问题即可。
            
            ### 示例
            
            Input：我如果想买一辆特斯拉Model 3的话，大概需要多少钱啊
            Output：Tesla Model 3官方指导价
            
            Input：我的车该保养了，多久保养一次？
            Output：车辆保养周期规定
            
            Input：我的比亚迪汉刹车有点异响是怎么回事
            Output：车辆刹车异响故障排查
            
            Input：毛豆Y续航多少
            Output：Tesla Model Y续航里程
            
            Input：保险什么时候到期？
            Output：车辆保险到期查询
            
            Input：年检怎么办理？
            Output：车辆年检办理流程
            
            Input：我想了解一下你们那款新出的电动车的配置
            Output：新款电动车车型配置参数
            
            ### 历史对话内容
            {{chatMemory}}
            
            ### 用户问题：
            
            {{query}}
            
            ### 要求：
            非常重要的一点是：你只需要提供重新组织后的提问，不要包含任何其他内容！绝对不要在提问前添加任何多余的文字！
            """);

    public KnowEngineQueryTransformer(ChatModel chatModel, String chatMessageId) {
        this(chatModel, LG_AGENT_PROMPT, chatMessageId, null);
    }

    public KnowEngineQueryTransformer(ChatModel chatModel, String chatMessageId, Consumer<String> progressCallback) {
        this(chatModel, LG_AGENT_PROMPT, chatMessageId, progressCallback);
    }

    public KnowEngineQueryTransformer(ChatModel chatModel, PromptTemplate promptTemplate, String chatMessageId, Consumer<String> progressCallback) {
        this.promptTemplate = ensureNotNull(promptTemplate, "promptTemplate");
        this.chatModel = ensureNotNull(chatModel, "chatModel");
        this.chatMessageId = chatMessageId;
        this.progressCallback = progressCallback;
    }

    @Override
    public Collection<Query> transform(Query query) {
        // 发送进度：开始问题改写
        if (progressCallback != null) {
            progressCallback.accept("[PROGRESS]:正在优化您的问题...");
            System.out.println("[PROGRESS]:正在优化您的问题...");
        }

        log.info("开始问题改写, 原始问题: {}", query.text());
        List<ChatMessage> chatMemory = query.metadata().chatMemory();

        Stopwatch stopwatch = Stopwatch.createStarted();
        String newQuery = chatModel.chat(createPrompt(query, format(chatMemory)).text());
        log.info("问题改写完成, 改写结果 : {} ,耗时: {}", newQuery, stopwatch.stop().elapsed(TimeUnit.MILLISECONDS));

        Query compressedQuery = query.metadata() == null
                ? Query.from(newQuery)
                : Query.from(newQuery, query.metadata());
        log.info("Compressed Success, source query: {}, compressed query: {}", query.text(), compressedQuery.text());

        // 异步回写改写结果到 chat_message
        if (chatMessageId != null) {
            ChatMessageService chatMessageService = getChatMessageService();
            if (chatMessageService != null) {
                Thread.ofVirtual().name("query-transform-" + chatMessageId).start(() -> {
                    try {
                        chatMessageService.updateTransformContent(chatMessageId, newQuery);
                        log.info("改写结果已回写: assistantMsgId={}, transformContent={}", chatMessageId, newQuery);
                    } catch (Exception e) {
                        log.warn("改写结果回写失败: assistantMsgId={}", chatMessageId, e);
                    }
                });
            }
        }

//        return List.of(compressedQuery, query);
        return singletonList(compressedQuery);
    }

    protected Prompt createPrompt(Query query, String chatMemory) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("query", query.text());
        variables.put("chatMemory", chatMemory);
        return promptTemplate.apply(variables);
    }

    protected String format(List<ChatMessage> chatMemory) {
        return chatMemory.stream()
                .map(this::format)
                .filter(Objects::nonNull)
                .collect(joining("\n"));
    }

    protected String format(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return "User: " + userMessage.singleText();
        } else if (message instanceof AiMessage aiMessage) {
            if (aiMessage.hasToolExecutionRequests()) {
                return null;
            }
            return "AI: " + aiMessage.text();
        } else {
            return null;
        }
    }

}
