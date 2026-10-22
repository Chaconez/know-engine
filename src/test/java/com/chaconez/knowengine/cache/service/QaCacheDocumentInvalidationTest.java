package com.chaconez.knowengine.cache.service;

import com.chaconez.knowengine.cache.config.QaCacheProperties;
import com.chaconez.knowengine.cache.util.QaCacheJsonUtil;
import com.chaconez.knowengine.document.constant.DocumentStatus;
import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.chaconez.knowengine.document.entity.KnowledgeDocumentVersion;
import com.chaconez.knowengine.document.mapper.KnowledgeDocumentVersionMapper;
import com.chaconez.knowengine.document.mapper.KnowledgeSegmentMapper;
import com.chaconez.knowengine.document.service.DocumentCleanupService;
import com.chaconez.knowengine.document.service.KnowledgeDocumentVersionService;
import com.chaconez.knowengine.document.service.VectorStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 真实 Spring 同步事件和 MyBatis-Plus/H2 事务，外部向量服务为模拟对象。
 */
public class QaCacheDocumentInvalidationTest {

    private QaCacheMapperTest fixture;
    private AnnotationConfigApplicationContext context;
    private QaCacheProperties properties;

    @BeforeEach
    void setup() throws Exception {
        fixture = new QaCacheMapperTest();
        fixture.setup();
        fixture.jdbc.execute("ALTER TABLE knowledge_document ADD (doc_title VARCHAR(128), status VARCHAR(32),"
                + " description VARCHAR(500),knowledge_base_type VARCHAR(32),extension VARCHAR(1000),"
                + " created_at TIMESTAMP,updated_at TIMESTAMP,lock_version INT)");
        fixture.jdbc.execute("CREATE ALIAS JSON_CONTAINS FOR "
                + "'com.chaconez.knowengine.cache.service.QaCacheDocumentInvalidationTest.jsonContains'");
        fixture.jdbc.update("INSERT INTO knowledge_document(doc_id,current_version_id,deleted,accessible_by)"
                + " VALUES(10,7,0,'VISITOR'),(20,8,0,'VISITOR')");
        fixture.repository.saveDependencies("one", Set.of(new QaCacheEntryService.Dependency(10, 7, null)));
        insertCache("other", 20, 8);
        insertCache("other-version", 10, 9);
        fixture.jdbc.update("UPDATE qa_cache_entry SET reviewer='staff_123',review_note='已核对' WHERE id='one'");

        properties = new QaCacheProperties();
        properties.setEnabled(true);
        QaCacheService cacheService = new QaCacheService(fixture.repository, properties,
                mock(QaCacheVectorService.class), mock(QaCacheCurator.class), fixture.lifecycle);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(QaCacheService.class, () -> cacheService);
        context.refresh();
        ReflectionTestUtils.setField(fixture.documentService, "eventPublisher", context);
        ReflectionTestUtils.setField(fixture.documentService, "vectorStoreService", mock(VectorStoreService.class));
        ReflectionTestUtils.setField(fixture.documentService, "knowledgeSegmentMapper", mock(KnowledgeSegmentMapper.class));
        ReflectionTestUtils.setField(fixture.documentService, "knowledgeDocumentVersionMapper", mock(KnowledgeDocumentVersionMapper.class));
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    private void insertCache(String id, long documentId, long versionId) {
        fixture.jdbc.update("INSERT INTO qa_cache_entry(id,fingerprint,question,answer,sources,frequency,quality,status,dependencies)"
                        + " VALUES(?,?,'q','a','[]',3,0.95,'ACTIVE',?)", id, id,
                QaCacheJsonUtil.write(List.of(new QaCacheEntryService.Dependency(documentId, versionId, null))));
    }

    /**
     * 模拟测试数据库的来源依赖匹配。
     */
    public static boolean jsonContains(String target, String candidate) throws Exception {
        if (target == null) {
            return false;
        }
        JsonNode dependencies = QaCacheJsonUtil.MAPPER.readTree(target);
        JsonNode wanted = QaCacheJsonUtil.MAPPER.readTree(candidate).get(0);
        for (JsonNode dependency : dependencies) {
            if (dependency.path("documentId").equals(wanted.path("documentId"))
                    && (!wanted.has("versionId") || dependency.path("versionId").equals(wanted.path("versionId")))) {
                return true;
            }
        }
        return false;
    }

    private KnowledgeDocument patch(Long id) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(id);
        return document;
    }

    private String status(String id) {
        return fixture.repository.getById(id).getStatus();
    }

    @Test
    void switchingVersionImmediatelyDisablesOnlyRelatedCache() {
        KnowledgeDocument document = patch(10L);
        document.setCurrentVersionId(8L);
        fixture.tx.executeWithoutResult(transaction -> assertTrue(fixture.documentService.updateById(document)));
        assertEquals("DISABLED", status("one"));
        assertEquals("DISABLED", status("other-version"));
        assertEquals("ACTIVE", status("other"));
        assertEquals("KNOWLEDGE_CHANGED", fixture.repository.getById("one").getDisableReason());
        assertEquals("staff_123", fixture.repository.getById("one").getReviewer());
        assertEquals("已核对", fixture.repository.getById("one").getReviewNote());
        assertFalse(fixture.repository.getById("one").isVectorDeleted());
    }

    @Test
    void titleAndSameVersionKeepCacheButRevokingPublicAccessDisablesIt() {
        KnowledgeDocument document = patch(10L);
        document.setDocTitle("新标题");
        document.setCurrentVersionId(7L);
        document.setAccessibleBy("VISITOR");
        fixture.tx.executeWithoutResult(transaction -> assertTrue(fixture.documentService.updateById(document)));
        assertEquals("ACTIVE", status("one"));
        document.setAccessibleBy("CUSTOMER_SERVICE");
        fixture.tx.executeWithoutResult(transaction -> assertTrue(fixture.documentService.updateById(document)));
        assertEquals("DISABLED", status("one"));
        assertEquals("ACTIVE", status("other"));
    }

    @Test
    void rolledBackDocumentChangeAlsoRollsBackCacheInvalidation() {
        KnowledgeDocument document = patch(10L);
        document.setCurrentVersionId(8L);
        assertThrows(IllegalStateException.class, () -> fixture.tx.executeWithoutResult(transaction -> {
            assertTrue(fixture.documentService.updateById(document));
            assertEquals("DISABLED", status("one"));
            throw new IllegalStateException("模拟后续业务失败");
        }));
        assertEquals(7L, fixture.documentService.getById(10L).getCurrentVersionId());
        assertEquals("ACTIVE", status("one"));
    }

    @Test
    void singleDocumentDeletionImmediatelyDisablesCache() {
        fixture.tx.executeWithoutResult(transaction -> assertTrue(fixture.documentService.removeDocumentWithSegments(10L)));
        assertNull(fixture.documentService.getById(10L));
        assertEquals("DISABLED", status("one"));
        assertEquals("ACTIVE", status("other"));
    }

    @Test
    void batchDocumentDeletionImmediatelyDisablesAllRelatedCache() {
        fixture.tx.executeWithoutResult(transaction -> assertTrue(fixture.documentService.removeDocumentsWithSegments(List.of(10L, 20L))));
        assertEquals("DISABLED", status("one"));
        assertEquals("DISABLED", status("other"));
    }

    @Test
    void deactivatingVersionOnlyDisablesItsOwnDependencies() {
        KnowledgeDocumentVersionService versionService = mock(KnowledgeDocumentVersionService.class);
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionId(7L);
        version.setDocId(10L);
        version.setStatus(DocumentStatus.VECTOR_STORED);
        when(versionService.getById(7L)).thenReturn(version);
        when(versionService.updateById(version)).thenReturn(true);
        ReflectionTestUtils.setField(fixture.documentService, "knowledgeDocumentVersionService", versionService);
        ReflectionTestUtils.setField(fixture.documentService, "documentCleanupService", mock(DocumentCleanupService.class));
        fixture.tx.executeWithoutResult(transaction -> fixture.documentService.deactivateVersion(7L));
        assertEquals("DISABLED", status("one"));
        assertEquals("ACTIVE", status("other-version"));
        assertEquals("ACTIVE", status("other"));
    }

    @Test
    void disabledFeatureDoesNotRequireCacheTablesForDocumentChanges() {
        properties.setEnabled(false);
        fixture.jdbc.execute("DROP TABLE qa_cache_entry");
        KnowledgeDocument document = patch(10L);
        document.setCurrentVersionId(8L);
        fixture.tx.executeWithoutResult(transaction -> assertTrue(fixture.documentService.updateById(document)));
        assertEquals(8L, fixture.documentService.getById(10L).getCurrentVersionId());
    }
}
