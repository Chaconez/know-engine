package com.chaconez.knowengine.document.controller;

import com.chaconez.knowengine.document.entity.KnowledgeSegment;
import com.chaconez.knowengine.document.service.KnowledgeSegmentService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识片段表 Controller
 */
@RestController
@RequestMapping("/api/segment")
public class KnowledgeSegmentController {

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    /**
     * 分页查询
     */
    @GetMapping("/page")
    public Page<KnowledgeSegment> page(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        return knowledgeSegmentService.page(new Page<>(current, size));
    }

    /**
     * 根据ID查询
     */
    @GetMapping("/{id}")
    public KnowledgeSegment getById(@PathVariable Long id) {
        return knowledgeSegmentService.getById(id);
    }

    /**
     * 根据文档ID查询片段列表
     */
    @GetMapping("/list-by-document")
    public List<KnowledgeSegment> listByDocumentId(@RequestParam Long documentId) {
        QueryWrapper<KnowledgeSegment> wrapper = new QueryWrapper<>();
        wrapper.eq("document_id", documentId).orderByAsc("chunk_order");
        return knowledgeSegmentService.list(wrapper);
    }

    /**
     * 根据文档ID分页查询片段
     *
     * @param documentId      文档ID
     * @param documentVersion 版本ID（可选，传入时只查该版本分段，不传时查该文档所有版本分段）
     * @param current         当前页
     * @param size            每页大小
     * @return 分页结果
     */
    @GetMapping("/page-by-document")
    public Page<KnowledgeSegment> pageByDocumentId(
            @RequestParam Long documentId,
            @RequestParam(required = false) Long documentVersion,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        Page<KnowledgeSegment> page = new Page<>(current, size);
        QueryWrapper<KnowledgeSegment> wrapper = new QueryWrapper<>();
        wrapper.eq("document_id", documentId);
        if (documentVersion != null) {
            wrapper.eq("document_version", documentVersion);
        }
        wrapper.orderByAsc("chunk_order");
        return knowledgeSegmentService.page(page, wrapper);
    }

    /**
     * 根据文档ID统计片段数量
     *
     * @param documentId      文档ID
     * @param documentVersion 版本ID（可选，传入时只统计该版本分段数量）
     * @return 片段数量
     */
    @GetMapping("/count-by-document")
    public long countByDocumentId(
            @RequestParam Long documentId,
            @RequestParam(required = false) Long documentVersion) {
        QueryWrapper<KnowledgeSegment> wrapper = new QueryWrapper<>();
        wrapper.eq("document_id", documentId);
        if (documentVersion != null) {
            wrapper.eq("document_version", documentVersion);
        }
        return knowledgeSegmentService.count(wrapper);
    }

    /**
     * 根据状态查询列表
     */
    @GetMapping("/list-by-status")
    public List<KnowledgeSegment> listByStatus(@RequestParam String status) {
        QueryWrapper<KnowledgeSegment> wrapper = new QueryWrapper<>();
        wrapper.eq("status", status);
        return knowledgeSegmentService.list(wrapper);
    }

    /**
     * 新增
     */
    @PostMapping
    public boolean save(@RequestBody KnowledgeSegment segment) {
        return knowledgeSegmentService.save(segment);
    }

    /**
     * 批量新增
     */
    @PostMapping("/batch")
    public boolean saveBatch(@RequestBody List<KnowledgeSegment> segments) {
        return knowledgeSegmentService.saveBatch(segments);
    }

    /**
     * 根据ID更新
     * <p>
     * 禁止直接修改父分段（skipEmbedding=1），父分段内容由子分段修改时自动同步。
     * 如需修改父分段内容，请修改对应的子分段。
     */
    @PutMapping
    public boolean updateById(@RequestBody KnowledgeSegment segment) {
        KnowledgeSegment existing = knowledgeSegmentService.getById(segment.getId());
        if (existing != null && existing.getSkipEmbedding() != null && existing.getSkipEmbedding() == 1) {
            throw new IllegalArgumentException("父分段不支持直接修改，请修改对应的子分段");
        }
        return knowledgeSegmentService.updateById(segment, true);
    }

    /**
     * 根据ID删除
     */
    @DeleteMapping("/{id}")
    public boolean removeById(@PathVariable Long id) {
        return knowledgeSegmentService.removeById(id);
    }

    /**
     * 批量删除
     */
    @DeleteMapping("/batch")
    public boolean removeByIds(@RequestParam List<Long> ids) {
        return knowledgeSegmentService.removeByIds(ids);
    }
}
