package com.chaconez.knowengine.cache.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答缓存条目，保存候选、人工审核和发布状态。
 */
@Data
@TableName("qa_cache_entry")
public class QaCacheEntry {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 来源证据指纹，用于防止重复入库。
     */
    private String fingerprint;

    /**
     * 归一化问题键，用于精确去重。
     */
    private String questionKey;

    /**
     * 最后修改时间。
     */
    private LocalDateTime updatedAt;

    /**
     * 人工审核时间。
     */
    private LocalDateTime reviewedAt;

    /**
     * 问题正文。
     */
    private String question;

    /**
     * 答案正文。
     */
    private String answer;

    /**
     * 原始问答及知识引用，JSON 格式。
     */
    private String sources;

    /**
     * 统计窗口内的独立会话数。
     */
    private int frequency;

    /**
     * 模型给出的质量置信度。
     */
    private double quality;

    /**
     * 模型整理依据及待人工核实事项。
     */
    private String reason;

    /**
     * 条目状态：PENDING、APPROVED、ACTIVE、REJECTED、DISABLED。
     */
    private String status;

    /**
     * 审核员工账号。
     */
    private String reviewer;

    /**
     * 人工审核意见。
     */
    private String reviewNote;

    /**
     * 人工审核设定的有效期截止时间。
     */
    private LocalDateTime expiresAt;

    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 最近一次向量发布错误。
     */
    private String indexError;

    /**
     * 累计缓存命中次数。
     */
    private long hits;

    /**
     * 首次生效时间。
     */
    private LocalDateTime activatedAt;

    /**
     * 下线时间。
     */
    private LocalDateTime disabledAt;

    /**
     * 下线原因编码。
     */
    private String disableReason;

    /**
     * 下线原因说明。
     */
    private String disableDetail;

    /**
     * 实际引用的文档及版本 ID 快照，JSON 数组；null 表示尚未解析。
     */
    private String dependencies;

    /**
     * 下线向量是否已清理。
     */
    private boolean vectorDeleted;
}
