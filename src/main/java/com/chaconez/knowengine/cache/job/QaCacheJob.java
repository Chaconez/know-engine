package com.chaconez.knowengine.cache.job;

import com.chaconez.knowengine.cache.service.QaCacheService;

import com.xxl.job.core.handler.annotation.XxlJob;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/**
 * 问答缓存 XXL-Job 任务，调度周期在任务控制台配置。
 */
@Component
@RequiredArgsConstructor
public class QaCacheJob {

    private final QaCacheService qaCacheService;

    /**
     * 执行高频问答整理任务。
     */
    @XxlJob("qaCacheCurate")
    public void curate() {
        qaCacheService.curate();
    }

    /**
     * 执行准出、向量清理。
     */
    @XxlJob("qaCacheRetire")
    public void retire() {
        qaCacheService.retire();
    }
}
