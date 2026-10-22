package com.chaconez.knowengine.cache.service;

import static org.junit.jupiter.api.Assertions.*;

import com.chaconez.knowengine.cache.entity.QaCacheEntry;
import com.chaconez.knowengine.chat.service.impl.ChatMessageServiceImpl;
import com.chaconez.knowengine.chat.service.ChatConversationService;
import com.chaconez.knowengine.chat.service.impl.ChatConversationServiceImpl;
import com.chaconez.knowengine.chat.mapper.ChatConversationMapper;
import com.chaconez.knowengine.document.mapper.KnowledgeSegmentMapper;
import com.chaconez.knowengine.document.service.impl.KnowledgeSegmentServiceImpl;
import com.chaconez.knowengine.document.mapper.KnowledgeDocumentMapper;
import com.chaconez.knowengine.document.service.impl.KnowledgeDocumentServiceImpl;
import com.chaconez.knowengine.document.mapper.KnowledgeDocumentVersionMapper;
import com.chaconez.knowengine.document.service.impl.KnowledgeDocumentVersionServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Matcher;

/**
 * 通过真实 MyBatis-Plus Mapper 执行被测业务；JdbcTemplate 仅用于建表、准备样本和断言。
 */
public class QaCacheMapperTest {

    JdbcTemplate jdbc;

    ChatConversationService conversationService;

    QaCacheEntryService repository;

    KnowledgeDocumentServiceImpl documentService;

    com.chaconez.knowengine.chat.mapper.ChatMessageMapper feedbackMapper;

    QaCacheLifecycleService lifecycle;

    TransactionTemplate tx;

    com.chaconez.knowengine.chat.service.ChatMessageService feedback;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        MybatisConfiguration configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatConversationMapper.class);
        configuration.addMapper(
                com.chaconez.knowengine.cache.mapper.QaCacheEntryMapper.class);
        configuration.addMapper(KnowledgeSegmentMapper.class);
        configuration.addMapper(KnowledgeDocumentMapper.class);
        configuration.addMapper(KnowledgeDocumentVersionMapper.class);
        configuration.addMapper(
                com.chaconez.knowengine.chat.mapper.ChatMessageMapper.class);
        MybatisSqlSessionFactoryBean factoryBean =
                new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(ds);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(
                new com.chaconez.knowengine.config.MybatisPlusConfig()
                        .mybatisPlusInterceptor());
        SqlSessionTemplate session = new org.mybatis.spring.SqlSessionTemplate(factoryBean.getObject());
        conversationService = new ChatConversationServiceImpl();
        ReflectionTestUtils.setField(conversationService, "baseMapper", session.getMapper(ChatConversationMapper.class));
        feedbackMapper =
                session.getMapper(
                        com.chaconez.knowengine.chat.mapper.ChatMessageMapper
                                .class);
        KnowledgeSegmentServiceImpl segmentService = new KnowledgeSegmentServiceImpl();
        ReflectionTestUtils.setField(segmentService, "baseMapper", session.getMapper(KnowledgeSegmentMapper.class));
        documentService = new KnowledgeDocumentServiceImpl();
        ReflectionTestUtils.setField(documentService, "baseMapper", session.getMapper(KnowledgeDocumentMapper.class));
        KnowledgeDocumentVersionServiceImpl documentVersionService = new KnowledgeDocumentVersionServiceImpl();
        ReflectionTestUtils.setField(documentVersionService, "baseMapper", session.getMapper(KnowledgeDocumentVersionMapper.class));
        repository =
                new com.chaconez.knowengine.cache.service.impl.QaCacheEntryServiceImpl(
                        session.getMapper(
                                com.chaconez.knowengine.cache.mapper.QaCacheEntryMapper
                                        .class),
                        feedbackMapper, segmentService, documentService, documentVersionService);
        lifecycle = new QaCacheLifecycleService(repository);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        feedback = new ChatMessageServiceImpl(feedbackMapper, conversationService);
        String schema = Files.readString(Path.of("src/main/resources/sql/tables.sql"));
        Matcher tables =
                java.util.regex.Pattern.compile(
                                "(?is)CREATE TABLE IF NOT EXISTS"
                                    + " (?:qa_cache_entry)\\s*\\(.*?;")
                        .matcher(schema);
        int tableCount = 0;
        while (tables.find()) {
            jdbc.execute(tables.group().replace("sources JSON", "sources MEDIUMTEXT")
                    .replace("dependencies JSON", "dependencies MEDIUMTEXT"));
            tableCount++;
        }
        assertEquals(1, tableCount, "缓存条目表必须统一定义在 tables.sql");
        jdbc.execute("CREATE TABLE knowledge_segment(document_id BIGINT,document_version BIGINT,chunk_id VARCHAR(64),embedding_id VARCHAR(64),deleted INT)");
        jdbc.execute(
                "CREATE TABLE knowledge_document(doc_id BIGINT PRIMARY KEY,current_version_id"
                        + " BIGINT,deleted INT,accessible_by VARCHAR(32))");
        jdbc.execute(
                "CREATE TABLE knowledge_document_version(version_id BIGINT PRIMARY KEY,doc_id"
                        + " BIGINT,deleted INT,status VARCHAR(32))");
        jdbc.execute(
                "CREATE TABLE chat_conversation(conversation_id VARCHAR(64) PRIMARY KEY,user_id"
                        + " VARCHAR(64),deleted INT)");
        jdbc.execute(
                "CREATE TABLE chat_message(id BIGINT AUTO_INCREMENT PRIMARY KEY,message_id VARCHAR(64) UNIQUE,conversation_id"
                        + " VARCHAR(64),type VARCHAR(32),content VARCHAR(100),deleted INT,metadata"
                        + " VARCHAR(1000),cache_hit TINYINT NOT NULL DEFAULT 0,helpful TINYINT,feedback_comment VARCHAR(1000),feedback_at TIMESTAMP(6),transform_content VARCHAR(500),token_count INT,model_name VARCHAR(128),rag_references VARCHAR(1000),created_at TIMESTAMP,updated_at TIMESTAMP,lock_version INT DEFAULT 0)");
        jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.chaconez.knowengine.cache.service.QaCacheMapperTest.jsonExtract'");
        jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.chaconez.knowengine.cache.service.QaCacheMapperTest.jsonUnquote'");
        jdbc.execute("CREATE ALIAS JSON_SET FOR 'com.chaconez.knowengine.cache.service.QaCacheMapperTest.jsonSet'");
        jdbc.execute("CREATE ALIAS JSON_LENGTH FOR 'com.chaconez.knowengine.cache.service.QaCacheMapperTest.jsonLength'");
        jdbc.update(
                "INSERT INTO"
                    + " qa_cache_entry(id,fingerprint,question,answer,sources,frequency,quality,status,expires_at,activated_at)"
                    + " VALUES ('one','fingerprint','q','a','[]',3,0.95,'ACTIVE',?,?)",
                LocalDateTime.now().plusDays(10),
                LocalDateTime.now().minusDays(8));
    }

    /**
     * 模拟测试数据库的 JSON 数组计数。
     */
    public static int jsonLength(String value) throws Exception {
        return value == null ? 0 : new com.fasterxml.jackson.databind.ObjectMapper().readTree(value).size();
    }

    /**
     * 模拟测试数据库的 JSON 字段提取。
     */
    public static String jsonExtract(String json, String path) throws Exception {
        if (json == null) return null;
        JsonNode value = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(path.substring(2));
        return value == null ? null : value.toString();
    }

    /**
     * 模拟测试数据库的 JSON 字段更新。
     */
    public static String jsonSet(String json, String path, String value) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode parsed = mapper.readTree(json);
        // H2 将 VARCHAR 与 JSON_OBJECT 合并时会包装成 JSON 字符串，模拟 MySQL 原生 JSON 对象。
        if (parsed.isTextual()) {
            parsed = mapper.readTree(parsed.asText());
        }
        com.fasterxml.jackson.databind.node.ObjectNode metadata =
                (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
        metadata.put(path.substring(2), value);
        return metadata.toString();
    }

    @Test
    void cacheHitIsMarkedWithEntryAssociation() {
        answer("owner", "cached");
        answer("owner", "normal");
        jdbc.update("UPDATE chat_message SET metadata=? WHERE message_id='cached'", "{\"existing\":\"kept\"}");
        assertEquals(0, jdbc.queryForObject("SELECT cache_hit FROM chat_message WHERE message_id='cached'", Integer.class));
        tx.executeWithoutResult(status -> repository.markCacheAnswer("cached", "one"));
        assertEquals(1, jdbc.queryForObject("SELECT cache_hit FROM chat_message WHERE message_id='cached'", Integer.class));
        assertEquals("one", jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.qaCacheEntryId')) FROM chat_message WHERE message_id='cached'", String.class));
        assertEquals("kept", jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.existing')) FROM chat_message WHERE message_id='cached'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT cache_hit FROM chat_message WHERE message_id='normal'", Integer.class));
    }

    /**
     * 读取测试 JSON 字符串的文本值。
     */
    public static String jsonUnquote(String json) throws Exception {
        return json == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).asText();
    }

    @Test
    void realDocumentVersionDeletionPermissionAndStatusInvalidateDependencies() {
        jdbc.update("INSERT INTO knowledge_document VALUES(10,7,0,'VISITOR')");
        jdbc.update("INSERT INTO knowledge_document_version VALUES(7,10,0,'VECTOR_STORED')");
        repository.saveDependencies("one", Set.of(new QaCacheEntryService.Dependency(10, 7, null)));
        assertTrue(repository.dependenciesCurrent("one"));
        jdbc.update("UPDATE knowledge_document SET current_version_id=8");
        assertFalse(repository.dependenciesCurrent("one"));
        jdbc.update(
                "UPDATE knowledge_document SET"
                        + " current_version_id=7,accessible_by='CUSTOMER_SERVICE'");
        assertFalse(repository.dependenciesCurrent("one"));
        jdbc.update("UPDATE knowledge_document SET accessible_by='VISITOR'");
        jdbc.update("UPDATE knowledge_document_version SET status='CHUNKED'");
        assertFalse(repository.dependenciesCurrent("one"));
        jdbc.update("DELETE FROM knowledge_document_version");
        assertFalse(repository.dependenciesCurrent("one"));
    }

    @Test
    void dependencySnapshotCannotBeReplacedByCurrentVersion() {
        repository.saveDependencies("one", Set.of(new QaCacheEntryService.Dependency(10, 7, null)));
        repository.saveDependencies("one", Set.of(new QaCacheEntryService.Dependency(10, 8, null)));
        assertEquals("[{\"documentId\":10,\"versionId\":7,\"chunkId\":null}]", repository.getById("one").getDependencies());
        jdbc.update("INSERT INTO knowledge_document VALUES(10,8,0,'VISITOR')");
        jdbc.update("INSERT INTO knowledge_document_version VALUES(8,10,0,'VECTOR_STORED')");
        assertFalse(repository.dependenciesCurrent("one"));
    }

    @Test
    void everyDependencyMustRemainPublicAndPresent() {
        repository.saveDependencies("one", Set.of(
                new QaCacheEntryService.Dependency(10, 7, null), new QaCacheEntryService.Dependency(20, 8, null)));
        jdbc.update("INSERT INTO knowledge_document VALUES(10,7,0,'VISITOR'),(20,8,0,'VISITOR')");
        jdbc.update("INSERT INTO knowledge_document_version VALUES(7,10,0,'VECTOR_STORED'),(8,20,0,'VECTOR_STORED')");
        assertTrue(repository.dependenciesCurrent("one"));
        jdbc.update("UPDATE knowledge_document SET deleted=1 WHERE doc_id=20");
        assertFalse(repository.dependenciesCurrent("one"));
        jdbc.update("UPDATE knowledge_document SET deleted=0 WHERE doc_id=20");
        jdbc.update("UPDATE knowledge_document_version SET deleted=1 WHERE version_id=8");
        assertFalse(repository.dependenciesCurrent("one"));
    }

    @Test
    void missingOrMalformedDependenciesCannotPassValidation() {
        assertFalse(repository.dependenciesCurrent("missing"));
        assertFalse(repository.dependenciesCurrent("one"));
        for (String snapshot : List.of("[]", "null", "bad json", "[null]", "[{\"documentId\":10}]")) {
            jdbc.update("UPDATE qa_cache_entry SET dependencies=? WHERE id='one'", snapshot);
            assertFalse(repository.dependenciesCurrent("one"), snapshot);
        }
        assertThrows(IllegalArgumentException.class, () -> repository.saveDependencies("one", Set.of()));
    }

    void answer(String user, String message) {
        jdbc.update("INSERT INTO chat_conversation VALUES(?,?,0)", "c" + message, user);
        jdbc.update(
                "INSERT INTO chat_message(message_id,conversation_id,type,content,deleted)"
                        + " VALUES(?,?,'ASSISTANT','cached answer',0)",
                message,
                "c" + message);
        jdbc.update(
                "UPDATE chat_message SET metadata=? WHERE message_id=?",
                "{\"qaCacheEntryId\":\"one\"}",
                message);
    }

    @Test
    void feedbackOnlySavesMessageUntilScheduledEvaluation() {
        answer("u1", "a1");
        answer("u1", "a2");
        answer("u2", "a3");
        answer("u3", "a4");
        tx.executeWithoutResult(s -> feedback.feedback("a1", "u1", false, null));
        tx.executeWithoutResult(s -> feedback.feedback("a2", "u1", false, null));
        assertEquals(1, repository.feedbackCounts("one").negative());
        tx.executeWithoutResult(s -> feedback.feedback("a3", "u2", false, null));
        assertEquals("ACTIVE", repository.getById("one").getStatus());
        tx.executeWithoutResult(s -> feedback.feedback("a4", "u3", false, null));
        assertEquals("ACTIVE", repository.getById("one").getStatus());
        tx.executeWithoutResult(status -> lifecycle.evaluateUsage("one", LocalDateTime.now()));
        assertEquals("NEGATIVE_FEEDBACK", repository.getById("one").getDisableReason());
        repository.activate("one");
        assertEquals("DISABLED", repository.getById("one").getStatus());
    }

    @Test
    void scheduledEvaluationCountsAllConcurrentVotes() throws Exception {
        answer("u1", "a1");
        answer("u2", "a2");
        answer("u3", "a3");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            List<Future<?>> tasks = new java.util.ArrayList<Future<?>>();
            for (int i = 1; i <= 3; i++) {
                final int user = i;
                tasks.add(
                        pool.submit(
                                () -> {
                                    try {
                                        start.await();
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                    tx.executeWithoutResult(
                                            s ->
                                                    feedback.feedback(
                                                            "a" + user, "u" + user, false, null));
                                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(3, repository.feedbackCounts("one").negative());
        assertEquals("ACTIVE", repository.getById("one").getStatus());
        tx.executeWithoutResult(status -> lifecycle.evaluateUsage("one", LocalDateTime.now()));
        assertEquals("DISABLED", repository.getById("one").getStatus());
    }

    @Test
    void windowCountsExcludeOldAndUnfinishedHitsAndRetireZeroHitEntry() {
        for (int i = 0; i < 100; i++) {
            jdbc.update(
                    "INSERT INTO chat_message(message_id,conversation_id,type,content,deleted,rag_references,created_at)"
                            + " VALUES (?,?,'ASSISTANT','rag answer',0,'[{\"documentId\":\"10\"}]',?)",
                    "a" + i,
                    "c" + i,
                    LocalDateTime.now());
        }
        // 命中标记已写入但答案未落库（未完成命中）不计入命中数。
        jdbc.update(
                "UPDATE chat_message SET cache_hit=1, content='', metadata=? WHERE message_id='a0'",
                "{\"qaCacheEntryId\":\"one\"}");
        // 观察窗口外的完整命中不计入分母和命中数。
        jdbc.update(
                "INSERT INTO chat_message(message_id,conversation_id,type,content,deleted,rag_references,cache_hit,metadata,created_at)"
                        + " VALUES ('old','cold','ASSISTANT','cached answer',0,'[{\"documentId\":\"10\"}]',1,?,?)",
                "{\"qaCacheEntryId\":\"one\"}",
                LocalDateTime.now().minusDays(8));
        assertEquals(
                new QaCacheEntryService.Usage(100, 0),
                repository.usage("one", LocalDateTime.now().minusDays(7)));
        tx.executeWithoutResult(s -> lifecycle.evaluateUsage("one", LocalDateTime.now()));
        assertEquals("LOW_HIT_RATE", repository.getById("one").getDisableReason());
    }

    @Test
    void reviewUpdatesOnlyEligibleStatesAndPreservesFirstDecision() {
        QaCacheEntry entry = new QaCacheEntry();
        entry.setId("review");
        entry.setFingerprint("review-fingerprint");
        entry.setQuestion("question");
        entry.setAnswer("answer");
        entry.setSources("[]");
        entry.setFrequency(3);
        entry.setQuality(0.95);
        entry.setReason("reason");
        repository.insertIfAbsent(entry);
        LocalDateTime expiry = LocalDateTime.now().plusDays(7);
        assertEquals(1, repository.approve("review", "approved question", "approved answer", "staff_1", "checked", expiry));
        assertEquals(0, repository.approve("review", "changed", "changed", "staff_2", "retry", expiry));
        assertEquals(0, repository.changeStatus("review", "REJECTED", "staff_2", "too late"));
        assertEquals("approved answer", repository.getById("review").getAnswer());
        assertEquals("staff_1", repository.getById("review").getReviewer());
        assertEquals(1, repository.changeStatus("review", "DISABLED", "staff_1", "offline"));
        assertEquals("MANUAL", repository.getById("review").getDisableReason());
        assertEquals(0, repository.changeStatus("review", "DISABLED", "staff_2", "retry"));
        assertEquals("staff_1", repository.getById("review").getReviewer());
    }

    @Test
    void exitPreservesHumanReviewerAndIsIdempotent() {
        jdbc.update("UPDATE qa_cache_entry SET reviewer='staff_123',review_note='checked'");
        assertEquals(1, repository.autoDisable("one", "LOW_HIT_RATE", "0/100"));
        assertEquals(0, repository.autoDisable("one", "NEGATIVE_FEEDBACK", "later"));
        assertEquals("staff_123", repository.getById("one").getReviewer());
        assertEquals("LOW_HIT_RATE", repository.getById("one").getDisableReason());
        assertEquals(java.util.List.of("one"), repository.vectorsToDelete());
        repository.vectorDeleted("one");
        assertTrue(repository.vectorsToDelete().isEmpty());
    }

    @Test
    void allAnswersCanBeRatedWithoutCacheDependenciesAndOwnershipIsEnforced() throws Exception {
        // 评价服务不依赖缓存配置或缓存服务
        ChatMessageServiceImpl feedback =
                new ChatMessageServiceImpl(feedbackMapper, conversationService);
        jdbc.update("INSERT INTO chat_conversation VALUES ('normal','owner',0)");
        jdbc.update(
                "INSERT INTO chat_message(message_id,conversation_id,type,content,deleted) VALUES"
                        + " ('answer','normal','ASSISTANT','ordinary answer',0)");
        tx.executeWithoutResult(s -> feedback.feedback("answer", "owner", false, "不准确"));
        tx.executeWithoutResult(s -> feedback.feedback("answer", "owner", true, "重试不覆盖"));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE helpful IS NOT NULL", Integer.class));
        assertEquals(
                0, jdbc.queryForObject("SELECT helpful FROM chat_message WHERE helpful IS NOT NULL", Integer.class));
        assertEquals("不准确", jdbc.queryForObject("SELECT feedback_comment FROM chat_message WHERE message_id='answer'", String.class));
        assertNotNull(jdbc.queryForObject("SELECT feedback_at FROM chat_message WHERE message_id='answer'", LocalDateTime.class));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> feedback.feedback("answer", "other", true, null));
        jdbc.update(
                "INSERT INTO chat_message(message_id,conversation_id,type,content,deleted) VALUES"
                        + " ('empty','normal','ASSISTANT','',0)");
        jdbc.update(
                "INSERT INTO chat_message(message_id,conversation_id,type,content,deleted) VALUES"
                        + " ('question','normal','USER','question',0)");
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> feedback.feedback("empty", "owner", true, null));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> feedback.feedback("question", "owner", true, null));
        assertEquals(0, repository.feedbackCounts("one").total());
    }

    @Test
    void genericFeedbackStillContributesToCacheExit() throws Exception {
        ChatMessageServiceImpl feedback =
                new ChatMessageServiceImpl(feedbackMapper, conversationService);
        for (int i = 1; i <= 3; i++) {
            String user = "u" + i, message = "a" + i;
            answer(user, message);
            tx.executeWithoutResult(s -> feedback.feedback(message, user, false, null));
        }
        assertEquals(
                3,
                jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE helpful IS NOT NULL", Integer.class));
        assertEquals("ACTIVE", repository.getById("one").getStatus());
        tx.executeWithoutResult(status -> lifecycle.evaluateUsage("one", LocalDateTime.now()));
        assertEquals("NEGATIVE_FEEDBACK", repository.getById("one").getDisableReason());
    }

    @Test
    void crossConversationVotesKeepMessagesButCountOnlyFirstUserVote() {
        answer("same-user", "first");
        answer("same-user", "second");
        tx.executeWithoutResult(s -> feedback.feedback("first", "same-user", true, null));
        tx.executeWithoutResult(s -> feedback.feedback("second", "same-user", false, null));
        assertEquals(
                2,
                jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE helpful IS NOT NULL", Integer.class));
        assertEquals(
                new QaCacheEntryService.FeedbackCounts(1, 0), repository.feedbackCounts("one"));
        // 时间戳相同时仍按 ID 确定首次，不使用最新消息的差评覆盖。
        jdbc.update(
                "UPDATE chat_message SET feedback_at=? WHERE helpful IS NOT NULL",
                LocalDateTime.of(2026, 1, 1, 0, 0));
        assertEquals(
                new QaCacheEntryService.FeedbackCounts(1, 0), repository.feedbackCounts("one"));
    }

    @Test
    void feedbackPreservesCacheAssociation() {
        answer("owner", "cached");
        ChatMessageServiceImpl service = new ChatMessageServiceImpl(feedbackMapper, conversationService);
        tx.executeWithoutResult(s -> service.feedback("cached", "owner", false, null));
        assertEquals(
                "one",
                jdbc.queryForObject(
                        "SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.qaCacheEntryId')) FROM chat_message WHERE helpful IS NOT NULL", String.class));
        assertEquals(
                new QaCacheEntryService.FeedbackCounts(1, 1), repository.feedbackCounts("one"));
    }
}
