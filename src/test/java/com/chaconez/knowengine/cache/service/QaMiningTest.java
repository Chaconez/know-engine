package com.chaconez.knowengine.cache.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chaconez.knowengine.cache.config.QaCacheProperties;
import com.chaconez.knowengine.cache.mapper.QaCacheEntryMapper;
import com.chaconez.knowengine.cache.util.QaCacheJsonUtil;
import com.chaconez.knowengine.document.mapper.KnowledgeSegmentMapper;
import com.chaconez.knowengine.document.service.impl.KnowledgeSegmentServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;

/**
 * Mapper 实际访问内存数据库；模型为模拟对象，不连接外部服务。
 */
public class QaMiningTest {

    JdbcTemplate jdbc;

    QaCacheService cacheService;

    QaCacheCurator curator;

    QaCacheVectorService vectorService;

    QaCacheProperties properties;

    long nextId;

    static final String QUESTION = "轮胎气压如何检查？";

    static final String ANSWER = "请依据车辆手册在冷态下检查轮胎气压，使用合适的胎压计，并参考车门侧面的胎压标识。";

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(QaCacheEntryMapper.class);
        config.addMapper(KnowledgeSegmentMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setPlugins(
                new com.chaconez.knowengine.config.MybatisPlusConfig()
                        .mybatisPlusInterceptor());
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        properties = new QaCacheProperties();
        properties.setEnabled(true);
        curator = mock(QaCacheCurator.class);
        vectorService = mock(QaCacheVectorService.class);
        KnowledgeSegmentServiceImpl segmentService = new KnowledgeSegmentServiceImpl();
        ReflectionTestUtils.setField(segmentService, "baseMapper", session.getMapper(KnowledgeSegmentMapper.class));
        cacheService =
                new QaCacheService(
                        new com.chaconez.knowengine.cache.service.impl
                                .QaCacheEntryServiceImpl(
                                session.getMapper(QaCacheEntryMapper.class), null, segmentService, null, null),
                        properties,
                        vectorService, curator, mock(QaCacheLifecycleService.class));
        String schema = Files.readString(Path.of("src/main/resources/sql/tables.sql"));
        Matcher definitions =
                java.util.regex.Pattern.compile(
                                "(?is)CREATE TABLE IF NOT EXISTS"
                                        + " (?:qa_cache_entry)\\s*\\(.*?;")
                        .matcher(schema);
        while (definitions.find()) {
            jdbc.execute(definitions.group().replace("sources JSON", "sources MEDIUMTEXT")
                    .replace("dependencies JSON", "dependencies MEDIUMTEXT"));
        }
        jdbc.execute(
                "CREATE TABLE chat_message(id BIGINT PRIMARY KEY,message_id VARCHAR(64)"
                    + " UNIQUE,conversation_id VARCHAR(64),type VARCHAR(32),content"
                    + " VARCHAR(9000),rag_references VARCHAR(1000),created_at TIMESTAMP,deleted INT"
                    + " DEFAULT 0,helpful TINYINT)");
        jdbc.execute(
                "CREATE TABLE knowledge_segment(document_id BIGINT,document_version BIGINT,chunk_id"
                    + " VARCHAR(64),embedding_id VARCHAR(64),deleted INT)");
        jdbc.update("INSERT INTO knowledge_segment VALUES(10,7,'c','e1',0)");
        jdbc.execute(
                "CREATE ALIAS JSON_LENGTH FOR"
                    + " 'com.chaconez.knowengine.cache.service.QaMiningTest.jsonLength'");
        when(curator.group(anyString()))
                .thenAnswer(
                        inv -> {
                            JsonNode json = QaCacheJsonUtil.MAPPER.readTree((String) inv.getArgument(0));
                            List<String> ids = new ArrayList<String>();
                            json.forEach(q -> ids.add(q.path("id").asText()));
                            return new QaCacheCurator.Grouping(
                                    List.of(new QaCacheCurator.Group(ids)));
                        });
        when(curator.curate(anyString()))
                .thenAnswer(
                        inv -> {
                            JsonNode json = QaCacheJsonUtil.MAPPER.readTree((String) inv.getArgument(0));
                            List<String> ids = new ArrayList<String>();
                            json.path("sources").forEach(s -> ids.add(s.path("id").asText()));
                            return new QaCacheCurator.Result(
                                    List.of(
                                            new QaCacheCurator.Candidate(
                                                    QUESTION, ANSWER, ids, .95, "来源一致", true)));
                        });
    }

    /**
     * 模拟测试数据库的 JSON 数组计数。
     */
    public static int jsonLength(String value) throws Exception {
        return value == null ? 0 : QaCacheJsonUtil.MAPPER.readTree(value).size();
    }

    long pair(String conversation, String question, String answer) {
        long q = ++nextId, a = ++nextId;
        jdbc.update(
                "INSERT INTO chat_message(id,message_id,conversation_id,type,content,created_at)"
                        + " VALUES (?,?,?,'USER',?,?)",
                q,
                "q" + q,
                conversation,
                question,
                LocalDateTime.now());
        jdbc.update(
                "INSERT INTO"
                    + " chat_message(id,message_id,conversation_id,type,content,rag_references,created_at)"
                    + " VALUES (?,?,?,'ASSISTANT',?,?,?)",
                a,
                "a" + a,
                conversation,
                answer,
                "[{\"documentId\":\"10\",\"embeddingId\":\"e1\",\"version\":\"7\"}]",
                LocalDateTime.now());
        return a;
    }

    void negative(long answer) {
        jdbc.update("UPDATE chat_message SET helpful=0 WHERE message_id=?", "a"+answer);
    }

    @Test
    void frequencyAccumulatesFromHistoryAcrossRuns() {
        pair("c1", QUESTION, ANSWER);
        assertEquals(0, cacheService.curate());
        pair("c2", QUESTION, ANSWER);
        assertEquals(0, cacheService.curate());
        verifyNoInteractions(curator);
        pair("c3", QUESTION, ANSWER);
        assertEquals(1, cacheService.curate());
        assertEquals(3, jdbc.queryForObject("SELECT frequency FROM qa_cache_entry", Integer.class));
        assertEquals(0, cacheService.curate());
        verify(curator, times(1)).curate(anyString());
    }

    @Test
    void badAnswersDoNotCountAndAreNeverSentToModel() {
        long bad = pair("bad", QUESTION, ANSWER);
        negative(bad);
        pair("c1", QUESTION, ANSWER);
        pair("c2", QUESTION, ANSWER);
        assertEquals(0, cacheService.curate());
        verifyNoInteractions(curator);
        pair("c3", QUESTION, ANSWER);
        assertEquals(1, cacheService.curate());
        verify(curator).curate(argThat(json -> !json.contains("\"id\":\"q1\"")));
        assertEquals(3, jdbc.queryForObject("SELECT frequency FROM qa_cache_entry", Integer.class));
    }

    @Test
    void completedAnswerWithinWeekIsIncludedOnRerun() {
        long pending = pair("pending", QUESTION, null);
        pair("c1", QUESTION, ANSWER);
        pair("c2", QUESTION, ANSWER);
        assertEquals(0, cacheService.curate());
        jdbc.update("UPDATE chat_message SET content=? WHERE id=?", ANSWER, pending);
        assertEquals(1, cacheService.curate());
    }

    @Test
    void modelFailureCanBeRetried() {
        pair("c1", QUESTION, ANSWER);
        pair("c2", QUESTION, ANSWER);
        pair("c3", QUESTION, ANSWER);
        QaCacheCurator.Result defaultResult =
                new QaCacheCurator.Result(
                        List.of(
                                new QaCacheCurator.Candidate(
                                        QUESTION,
                                        ANSWER,
                                        List.of("q1", "q3", "q5"),
                                        .95,
                                        "一致",
                                        true)));
        when(curator.curate(anyString()))
                .thenThrow(new IllegalStateException("model unavailable"))
                .thenReturn(defaultResult);
        assertThrows(IllegalStateException.class, () -> cacheService.curate());
        assertEquals(1, cacheService.curate());
    }

    @Test
    void negativeAddedDuringModelCallPreventsCandidateSave() {
        long bad = pair("c1", QUESTION, ANSWER);
        pair("c2", QUESTION, ANSWER);
        pair("c3", QUESTION, ANSWER);
        when(curator.curate(anyString()))
                .thenAnswer(
                        inv -> {
                            negative(bad);
                            return new QaCacheCurator.Result(
                                    List.of(
                                            new QaCacheCurator.Candidate(
                                                    QUESTION,
                                                    ANSWER,
                                                    List.of("q1", "q3", "q5"),
                                                    .95,
                                                    "一致",
                                                    true)));
                        });
        assertEquals(0, cacheService.curate());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM qa_cache_entry", Integer.class));
    }

    @Test
    void onlyRecentWeekCountsTowardsFrequency() {
        long old = pair("old", QUESTION, ANSWER);
        jdbc.update(
                "UPDATE chat_message SET created_at=? WHERE id=?",
                LocalDateTime.now().minusDays(8),
                old);
        long future = pair("future", QUESTION, ANSWER);
        jdbc.update(
                "UPDATE chat_message SET created_at=? WHERE id=?",
                LocalDateTime.now().plusDays(1),
                future);
        pair("c1", QUESTION, ANSWER);
        pair("c2", QUESTION, ANSWER);
        assertEquals(0, cacheService.curate());
        verifyNoInteractions(curator);
        pair("c3", QUESTION, ANSWER);
        assertEquals(1, cacheService.curate());
        assertEquals(3, jdbc.queryForObject("SELECT frequency FROM qa_cache_entry", Integer.class));
        verify(curator)
                .curate(
                        argThat(
                                json ->
                                        !json.contains("\"id\":\"q1\"")
                                                && !json.contains("\"id\":\"q3\"")));
    }

    @Test
    void emptyResultDoesNotPersistProcessingProgress() {
        pair("c1", QUESTION, ANSWER);
        pair("c2", QUESTION, ANSWER);
        pair("c3", QUESTION, ANSWER);
        when(curator.curate(anyString())).thenReturn(new QaCacheCurator.Result(List.of()));
        assertEquals(0, cacheService.curate());
        assertEquals(0, cacheService.curate());
        verify(curator, times(2)).curate(anyString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM qa_cache_entry", Integer.class));
    }

    @Test
    void lateCommittedMessagesWithinWeekAreIncluded() {
        nextId = 4;
        pair("first-committed", QUESTION, ANSWER);
        assertEquals(0, cacheService.curate());
        nextId = 0;
        pair("late-one", QUESTION, ANSWER);
        assertEquals(0, cacheService.curate());
        pair("late-two", QUESTION, ANSWER);
        assertEquals(1, cacheService.curate());
    }

    @Test
    void paraphrasesAreCountedAsOneSemanticQuestion() {
        pair("c1", "轮胎气压如何检查？", ANSWER);
        pair("c2", "怎么检查胎压？", ANSWER);
        pair("c3", "想测一下轮胎的气压，该怎么操作？", ANSWER);
        assertEquals(1, cacheService.curate());
        assertEquals(3, jdbc.queryForObject("SELECT frequency FROM qa_cache_entry", Integer.class));
        verify(curator).group(argThat(json -> json.contains("怎么检查胎压") && !json.contains(ANSWER)));
    }

    @Test
    void separateSemanticGroupsDoNotCombineTheirFrequency() {
        pair("c1", "2024款汽车的胎压是多少？", ANSWER);
        pair("c2", "2024年款应该打多少胎压？", ANSWER);
        pair("c3", "2025款汽车的胎压是多少？", ANSWER);
        when(curator.group(anyString()))
                .thenReturn(
                        new QaCacheCurator.Grouping(
                                List.of(
                                        new QaCacheCurator.Group(List.of("q1", "q3")),
                                        new QaCacheCurator.Group(List.of("q5")))));
        assertEquals(0, cacheService.curate());
        verify(curator, never()).curate(anyString());
    }

    @Test
    void invalidQuestionAssignmentsNeverReachAnswerModel() {
        pair("c1", QUESTION, ANSWER);
        pair("c2", QUESTION, ANSWER);
        pair("c3", QUESTION, ANSWER);
        for (List<String> ids :
                List.of(
                        List.of("q1", "q3", "invented"),
                        List.of("q1", "q3", "q5", "q5"),
                        List.of("q1", "q3"))) {
            when(curator.group(anyString()))
                    .thenReturn(
                            new QaCacheCurator.Grouping(List.of(new QaCacheCurator.Group(ids))));
            assertThrows(IllegalStateException.class, () -> cacheService.curate());
        }
        when(curator.group(anyString()))
                .thenReturn(
                        new QaCacheCurator.Grouping(
                                List.of(
                                        new QaCacheCurator.Group(List.of("q1", "q3", "q5")),
                                        new QaCacheCurator.Group(List.of("q1")))));
        assertThrows(IllegalStateException.class, () -> cacheService.curate());
        verify(curator, never()).curate(anyString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM qa_cache_entry", Integer.class));
    }

    private void existingCache(String status, LocalDateTime expiry) {
        jdbc.update("INSERT INTO qa_cache_entry(id,fingerprint,question_key,question,answer,sources,frequency,quality,status,expires_at) VALUES ('existing','existing-fingerprint','different-key','怎样测量轮胎气压？',?,'[]',3,0.95,?,?)", ANSWER,status,expiry);
        pair("c1",QUESTION,ANSWER);
        pair("c2",QUESTION,ANSWER);
        pair("c3",QUESTION,ANSWER);
    }

    @Test
    void similarPublishedQuestionPreventsCandidateInsertion() {
        existingCache("ACTIVE",LocalDateTime.now().plusDays(7));
        when(vectorService.search(QUESTION,0.95)).thenReturn(List.of(new QaCacheVectorService.Match("existing",0.95)));
        assertEquals(0,cacheService.curate());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qa_cache_entry WHERE status='PENDING'",Integer.class));
        verify(vectorService).search(QUESTION,0.95);
    }

    @Test
    void belowThresholdStillCreatesCandidate() {
        existingCache("ACTIVE",LocalDateTime.now().plusDays(7));
        when(vectorService.search(QUESTION,0.95)).thenReturn(List.of(new QaCacheVectorService.Match("existing",0.94)));
        assertEquals(1,cacheService.curate());
    }

    @Test
    void inactiveOrExpiredCacheDoesNotPreventNewCandidate() {
        existingCache("DISABLED",LocalDateTime.now().plusDays(7));
        assertEquals(1,cacheService.curate());
        verifyNoInteractions(vectorService);
        jdbc.update("DELETE FROM qa_cache_entry WHERE status='PENDING'");
        jdbc.update("UPDATE qa_cache_entry SET status='ACTIVE',expires_at=? WHERE id='existing'",LocalDateTime.now().minusDays(1));
        assertEquals(1,cacheService.curate());
    }

    @Test
    void vectorFailureDoesNotInsertUncheckedCandidate() {
        existingCache("ACTIVE",LocalDateTime.now().plusDays(7));
        when(vectorService.search(QUESTION,0.95)).thenThrow(new IllegalStateException("search unavailable"));
        assertThrows(IllegalStateException.class,()->cacheService.curate());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qa_cache_entry WHERE status='PENDING'",Integer.class));
    }

    @Test
    void normalizationDoesNotMergeDifferentNumericConditions() {
        assertEquals(
                QaCacheService.questionKey(" 胎压如何检查？ "), QaCacheService.questionKey("胎压如何检查?"));
        assertNotEquals(
                QaCacheService.questionKey("胎压2.5是否正常"), QaCacheService.questionKey("胎压25是否正常"));
    }
}
