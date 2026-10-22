package com.chaconez.knowengine.document.controller;

import com.chaconez.knowengine.document.entity.DocumentSplitParam;
import com.chaconez.knowengine.document.entity.DocumentUploadParam;
import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.chaconez.knowengine.document.entity.KnowledgeDocumentVersion;
import com.chaconez.knowengine.auth.service.AuthService;
import com.chaconez.knowengine.document.service.DocumentProcessService;
import com.chaconez.knowengine.document.service.KnowledgeDocumentService;
import com.chaconez.knowengine.document.service.KnowledgeDocumentVersionService;
import com.chaconez.knowengine.document.service.VectorStoreService;
import com.chaconez.knowengine.document.service.impl.PdfProcessServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 知识文档表 Controller
 */
@RestController
@RequestMapping("/api/document")
public class KnowledgeDocumentController {

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeDocumentVersionService knowledgeDocumentVersionService;

    @Autowired
    private DocumentProcessService documentProcessService;

    @Autowired
    private PdfProcessServiceImpl fileProcessService;

    @Autowired
    private AuthService authService;

    /**
     * 文件上传接口
     *
     * @param file         上传的文件
     * @param accessibleBy 可见范围（可选）
     * @return 保存后的文档记录
     */
    @PostMapping("/upload")
    public KnowledgeDocument uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "version", required = false, defaultValue = "1.0.0") String version,
            @RequestParam(value = "tableName", required = false) String tableName,
            @RequestParam("description") String description,
            @RequestParam("knowledgeBaseType") String knowledgeBaseType,
            @RequestParam(value = "accessibleBy", required = false) String accessibleBy) throws IOException {
        String uploadUser = authService.getCurrentUser().getName();
        return documentProcessService.upload(new DocumentUploadParam(file, title, accessibleBy, description, knowledgeBaseType, tableName, version), uploadUser);
    }

    /**
     * 上传文档新版本
     *
     * @param file        新版本文件
     * @param docId       文档ID（knowledge_document.doc_id）
     * @param version     新版本号（语义化版本，如 "2.0.0"，必须大于现有最新版本号）
     * @param changelog   版本变更说明（可选）
     * @return 更新后的文档记录
     */
    @PostMapping("/upload-version")
    public KnowledgeDocument uploadVersion(
            @RequestParam("file") MultipartFile file,
            @RequestParam("docId") Long docId,
            @RequestParam("version") String version,
            @RequestParam(value = "changelog", required = false) String changelog) throws IOException {
        String uploadUser = authService.getCurrentUser().getName();
        return documentProcessService.uploadNewVersion(docId, version, file, uploadUser, changelog);
    }

    /**
     * 预览 DATA_QUERY 类型文档的动态表数据
     *
     * @param docId   文档ID
     * @param current 当前页
     * @param size    每页大小
     * @return 分页数据
     */
    @GetMapping("/data/{docId}")
    public Page<Map<String, Object>> previewData(
            @PathVariable Long docId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "50") Integer size) {
        return documentProcessService.previewData(docId, current, size);
    }

    /**
     * 查询文档的所有版本（按版本号降序）
     *
     * @param docId 文档ID
     * @return 版本列表
     */
    @GetMapping("/versions/{docId}")
    public List<KnowledgeDocumentVersion> listVersions(@PathVariable Long docId) {
        return knowledgeDocumentVersionService.listByDocId(docId);
    }

    /**
     * 切换文档到指定版本
     * 清理当前版本的分段和向量，恢复目标版本的文件URL和状态，状态置为 CONVERTED 等待重新切片
     *
     * @param docId     文档ID
     * @param versionId 目标版本ID
     * @return 更新后的文档记录
     */
    @PostMapping("/switch-version")
    public KnowledgeDocument switchVersion(@RequestParam("docId") Long docId,
                                           @RequestParam("versionId") Long versionId) {
        return documentProcessService.switchVersion(docId, versionId);
    }

    /**
     * 让指定版本失效：清理该版本 ES 向量，将分段状态降为 STORED，版本状态降为 CHUNKED
     *
     * @param versionId 版本ID（knowledge_document_version.version_id）
     */
    @PostMapping("/deactivate-version")
    public void deactivateVersion(@RequestParam("versionId") Long versionId) {
        knowledgeDocumentService.deactivateVersion(versionId);
    }

    /**
     * 让指定版本生效（重新向量化）：对 STORED 分段重新 embed 写入 ES，版本状态升为 VECTOR_STORED
     *
     * @param versionId 版本ID（knowledge_document_version.version_id）
     */
    @PostMapping("/activate-version")
    public void activateVersion(@RequestParam("versionId") Long versionId) {
        knowledgeDocumentService.activateVersion(versionId);
    }

    /**
     * 对文档进行切分
     * 注意：此方法为手动触发切分接口，正常流程由事件驱动自动执行
     *
     * @param documentId 文档ID
     * @return 切分后的片段数量
     */
    @PostMapping("/split/{documentId}")
    public Integer splitDocument(@PathVariable Long documentId,
                                 @RequestParam("splitType") String splitType,
                                 @RequestParam("chunkSize") Integer chunkSize,
                                 @RequestParam(value = "overlap", required = false) Integer overlap,
                                 @RequestParam(value = "regex", required = false) String regex,
                                 @RequestParam(value = "titleLevel", required = false) Integer titleLevel,
                                 @RequestParam(value = "separator", required = false) String separator
    ) {
        KnowledgeDocument document = knowledgeDocumentService.getById(documentId);
        return documentProcessService.split(document, new DocumentSplitParam(splitType, chunkSize, overlap, titleLevel, separator, regex));
    }

    /**
     * 分页查询（支持多条件筛选）
     *
     * @param current           当前页
     * @param size              每页大小
     * @param docTitle          文档标题（模糊查询，可选）
     * @param status            文档状态，可选值：INIT, UPLOADED, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED, STORED
     * @param knowledgeBaseType 知识库类型，可选值：DOCUMENT_SEARCH, DATA_QUERY
     * @return 分页结果
     */
    @GetMapping("/page")
    public Page<KnowledgeDocument> page(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(value = "docTitle", required = false) String docTitle,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "knowledgeBaseType", required = false) String knowledgeBaseType) {
        Page<KnowledgeDocument> page = new Page<>(current, size);
        QueryWrapper<KnowledgeDocument> wrapper = new QueryWrapper<>();
        if (docTitle != null && !docTitle.isEmpty()) {
            wrapper.like("doc_title", docTitle);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq("status", status);
        }
        if (knowledgeBaseType != null && !knowledgeBaseType.isEmpty()) {
            wrapper.eq("knowledge_base_type", knowledgeBaseType);
        }
        wrapper.orderByDesc("created_at");
        return knowledgeDocumentService.page(page, wrapper);
    }

    /**
     * 条件查询列表（不分页）
     *
     * @param docTitle          文档标题（模糊查询，可选）
     * @param status            文档状态（可选）
     * @param knowledgeBaseType 知识库类型（可选）
     * @return 文档列表
     */
    @GetMapping("/list")
    public List<KnowledgeDocument> list(
            @RequestParam(value = "docTitle", required = false) String docTitle,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "knowledgeBaseType", required = false) String knowledgeBaseType) {
        QueryWrapper<KnowledgeDocument> wrapper = new QueryWrapper<>();
        if (docTitle != null && !docTitle.isEmpty()) {
            wrapper.like("doc_title", docTitle);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq("status", status);
        }
        if (knowledgeBaseType != null && !knowledgeBaseType.isEmpty()) {
            wrapper.eq("knowledge_base_type", knowledgeBaseType);
        }
        wrapper.orderByDesc("created_at");
        return knowledgeDocumentService.list(wrapper);
    }

    /**
     * 根据ID查询文档详情
     */
    @GetMapping("/{id:\\d+}")
    public KnowledgeDocument getById(@PathVariable Long id) {
        return knowledgeDocumentService.getById(id);
    }

    /**
     * 新增文档记录
     *
     * @param document 文档实体
     * @return 是否新增成功
     */
    @PostMapping
    public boolean save(@RequestBody KnowledgeDocument document) {
        return knowledgeDocumentService.save(document);
    }

    /**
     * 根据ID更新文档（需携带 lockVersion 乐观锁版本号）
     *
     * @param document 文档实体
     * @return 是否更新成功
     */
    @PutMapping
    public boolean updateById(@RequestBody KnowledgeDocument document) {
        return knowledgeDocumentService.updateById(document);
    }

    /**
     * 根据ID删除文档（逻辑删除，并级联删除该文档下的所有分段及向量）
     *
     * @param id 文档ID
     * @return 是否删除成功
     */
    @DeleteMapping("/{id}")
    public boolean removeById(@PathVariable Long id) {
        return knowledgeDocumentService.removeDocumentWithSegments(id);
    }

    /**
     * 批量删除文档（逻辑删除，并级联删除分段及向量）
     *
     * @param ids 文档ID列表
     * @return 是否删除成功
     */
    @DeleteMapping("/batch")
    public boolean removeByIds(@RequestParam List<Long> ids) {
        return knowledgeDocumentService.removeDocumentsWithSegments(ids);
    }

    /**
     * 根据状态查询列表
     */
    @GetMapping("/list-by-status")
    public List<KnowledgeDocument> listByStatus(@RequestParam String status) {
        QueryWrapper<KnowledgeDocument> wrapper = new QueryWrapper<>();
        wrapper.eq("status", status);
        return knowledgeDocumentService.list(wrapper);
    }

    /**
     * 获取图片描述
     * 用于测试
     *
     * @param url 图片URL
     * @return 图片描述
     */
    @GetMapping("/image-desc")
    public String getImageDesc(String url) {
        return fileProcessService.generateImageDescription(url);
    }

    @Autowired
    private VectorStoreService vectorStoreService;

    /**
     * 根据查询问题返回相关文档
     * <p>
     * 主要用于测试
     *
     * @param query
     * @return
     */
    @GetMapping("/askDocument")
    public String askDocument(String query) {
        String result = vectorStoreService.search(query, 0.7);
        return result != null ? result : "No relevant documents found.";
    }

    /**
     * 处理文档上传中的参数异常（如内容重复）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
