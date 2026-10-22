package com.chaconez.knowengine.cache.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.chaconez.knowengine.cache.config.QaCacheProperties;
import com.chaconez.knowengine.cache.entity.QaCacheEntry;
import com.chaconez.knowengine.cache.service.QaCacheEntryService;
import com.chaconez.knowengine.cache.service.QaCacheLifecycleService;
import com.chaconez.knowengine.cache.service.QaCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 问答缓存审核及手动任务接口，仅允许员工访问
 */
@RestController
@RequestMapping("/api/qa-cache")
@RequiredArgsConstructor
public class QaCacheController {

    private final QaCacheEntryService entryService;

    private final QaCacheProperties properties;

    private final QaCacheLifecycleService lifecycle;

    private final QaCacheService cacheService;

    public record Review(
            String question,
            String answer,
            String note,
            Integer validDays,
            boolean publicConfirmed) {}

    /**
     * 校验问答缓存已启用，并返回当前登录员工账号（员工身份由 Sa-Token 拦截器统一校验）。
     */
    private String reviewer() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "问答缓存尚未启用");
        }
        return StpUtil.getLoginIdAsString();
    }

    /**
     * 按状态分页查询缓存条目。
     */
    @GetMapping
    public List<QaCacheEntry> list(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "1") int page) {
        reviewer();
        if (!Set.of("PENDING", "APPROVED", "ACTIVE", "REJECTED", "DISABLED").contains(status)
                || page < 1
                || page > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态或页码无效");
        }
        return entryService.list(status, (page - 1) * 20, 20);
    }

    /**
     * 整理最近七天的高频问答，返回新增待审核条目数。
     * 后门接口，实际会通过定时任务调用，这里仅用于测试
     */
    @GetMapping("/curate")
    public int curate() {
        return cacheService.curate();
    }

    /**
     * 执行准出、向量清理。
     * 后门接口，实际会通过定时任务调用，这里仅用于测试。
     */
    @GetMapping("/retire")
    public void retire() {
        cacheService.retire();
    }

    /**
     * 审核通过并发布缓存条目。
     */
    @PostMapping("/{id}/approve")
    public QaCacheEntry approve(@PathVariable String id, @RequestBody Review review) {
        String reviewer = reviewer();
        if (review.question() == null
                || review.question().isBlank()
                || review.question().length() > 500
                || review.answer() == null
                || review.answer().isBlank()
                || review.answer().length() > 16000
                || review.validDays() == null
                || review.validDays() < 1
                || review.validDays() > 90
                || !review.publicConfirmed()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "请填写完整问答、1–90 天有效期，并确认答案可公开复用");
        }
        checkNote(review.note());
        if (!lifecycle.ensureCurrent(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "来源知识已失效或条目已被处理，请刷新");
        }
        changed(
                entryService.approve(
                        id,
                        review.question().trim(),
                        review.answer().trim(),
                        reviewer,
                        review.note(),
                        LocalDateTime.now().plusDays(review.validDays())));
        cacheService.publish(id);
        return entryService.getById(id);
    }

    /**
     * 拒绝待审核的缓存条目。
     */
    @PostMapping("/{id}/reject")
    public void reject(@PathVariable String id, @RequestBody Review review) {
        String reviewer = reviewer();
        checkNote(review.note());
        changed(entryService.changeStatus(id, "REJECTED", reviewer, review.note()));
    }

    /**
     * 人工下线缓存条目。
     */
    @PostMapping("/{id}/disable")
    public void disable(@PathVariable String id, @RequestBody Review review) {
        String reviewer = reviewer();
        checkNote(review.note());
        changed(entryService.changeStatus(id, "DISABLED", reviewer, review.note()));
    }

    /**
     * 手动发布待生效的已审核条目。
     */
    @PostMapping("/{id}/publish")
    public QaCacheEntry publish(@PathVariable String id) {
        reviewer();
        cacheService.publish(id);
        return entryService.getById(id);
    }

    /**
     * 重新激活已下线的缓存条目。
     */
    @PostMapping("/{id}/reactivate")
    public QaCacheEntry reactivate(@PathVariable String id, @RequestBody Review review) {
        String reviewer = reviewer();
        checkNote(review.note());
        if (review.validDays() == null || review.validDays() < 1 || review.validDays() > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "有效天数必须在 1–90 之间");
        }
        changed(entryService.reactivate(id, reviewer, review.note(),
                LocalDateTime.now().plusDays(review.validDays())));
        cacheService.publish(id);
        return entryService.getById(id);
    }

    private void checkNote(String note) {
        if (note != null && note.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核意见最多 2000 字");
        }
    }

    private void changed(int rows) {
        if (rows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "条目不存在或已被处理，请刷新");
        }
    }
}
