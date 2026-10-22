package com.chaconez.knowengine.cache.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.dev33.satoken.stp.StpUtil;
import com.chaconez.knowengine.cache.config.QaCacheProperties;
import com.chaconez.knowengine.cache.controller.QaCacheController;
import com.chaconez.knowengine.cache.entity.QaCacheEntry;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

class QaCacheControllerTest {

    @Test
    void listRequiresEnabledCache() {
        QaCacheEntryService repo = mock(QaCacheEntryService.class);
        QaCacheProperties p = new QaCacheProperties();
        p.setEnabled(false);
        QaCacheController controller =
                new QaCacheController(
                        repo, p, mock(QaCacheLifecycleService.class), mock(QaCacheService.class));
        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsString).thenReturn("staff_123");
            assertEquals(
                    503,
                    assertThrows(ResponseStatusException.class, () -> controller.list("PENDING", 1))
                            .getStatusCode()
                            .value());
            verifyNoInteractions(repo);
        }
    }

    @Test
    void approvalRequiresPublicConfirmationAndConditionalUpdate() {
        QaCacheEntryService repo = mock(QaCacheEntryService.class);
        QaCacheProperties p = new QaCacheProperties();
        p.setEnabled(true);
        QaCacheLifecycleService lifecycle = mock(QaCacheLifecycleService.class);
        when(lifecycle.ensureCurrent(anyString())).thenReturn(true);
        QaCacheService cacheService = mock(QaCacheService.class);
        QaCacheController controller = new QaCacheController(repo, p, lifecycle, cacheService);
        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsString).thenReturn("staff_123");
            assertEquals(
                    400,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            controller.approve(
                                                    "one",
                                                    new QaCacheController.Review(
                                                            "问题", "答案", "已核对", 30, false)))
                            .getStatusCode()
                            .value());
            verifyNoInteractions(repo);
            when(repo.approve(
                            anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(0);
            assertEquals(
                    409,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            controller.approve(
                                                    "one",
                                                    new QaCacheController.Review(
                                                            "问题", "答案", "已核对", 30, true)))
                            .getStatusCode()
                            .value());
            verifyNoInteractions(cacheService);
        }
    }

    @Test
    void approvalPublishesSavedAnswerImmediatelyAndReturnsActualState() {
        QaCacheEntryService repo = mock(QaCacheEntryService.class);
        QaCacheProperties properties = new QaCacheProperties();
        properties.setEnabled(true);
        QaCacheLifecycleService lifecycle = mock(QaCacheLifecycleService.class);
        when(lifecycle.ensureCurrent("one")).thenReturn(true);
        QaCacheService cacheService = mock(QaCacheService.class);
        QaCacheController controller = new QaCacheController(repo, properties, lifecycle, cacheService);
        when(repo.approve(eq("one"), eq("审核后的问题"), eq("审核后的答案"), eq("staff_123"), eq("已核对"), any()))
                .thenReturn(1);
        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsString).thenReturn("staff_123");
            for (String state : List.of("ACTIVE", "APPROVED", "DISABLED")) {
                QaCacheEntry result = new QaCacheEntry();
                result.setStatus(state);
                when(repo.getById("one")).thenReturn(result);
                QaCacheEntry response = controller.approve("one", new QaCacheController.Review(
                        " 审核后的问题 ", " 审核后的答案 ", "已核对", 30, true));
                assertEquals(state, response.getStatus());
                InOrder order = inOrder(repo, cacheService);
                order.verify(repo).approve(eq("one"), eq("审核后的问题"), eq("审核后的答案"),
                        eq("staff_123"), eq("已核对"), any());
                order.verify(cacheService).publish("one");
                order.verify(repo).getById("one");
                clearInvocations(repo, cacheService);
            }
        }
    }

    @Test
    void manualBackdoorEndpointsDelegateToServiceTasks() throws Exception {
        QaCacheProperties properties = new QaCacheProperties();
        properties.setEnabled(false);
        QaCacheService cacheService = mock(QaCacheService.class);
        when(cacheService.curate()).thenReturn(3);
        QaCacheController controller = new QaCacheController(mock(QaCacheEntryService.class), properties,
                mock(QaCacheLifecycleService.class), cacheService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/qa-cache/curate"))
                .andExpect(status().isOk()).andExpect(content().string("3"));
        mvc.perform(get("/api/qa-cache/retire")).andExpect(status().isOk());
        verify(cacheService).curate();
        verify(cacheService).retire();
        verifyNoMoreInteractions(cacheService);
    }
}
