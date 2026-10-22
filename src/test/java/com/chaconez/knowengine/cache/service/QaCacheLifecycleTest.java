package com.chaconez.knowengine.cache.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chaconez.knowengine.cache.entity.QaCacheEntry;
import com.chaconez.knowengine.cache.util.QaCacheJsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

class QaCacheLifecycleTest {

    QaCacheEntryService repository;

    QaCacheLifecycleService lifecycle;

    QaCacheEntry entry;

    @BeforeEach
    void setup() {
        repository = mock(QaCacheEntryService.class);
        lifecycle = new QaCacheLifecycleService(repository);
        entry = new QaCacheEntry();
        entry.setId("one");
        entry.setStatus("ACTIVE");
        entry.setDependencies("[{\"documentId\":10,\"versionId\":7}]");
        entry.setExpiresAt(LocalDateTime.now().plusDays(10));
        entry.setActivatedAt(LocalDateTime.now().minusDays(8));
        when(repository.lock("one")).thenReturn(entry);
        when(repository.dependenciesCurrent("one")).thenReturn(true);
        when(repository.feedbackCounts("one"))
                .thenReturn(new QaCacheEntryService.FeedbackCounts(0, 0));
    }

    @Test
    void currentKnowledgeStaysActiveAndChangedVersionRetires() {
        assertTrue(lifecycle.ensureCurrent("one"));
        verify(repository, never()).autoDisable(any(), any(), any());
        when(repository.dependenciesCurrent("one")).thenReturn(false);
        assertFalse(lifecycle.ensureCurrent("one"));
        verify(repository).autoDisable(eq("one"), eq("KNOWLEDGE_CHANGED"), any());
    }



    @Test
    void untraceableOldSourcesAreDisabled() {
        entry.setDependencies(null);
        entry.setSources("[]");
        assertFalse(lifecycle.ensureCurrent("one"));
        verify(repository).autoDisable(eq("one"), eq("SOURCE_UNVERIFIABLE"), any());
    }

    @Test
    void sourceDatabaseFailureIsNotClassifiedAsKnowledgeChange() {
        when(repository.dependenciesCurrent("one")).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> lifecycle.ensureCurrent("one"));
        verify(repository, never()).autoDisable(any(), any(), any());
    }

    @Test
    void expiryHasExplicitExitReason() {
        entry.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertFalse(lifecycle.ensureCurrent("one"));
        verify(repository).autoDisable(eq("one"), eq("EXPIRED"), any());
    }

    @Test
    void lowHitRequiresMinimumTrafficAndFullObservationWindow() {
        entry.setActivatedAt(LocalDateTime.now().minusDays(1));
        lifecycle.evaluateUsage("one", LocalDateTime.now());
        verify(repository, never()).usage(any(), any());
        entry.setActivatedAt(LocalDateTime.now().minusDays(8));
        when(repository.usage(eq("one"), any())).thenReturn(new QaCacheEntryService.Usage(99, 0));
        lifecycle.evaluateUsage("one", LocalDateTime.now());
        verify(repository, never()).autoDisable(any(), any(), any());
        when(repository.usage(eq("one"), any())).thenReturn(new QaCacheEntryService.Usage(100, 0));
        lifecycle.evaluateUsage("one", LocalDateTime.now());
        verify(repository).autoDisable(eq("one"), eq("LOW_HIT_RATE"), any());
    }

    @Test
    void boundaryRatesAreDefined() {
        assertFalse(
                QaCacheLifecycleService.lowHitThreshold(
                        new QaCacheEntryService.Usage(100, 1)));
        assertFalse(
                QaCacheLifecycleService.negativeThreshold(
                        new QaCacheEntryService.FeedbackCounts(2, 2)));
        assertFalse(
                QaCacheLifecycleService.negativeThreshold(
                        new QaCacheEntryService.FeedbackCounts(7, 3)));
        assertTrue(
                QaCacheLifecycleService.negativeThreshold(
                        new QaCacheEntryService.FeedbackCounts(6, 3)));
    }

    @Test
    void scheduledEvaluationRetiresAtNegativeThreshold() {
        when(repository.feedbackCounts("one"))
                .thenReturn(new QaCacheEntryService.FeedbackCounts(3, 3));
        lifecycle.evaluateUsage("one", LocalDateTime.now());
        verify(repository).autoDisable(eq("one"), eq("NEGATIVE_FEEDBACK"), any());
    }
}
