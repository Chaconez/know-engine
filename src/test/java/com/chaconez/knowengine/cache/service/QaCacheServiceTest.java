package com.chaconez.knowengine.cache.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chaconez.knowengine.cache.config.QaCacheProperties;
import com.chaconez.knowengine.cache.entity.QaCacheEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

class QaCacheServiceTest {

    QaCacheEntryService repository;

    QaCacheVectorService vectors;

    QaCacheProperties properties;

    QaCacheService service;

    QaCacheLifecycleService lifecycle;

    @BeforeEach
    void setup() {
        repository = mock(QaCacheEntryService.class);
        vectors = mock(QaCacheVectorService.class);
        properties = new QaCacheProperties();
        properties.setEnabled(true);
        lifecycle = mock(QaCacheLifecycleService.class);
        when(lifecycle.ensureCurrent(anyString())).thenReturn(true);
        service =
                new QaCacheService(
                        repository, properties, vectors, mock(QaCacheCurator.class), lifecycle);
    }

    QaCacheEntry entry(String status) {
        QaCacheEntry e = new QaCacheEntry();
        e.setId("one");
        e.setQuestion("轮胎气压如何检查？");
        e.setAnswer("请依据车辆手册检查冷态胎压。");
        e.setStatus(status);
        e.setExpiresAt(LocalDateTime.now().plusDays(1));
        return e;
    }

    @Test
    void onlyActiveUnexpiredEntriesAreUsable() {
        for (String state : List.of("PENDING", "APPROVED", "REJECTED", "DISABLED")) {
            assertFalse(QaCacheService.isUsable(entry(state), LocalDateTime.now()));
        }
        QaCacheEntry active = entry("ACTIVE");
        assertTrue(QaCacheService.isUsable(active, LocalDateTime.now()));
        active.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertFalse(QaCacheService.isUsable(active, LocalDateTime.now()));
    }

    @Test
    void hitLoadsAnswerFromDatabase() {
        when(vectors.search(anyString(), anyDouble()))
                .thenReturn(List.of(new QaCacheVectorService.Match("one", 0.99)));
        when(repository.getById("one")).thenReturn(entry("ACTIVE"));
        when(repository.resolveReferences("one")).thenReturn(List.of());
        QaCacheService.CachedAnswer answer =
                service.lookup("轮胎气压如何检查？", "c", "m", "a").orElseThrow();
        assertEquals("请依据车辆手册检查冷态胎压。", answer.answer());
        verify(repository).markCacheAnswer("a", "one");
        verify(repository).hit("one");
    }

    @Test
    void staleVectorDoesNotBypassApproval() {
        when(vectors.search(anyString(), anyDouble()))
                .thenReturn(List.of(new QaCacheVectorService.Match("one", 0.99)));
        when(repository.getById("one")).thenReturn(entry("DISABLED"));
        assertTrue(service.lookup("轮胎气压如何检查？", "c", "m", "a").isEmpty());
        verify(repository, never()).hit(anyString());
    }

    @Test
    void disabledAndFollowupQuestionsSkipVectorCalls() {
        properties.setEnabled(false);
        assertTrue(service.lookup("轮胎气压如何检查？", "c", "m", "a").isEmpty());
        properties.setEnabled(true);
        when(repository.hasHistory("c", "m")).thenReturn(true);
        assertTrue(service.lookup("轮胎气压如何检查？", "c", "m", "a").isEmpty());
        verifyNoInteractions(vectors);
    }

    @Test
    void vectorFailureFallsBack() {
        when(vectors.search(anyString(), anyDouble()))
                .thenThrow(new IllegalStateException("offline"));
        assertTrue(service.lookup("轮胎气压如何检查？", "c", "m", "a").isEmpty());
    }

    @Test
    void lowScoreDoesNotLoadAnswer() {
        when(vectors.search(anyString(), anyDouble()))
                .thenReturn(List.of(new QaCacheVectorService.Match("one", 0.5)));
        assertTrue(service.lookup("轮胎气压如何检查？", "c", "m", "a").isEmpty());
        verify(repository, never()).getById(anyString());
    }

    @Test
    void immediatePublicationSkipsUnapprovedExpiredAndInvalidSources() {
        service.publish("missing");
        for (String state : List.of("PENDING", "REJECTED", "DISABLED", "ACTIVE")) {
            when(repository.getById("one")).thenReturn(entry(state));
            service.publish("one");
        }
        QaCacheEntry expired = entry("APPROVED");
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(repository.getById("one")).thenReturn(expired);
        service.publish("one");
        when(repository.getById("one")).thenReturn(entry("APPROVED"));
        when(lifecycle.ensureCurrent("one")).thenReturn(false);
        service.publish("one");
        verifyNoInteractions(vectors);
        verify(repository, never()).activate(anyString());
    }

    @Test
    void candidateRequiresRealDistinctConversationsAndQuality() {
        Map<String, QaCacheEntryService.Source> evidence =
                Map.of(
                        "1",
                        new QaCacheEntryService.Source("1", "c1", "q", "a", "[]"),
                        "2",
                        new QaCacheEntryService.Source("2", "c2", "q", "a", "[]"),
                        "3",
                        new QaCacheEntryService.Source("3", "c3", "q", "a", "[]"));
        assertTrue(
                QaCacheService.validCandidate(
                        candidate(List.of("1", "2", "3"), 0.95, true), evidence, properties));
        assertFalse(
                QaCacheService.validCandidate(
                        candidate(List.of("1", "1", "1"), 0.95, true), evidence, properties));
        assertFalse(
                QaCacheService.validCandidate(
                        candidate(List.of("1", "2", "fake"), 0.95, true), evidence, properties));
        assertFalse(
                QaCacheService.validCandidate(
                        candidate(List.of("1", "2", "3"), 0.5, true), evidence, properties));
        assertFalse(
                QaCacheService.validCandidate(
                        candidate(List.of("1", "2", "3"), 0.95, false), evidence, properties));
    }

    @Test
    void changedKnowledgeIsNotReturnedAndNotMarkedAsCacheHit() {
        when(vectors.search(anyString(), anyDouble()))
                .thenReturn(List.of(new QaCacheVectorService.Match("one", 0.99)));
        when(repository.getById("one")).thenReturn(entry("ACTIVE"));
        when(lifecycle.ensureCurrent("one")).thenReturn(false);
        assertTrue(service.lookup("轮胎气压如何检查？", "c", "m", "a").isEmpty());
        verify(repository, never()).markCacheAnswer(any(), any());
        verify(repository, never()).hit(anyString());
    }

    @Test
    void cacheOutageDoesNotMarkAnyAnswer() {
        when(vectors.search(anyString(), anyDouble()))
                .thenThrow(new IllegalStateException("offline"));
        service.lookup("轮胎气压如何检查？", "c", "m", "a");
        verify(repository, never()).markCacheAnswer(any(), any());
    }

    @Test
    void physicalDeleteFailuresRemainQueuedForRetry() {
        when(repository.lifecycleBatch("")).thenReturn(List.of());
        when(repository.vectorsToDelete()).thenReturn(List.of("one"));
        doThrow(new IllegalStateException("offline")).doNothing().when(vectors).remove("one");
        service.retire();
        verify(repository, never()).vectorDeleted("one");
        service.retire();
        verify(repository).vectorDeleted("one");
    }

    QaCacheCurator.Candidate candidate(List<String> ids, double quality, boolean reusable) {
        return new QaCacheCurator.Candidate(
                "轮胎气压如何检查？", "按车辆手册检查冷态胎压。", ids, quality, "一致", reusable);
    }
}
