package com.chaconez.knowengine.rag.modules;

import com.chaconez.knowengine.chat.service.ChatMessageService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.invocation.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * KnowEngineQueryTransformer 单元测试
 */
@ExtendWith(MockitoExtension.class)
class KnowEngineQueryTransformerTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private ChatMessageService chatMessageService;

    private static final String TEST_ASSISTANT_MSG_ID = "test-msg-id-123";
    private static final String TEST_QUERY = "特斯拉毛豆3多少钱";
    private static final String TRANSFORMED_RESPONSE = "Tesla Model 3官方指导价";

    @BeforeEach
    void setUp() {
        // 重置 ApplicationContext
        KnowEngineQueryTransformer.setApplicationContext(null);
    }

    @Test
    @DisplayName("测试基本查询改写 - 无进度回调、无回写")
    void testTransform_BasicSuccess() {
        // Given
        when(chatModel.chat(anyString())).thenReturn(TRANSFORMED_RESPONSE);
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(chatModel, null);
        Query originalQuery = Query.from(TEST_QUERY);

        // When
        Collection<Query> result = transformer.transform(originalQuery);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        List<Query> resultList = new ArrayList<>(result);
        
        // 验证返回两个查询：改写后的查询和原始查询
        Query transformedQuery = resultList.get(0);
        Query originalQueryReturned = resultList.get(1);
        
        // 改写后的查询应包含 LLM 返回的内容
        assertTrue(transformedQuery.text().contains(TRANSFORMED_RESPONSE));
        assertTrue(transformedQuery.text().contains("我的问题是："));
        assertTrue(transformedQuery.text().contains("我的用户Id是: 123321"));
        assertTrue(transformedQuery.text().contains("现在是："));
        
        // 原始查询保持不变
        assertEquals(TEST_QUERY, originalQueryReturned.text());
        
        verify(chatModel, times(1)).chat(anyString());
    }

    @Test
    @DisplayName("测试带进度回调的查询改写")
    void testTransform_WithProgressCallback() {
        // Given
        when(chatModel.chat(anyString())).thenReturn(TRANSFORMED_RESPONSE);
        List<String> progressMessages = new ArrayList<>();
        Consumer<String> progressCallback = progressMessages::add;
        
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(
                chatModel, null, progressCallback);
        Query originalQuery = Query.from(TEST_QUERY);

        // When
        Collection<Query> result = transformer.transform(originalQuery);

        // Then
        assertNotNull(result);
        assertEquals(1, progressMessages.size());
        assertEquals("[PROGRESS]:正在优化您的问题...", progressMessages.get(0));
    }

    @Test
    @DisplayName("测试自定义 PromptTemplate 构造")
    void testTransform_WithCustomPromptTemplate() {
        // Given
        when(chatModel.chat(anyString())).thenReturn(TRANSFORMED_RESPONSE);
        PromptTemplate customPrompt = PromptTemplate.from("自定义Prompt: {{query}}");
        
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(
                chatModel, customPrompt, null, null);
        Query originalQuery = Query.from(TEST_QUERY);

        // When
        Collection<Query> result = transformer.transform(originalQuery);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        verify(chatModel, times(1)).chat(anyString());
    }

    @Test
    @DisplayName("测试带 assistantMsgId 但无 ApplicationContext - 不应抛出异常")
    void testTransform_WithAssistantMsgIdButNoContext() {
        // Given
        when(chatModel.chat(anyString())).thenReturn(TRANSFORMED_RESPONSE);
        // 不设置 ApplicationContext
        
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(
                chatModel, TEST_ASSISTANT_MSG_ID);
        Query originalQuery = Query.from(TEST_QUERY);

        // When & Then - 不应抛出异常
        assertDoesNotThrow(() -> {
            Collection<Query> result = transformer.transform(originalQuery);
            assertNotNull(result);
            assertEquals(2, result.size());
        });
        
        verify(chatModel, times(1)).chat(anyString());
    }

    @Test
    @DisplayName("测试带 assistantMsgId 和 ApplicationContext - 应触发异步回写")
    void testTransform_WithAssistantMsgIdAndContext() throws InterruptedException {
        // Given
        when(chatModel.chat(anyString())).thenReturn(TRANSFORMED_RESPONSE);
        when(applicationContext.getBean(ChatMessageService.class)).thenReturn(chatMessageService);
        
        KnowEngineQueryTransformer.setApplicationContext(applicationContext);
        
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(
                chatModel, TEST_ASSISTANT_MSG_ID);
        Query originalQuery = Query.from(TEST_QUERY);

        // When
        Collection<Query> result = transformer.transform(originalQuery);
        
        // 等待虚拟线程执行完成
        Thread.sleep(500);

        // Then
        assertNotNull(result);
        verify(chatModel, times(1)).chat(anyString());
        verify(applicationContext, times(1)).getBean(ChatMessageService.class);
        verify(chatMessageService, times(1)).updateTransformContent(eq(TEST_ASSISTANT_MSG_ID), anyString());
    }

    @Test
    @DisplayName("测试 LLM 返回空字符串的查询改写")
    void testTransform_EmptyLlmResponse() {
        // Given
        when(chatModel.chat(anyString())).thenReturn("");
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(chatModel, null);
        Query originalQuery = Query.from("测试问题");

        // When
        Collection<Query> result = transformer.transform(originalQuery);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        List<Query> resultList = new ArrayList<>(result);
        
        // 即使 LLM 返回空，也应构造查询
        assertTrue(resultList.get(0).text().contains("我的问题是："));
        assertEquals("测试问题", resultList.get(1).text());
    }

    @Test
    @DisplayName("测试带元数据的查询改写")
    void testTransform_QueryWithMetadata() {
        // Given
        when(chatModel.chat(anyString())).thenReturn(TRANSFORMED_RESPONSE);
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(chatModel, null);
        
        // 创建 InvocationContext 并设置 chatMemoryId
        InvocationContext invocationContext = InvocationContext.builder()
                .chatMemoryId("test-user")
                .build();
        
        // 使用 Builder 创建 Metadata
        Metadata metadata = Metadata.builder()
                .chatMessage(dev.langchain4j.data.message.UserMessage.from("test message"))
                .invocationContext(invocationContext)
                .build();
        Query queryWithMetadata = Query.from(TEST_QUERY, metadata);

        // When
        Collection<Query> result = transformer.transform(queryWithMetadata);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        List<Query> resultList = new ArrayList<>(result);
        
        // 改写后的查询应保留元数据
        Query transformedQuery = resultList.get(0);
        assertNotNull(transformedQuery.metadata());
        assertEquals("test-user", transformedQuery.metadata().chatMemoryId().toString());
    }

    @Test
    @DisplayName("测试 LLM 调用失败时的异常传播")
    void testTransform_LlmCallFailure() {
        // Given
        when(chatModel.chat(anyString())).thenThrow(new RuntimeException("LLM 服务异常"));
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(chatModel, null);
        Query originalQuery = Query.from(TEST_QUERY);

        // When & Then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            transformer.transform(originalQuery);
        });
        assertEquals("LLM 服务异常", exception.getMessage());
    }

    @Test
    @DisplayName("测试构造函数参数验证 - null ChatModel")
    void testConstructor_NullChatModel() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new KnowEngineQueryTransformer(null, null);
        });
    }

    @Test
    @DisplayName("测试构造函数参数验证 - null PromptTemplate")
    void testConstructor_NullPromptTemplate() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new KnowEngineQueryTransformer(chatModel, null, null, null);
        });
    }

    @Test
    @DisplayName("测试 ApplicationContext 设置和获取")
    void testApplicationContext_SetAndGet() {
        // Given
        when(applicationContext.getBean(ChatMessageService.class)).thenReturn(chatMessageService);
        
        // When
        KnowEngineQueryTransformer.setApplicationContext(applicationContext);
        
        // Then - 通过构造 transformer 并执行 transform 来验证
        when(chatModel.chat(anyString())).thenReturn(TRANSFORMED_RESPONSE);
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(
                chatModel, TEST_ASSISTANT_MSG_ID);
        
        Collection<Query> result = transformer.transform(Query.from(TEST_QUERY));
        
        assertNotNull(result);
        verify(applicationContext, times(1)).getBean(ChatMessageService.class);
    }

    @Test
    @DisplayName("测试 ChatMessageService 获取失败时的容错处理")
    void testGetChatMessageService_Failure() throws InterruptedException {
        // Given
        when(chatModel.chat(anyString())).thenReturn(TRANSFORMED_RESPONSE);
        when(applicationContext.getBean(ChatMessageService.class))
                .thenThrow(new RuntimeException("Bean 获取失败"));
        
        KnowEngineQueryTransformer.setApplicationContext(applicationContext);
        
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(
                chatModel, TEST_ASSISTANT_MSG_ID);
        Query originalQuery = Query.from(TEST_QUERY);

        // When & Then - 不应抛出异常，应正常返回结果
        assertDoesNotThrow(() -> {
            Collection<Query> result = transformer.transform(originalQuery);
            assertNotNull(result);
        });
        
        // 等待虚拟线程执行（虽然会失败，但不应影响主流程）
        Thread.sleep(500);
        
        verify(applicationContext, times(1)).getBean(ChatMessageService.class);
        verify(chatMessageService, never()).updateTransformContent(anyString(), anyString());
    }
}

/**
 * KnowEngineQueryTransformer 集成测试
 * <p>
 * 使用真实的 ChatModel 进行测试，不 mock LLM 服务
 * 需要配置正确的 API Key 才能运行
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowEngineQueryTransformerIntegrationTest {

    @Autowired
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        // 重置 ApplicationContext
        KnowEngineQueryTransformer.setApplicationContext(null);
    }

    @Test
    @DisplayName("集成测试：使用真实 LLM 进行查询改写")
    @Disabled("需要配置有效的 API Key 才能运行，取消注释此注解以运行")
    void testTransform_WithRealLLM() {
        // Given
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(chatModel, null);
        String userQuery = "特斯拉毛豆3多少钱";
        Query originalQuery = Query.from(userQuery);

        // When
        Collection<Query> result = transformer.transform(originalQuery);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        List<Query> resultList = new ArrayList<>(result);
        
        Query transformedQuery = resultList.get(0);
        Query originalQueryReturned = resultList.get(1);
        
        // 验证改写后的查询包含关键信息
        assertTrue(transformedQuery.text().contains("我的问题是："));
        assertTrue(transformedQuery.text().contains("我的用户Id是: 123321"));
        assertTrue(transformedQuery.text().contains("现在是："));
        
        // 原始查询保持不变
        assertEquals(userQuery, originalQueryReturned.text());
        
        // 打印改写结果供人工检查
        System.out.println("原始查询: " + userQuery);
        System.out.println("改写结果: " + transformedQuery.text());
    }

    @Test
    @DisplayName("集成测试：使用真实 LLM 改写复杂查询")
    @Disabled("需要配置有效的 API Key 才能运行，取消注释此注解以运行")
    void testTransform_ComplexQueryWithRealLLM() {
        // Given
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(chatModel, null);
        String userQuery = "我的车每次踩刹车的时候都有吱吱吱的声音很吵怎么办";
        Query originalQuery = Query.from(userQuery);

        // When
        Collection<Query> result = transformer.transform(originalQuery);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        List<Query> resultList = new ArrayList<>(result);
        
        Query transformedQuery = resultList.get(0);
        
        // 验证改写后的查询结构
        assertTrue(transformedQuery.text().contains("我的问题是："));
        assertTrue(transformedQuery.text().contains("我的用户Id是: 123321"));
        
        // 打印改写结果供人工检查
        System.out.println("原始查询: " + userQuery);
        System.out.println("改写结果: " + transformedQuery.text());
    }

    @Test
    @DisplayName("集成测试：使用真实 LLM 和进度回调")
    @Disabled("需要配置有效的 API Key 才能运行，取消注释此注解以运行")
    void testTransform_WithRealLLMAndProgressCallback() {
        // Given
        List<String> progressMessages = new ArrayList<>();
        Consumer<String> progressCallback = progressMessages::add;
        
        KnowEngineQueryTransformer transformer = new KnowEngineQueryTransformer(
                chatModel, null, progressCallback);
        String userQuery = "比亚迪汉续航多少公里";
        Query originalQuery = Query.from(userQuery);

        // When
        Collection<Query> result = transformer.transform(originalQuery);

        // Then
        assertNotNull(result);
        assertEquals(1, progressMessages.size());
        assertEquals("[PROGRESS]:正在优化您的问题...", progressMessages.get(0));
        
        // 打印改写结果
        List<Query> resultList = new ArrayList<>(result);
        System.out.println("原始查询: " + userQuery);
        System.out.println("改写结果: " + resultList.get(0).text());
    }
}
