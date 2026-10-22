package com.chaconez.knowengine.document.service.impl;

import com.chaconez.knowengine.document.constant.DocumentStatus;
import com.chaconez.knowengine.document.constant.FileType;
import com.chaconez.knowengine.document.constant.KnowledgeBaseType;
import com.chaconez.knowengine.document.constant.SegmentStatus;
import com.chaconez.knowengine.document.entity.*;
import com.chaconez.knowengine.document.event.DocumentChunkedEvent;
import com.chaconez.knowengine.document.event.DocumentConvertedEvent;
import com.chaconez.knowengine.document.mapper.KnowledgeSegmentMapper;
import com.chaconez.knowengine.document.mapper.TableMetaMapper;
import com.chaconez.knowengine.document.service.*;
import com.chaconez.knowengine.document.util.FileTypeUtil;
import com.chaconez.knowengine.document.util.VersionUtil;
import com.chaconez.knowengine.infra.lock.DistributeLock;
import com.chaconez.knowengine.infra.snowflake.SnowflakeIdGenerator;
import com.chaconez.knowengine.rag.constant.MetadataKeyConstant;
import com.chaconez.knowengine.rag.modules.splitter.DocumentSplitterFactory;
import com.chaconez.knowengine.rag.modules.splitter.ExcelSplitter;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.base.Stopwatch;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 文档处理服务实现类
 * 负责文档的业务流程处理：上传、转换、分段、向量化
 */
@Slf4j
@Service
public class DocumentProcessServiceImpl implements DocumentProcessService {

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeDocumentVersionService knowledgeDocumentVersionService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private FileProcessServiceFactory fileProcessServiceFactory;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private KnowledgeSegmentMapper knowledgeSegmentMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private DocumentCleanupService documentCleanupService;

    @Autowired
    private ExcelProcessServiceImpl excelProcessServiceImpl;

    @Autowired
    private TableMetaMapper tableMetaMapper;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Override
    @DistributeLock(scene = "document-upload", keyExpression = "#uploadUser", waitTime = 0)
    public KnowledgeDocument upload(DocumentUploadParam documentUploadParam, String uploadUser) throws IOException {
        // 计算文件内容hash，用于去重
        String contentHash = calculateContentHash(documentUploadParam.file());

        // 检查是否已存在相同内容的版本（跨文档跨版本去重）
        if (knowledgeDocumentVersionService.existsByContentHash(contentHash)) {
            throw new IllegalArgumentException("文档内容已存在，请勿重复上传");
        }

        // 创建文档记录
        KnowledgeDocument document = new KnowledgeDocument().create(documentUploadParam);
        boolean result = knowledgeDocumentService.save(document);
        Assert.isTrue(result, "文件上传失败");


        log.info("start to upload ....");
        String fileName = documentUploadParam.file().getOriginalFilename();
        // 用minio上传
        String fileUrl = null;
        try {
            fileUrl = fileStorageService.uploadFile(documentUploadParam.file(), fileName);
        } catch (Exception e) {
            knowledgeDocumentService.removeDocumentWithSegments(document.getDocId());
            log.info("文件上传失败，文档已删除");
            return null;
        }

        // 创建初始版本记录
        KnowledgeDocumentVersion versionRecord = createVersionRecord(
                document.getDocId(), documentUploadParam.version(), fileUrl, null,
                uploadUser, contentHash, DocumentStatus.UPLOADED, null);
        document.setCurrentVersionId(versionRecord.getVersionId());

        // 处理文档（转换/存储），获取转换后的文档URL
        String convertedDocUrl = processFile(fileName, documentUploadParam.file(), document, fileUrl);

        // 更新版本记录的转换后URL
        versionRecord = knowledgeDocumentVersionService.getById(versionRecord.getVersionId());
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        result = knowledgeDocumentVersionService.updateById(versionRecord);
        Assert.isTrue(result, "版本记录更新失败");

        KnowledgeDocument documentInDb = knowledgeDocumentService.getById(document.getDocId());
        documentInDb.setCurrentVersionId(versionRecord.getVersionId());
        result = knowledgeDocumentService.updateById(documentInDb);
        Assert.isTrue(result, "文档当前版本更新失败");

        return document;
    }

    /**
     * 处理文档（转换/存储）
     */
    private String processFile(String fileName, MultipartFile documentUploadParam, KnowledgeDocument document, String fileUrl) throws IOException {
        String convertedDocUrl;
        FileProcessService fileProcessService = fileProcessServiceFactory.get(FileTypeUtil.getFileType(fileName, documentUploadParam), document.getKnowledgeBaseType());
        if (fileProcessService != null) {
            convertedDocUrl = fileProcessService.processDocument(document, documentUploadParam.getInputStream());
        } else {
            DocumentStatus targetStatus = document.getKnowledgeBaseType() == KnowledgeBaseType.DOCUMENT_SEARCH
                    ? DocumentStatus.CONVERTED : DocumentStatus.STORED;
            knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), targetStatus);
            document.setStatus(targetStatus);
            convertedDocUrl = fileUrl;
        }
        return convertedDocUrl;
    }

    @Override
    @DistributeLock(scene = "document-upload", keyExpression = "#uploadUser", waitTime = 0)
    public KnowledgeDocument uploadNewVersion(Long docId, String version, MultipartFile file, String uploadUser, String changelog) throws IOException {
        // 查询文档
        KnowledgeDocument document = knowledgeDocumentService.getById(docId);
        Assert.notNull(document, "文档不存在");

        // 校验版本号必须大于已有最大版本号
        String latestVersion = knowledgeDocumentVersionService.getLatestVersion(docId);
        if (latestVersion != null && VersionUtil.compareVersions(version, latestVersion) <= 0) {
            throw new IllegalArgumentException("版本号 " + version + " 不大于现有最新版本号 " + latestVersion + "，请使用更大的版本号");
        }

        // 计算文件内容hash，用于去重
        String contentHash = calculateContentHash(file);

        // 检查是否已存在相同内容的版本（跨文档跨版本去重）
        if (knowledgeDocumentVersionService.existsByContentHash(contentHash)) {
            throw new IllegalArgumentException("文档内容已存在，请勿重复上传");
        }

        KnowledgeDocumentVersion versionRecord = null;
        log.info("start to upload version {} for doc {} ....", version, docId);

        // 1. 上传新版本文件到MinIO（不清理旧版本数据，保证处理期间旧版本仍可查询）
        String fileName = file.getOriginalFilename();
        String fileUrl = null;
        try {
            fileUrl = fileStorageService.uploadFile(file, fileName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 2. 先创建新版本记录，使 processDocument 内部可以推进版本状态
        versionRecord = createVersionRecord(
                document.getDocId(), version, fileUrl, null,
                uploadUser, contentHash, DocumentStatus.UPLOADED, changelog);
        // 这一步先不更新数据库，只是为了让后续的操作能从document中取出version，避免npm和流程走不下去
        // document的更新会在最后执行，确保前置流程都完成后实现版本的切换。
        document.setCurrentVersionId(versionRecord.getVersionId());

        // 3. 处理文档（转换/存储），获取转换后的文档URL
        String convertedDocUrl = processFile(fileName, file, document, fileUrl);

        // 4. 更新版本记录的转换后URL
        versionRecord = knowledgeDocumentVersionService.getById(versionRecord.getVersionId());
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        boolean result = knowledgeDocumentVersionService.updateById(versionRecord);
        Assert.isTrue(result, "版本记录更新失败");

        result = knowledgeDocumentService.updateById(document);
        Assert.isTrue(result, "文档当前版本更新失败");

        log.info("文档 {} 新版本 {} 上传完成，旧版本数据保留中，待新版本向量化完成后自动清理", docId, version);
        return document;
    }

    @Override
    @Transactional
    @DistributeLock(scene = "document-split", keyExpression = "#document.docId", waitTime = 0)
    public int split(KnowledgeDocument document, DocumentSplitParam documentSplitParam) {
        // 1. 查询文档
        Assert.notNull(document, "文档不存在");

        // 从版本表获取当前版本的文件URL
        KnowledgeDocumentVersion versionRecord = knowledgeDocumentVersionService.getById(document.getCurrentVersionId());
        Assert.notNull(versionRecord, "文档版本不存在");
        Assert.notNull(versionRecord.getConvertedDocUrl(), "文档未转换完成");

        if (versionRecord.getStatus() == DocumentStatus.CHUNKED) {
            // 返回已切分的分段数量（仅统计当前版本的分段，排除旧版本残留）
            Long chunkedCount = knowledgeSegmentService.count(new QueryWrapper<KnowledgeSegment>()
                    .eq("document_id", document.getDocId())
                    .eq("document_version", document.getCurrentVersionId())
                    .eq("skipEmbedding", 0));
            return chunkedCount.intValue();
        }

        if (versionRecord.getStatus() != DocumentStatus.CONVERTED) {
            throw new RuntimeException("文档状态不为CONVERTED，无法完成切分");
        }

        // 2. 从MinIO下载文件内容（从版本表获取转换后的文档URL）
        String convertedDocUrl = versionRecord.getConvertedDocUrl();
        String objectName = extractObjectNameFromUrl(convertedDocUrl);
        Assert.notNull(objectName, "无法解析文档URL");

        List<KnowledgeSegment> knowledgeSegments = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        try (InputStream inputStream = fileStorageService.downloadFile(objectName)) {
            //EXCEL单独处理，因为他不是Document类型
            if (FileType.EXCEL == FileTypeUtil.getFileType(convertedDocUrl) || FileType.CSV == FileTypeUtil.getFileType(convertedDocUrl)) {
                ExcelSplitter splitter = new ExcelSplitter(documentSplitParam.chunkSize(), false);
                segments = splitter.split(inputStream.readAllBytes());
            } else {
                DocumentSplitter splitter = DocumentSplitterFactory.getInstance(documentSplitParam);
                Document doc = Document.from(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                segments = splitter.split(doc);
            }
        } catch (Exception e) {
            throw new RuntimeException("下载文档失败: " + e.getMessage(), e);
        }

        // 4. 转换为 KnowledgeSegment 并保存
        for (int i = 0; i < segments.size(); i++) {

            TextSegment segment = segments.get(i);
            KnowledgeSegment knowledgeSegment = new KnowledgeSegment();
            knowledgeSegment.setText(segment.text());
            // 设置分段ID，部分自定义的分段器中已经设置过chunk id，用于处理父子分段，其他的没有的这里单独生成一个
            if (segment.metadata().getString(MetadataKeyConstant.CHUNK_ID) != null) {
                knowledgeSegment.setChunkId(segment.metadata().getString(MetadataKeyConstant.CHUNK_ID));
            } else {
                knowledgeSegment.setChunkId(SnowflakeIdGenerator.getInstance().nextIdStr());
            }
            Metadata metadata = segment.metadata();
            knowledgeSegment.setMetadata(enrichMetadata(document, versionRecord, metadata, knowledgeSegment));
            knowledgeSegment.setDocumentId(document.getDocId());
            knowledgeSegment.setDocumentVersion(document.getCurrentVersionId());
            knowledgeSegment.setChunkOrder(i);

            // 检查是否需要跳过嵌入
            Integer skipEmbedding = metadata.getInteger(MetadataKeyConstant.SKIP_EMBEDDING);
            if (skipEmbedding != null && skipEmbedding == 1) {
                knowledgeSegment.setSkipEmbedding(1);
                knowledgeSegment.setStatus(SegmentStatus.STORED);
            } else {
                knowledgeSegment.setSkipEmbedding(0);
                knowledgeSegment.setStatus(SegmentStatus.STORED);
            }

            knowledgeSegments.add(knowledgeSegment);
        }

        // 5. 批量保存片段
        Stopwatch stopwatch = Stopwatch.createStarted();
        boolean saveResult = knowledgeSegmentService.saveBatch(knowledgeSegments);
        Assert.isTrue(saveResult, "保存知识片段失败");
        log.info("保存知识片段耗时: {}", stopwatch.elapsed().toMillis());

        int segmentCount = knowledgeSegments.size();

        // 6. 更新文档状态为 CHUNKED，并保存分段参数
        boolean advanceResult = knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CHUNKED);
        Assert.isTrue(advanceResult, "更新文档版本状态失败");

        // 发送文档已分段事件
        publishChunkedEvent(document, segmentCount);

        return segmentCount;
    }

    /**
     * 填充元数据
     *
     * @param document 文档信息
     * @param metadata 元数据
     * @return
     */
    private static String enrichMetadata(KnowledgeDocument document, KnowledgeDocumentVersion versionRecord, Metadata metadata, KnowledgeSegment knowledgeSegment) {
        metadata.put(MetadataKeyConstant.DOC_ID, document.getDocId());
        metadata.put(MetadataKeyConstant.FILE_NAME, document.getDocTitle());
        metadata.put(MetadataKeyConstant.URL, versionRecord.getDocUrl());
        if (metadata.getString(MetadataKeyConstant.CHUNK_ID) == null) {
            metadata.put(MetadataKeyConstant.CHUNK_ID, knowledgeSegment.getChunkId());
        }
        if (document.getCurrentVersionId() != null) {
            metadata.put(MetadataKeyConstant.VERSION, document.getCurrentVersionId());
        }
        Map<String, Object> metadataMap = metadata.toMap();
        metadataMap.put(MetadataKeyConstant.ACCESSIBLE_BY, document.getAccessibleBy());

        return JSON.toJSONString(metadataMap);
    }

    @Override
    @DistributeLock(scene = "document-embed", keyExpression = "#documentVersion.versionId", waitTime = 0)
    public boolean embedAndStore(KnowledgeDocumentVersion documentVersion) {
        if (documentVersion == null) {
            return false;
        }

        if (documentVersion.getStatus() == DocumentStatus.VECTOR_STORED) {
            log.info("文档版本状态已为VECTOR_STORED，无需重复向量化: {}", documentVersion.getVersionId());
            return true;
        }

        if (documentVersion.getStatus() != DocumentStatus.CHUNKED) {
            log.warn("文档版本状态不是CHUNKED，无法完成向量化: {}", documentVersion.getStatus());
            return false;
        }

        knowledgeDocumentService.activateVersion(documentVersion.getVersionId());

        //double check
        long segmentCount = knowledgeSegmentService.count(new QueryWrapper<KnowledgeSegment>()
                .eq("document_id", documentVersion.getDocId())
                .eq("document_version", documentVersion.getVersionId())
                .eq("status", SegmentStatus.STORED)
                .eq("skip_embedding", 0));

        if (segmentCount == 0) {
            // 针对非当前版本的文档，取消激活
            List<KnowledgeDocumentVersion> documentVersions = knowledgeDocumentVersionService.list(new QueryWrapper<KnowledgeDocumentVersion>()
                    .eq("doc_id", documentVersion.getDocId())
                    .eq("status", DocumentStatus.VECTOR_STORED)
                    .ne("version_id", documentVersion.getVersionId()));

            documentVersions.forEach(version -> knowledgeDocumentService.deactivateVersion(version.getVersionId()));
            return true;
        }

        log.warn("向量存储失败，存在部分分段没有存储成功，未成功的数量： " + segmentCount);
        return false;
    }

    @Override
    @DistributeLock(scene = "document-upload", keyExpression = "#docId", waitTime = 0)
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocument switchVersion(Long docId, Long versionId) {
        // 查询文档
        KnowledgeDocument document = knowledgeDocumentService.getById(docId);
        Assert.notNull(document, "文档不存在");

        // 查询目标版本
        KnowledgeDocumentVersion versionRecord = knowledgeDocumentVersionService.getById(versionId);
        Assert.notNull(versionRecord, "版本不存在");
        Assert.isTrue(versionRecord.getDocId().equals(docId), "版本不属于该文档");

        // 如果已经是当前版本，无需切换
        if (versionId.equals(document.getCurrentVersionId())) {
            return document;
        }

        log.info("切换文档 {} 的版本：从 versionId={} 切换到 versionId={}", docId, document.getCurrentVersionId(), versionId);

        // DATA_QUERY 类型文档无分段/向量，直接切换当前版本即可
        if (document.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY) {
            // DATA_QUERY 类型不支持回退到旧版本，只能保持为最新版本
            String latestVersion = knowledgeDocumentVersionService.getLatestVersion(docId);
            if (latestVersion != null && VersionUtil.compareVersions(versionRecord.getVersion(), latestVersion) < 0) {
                throw new IllegalArgumentException("DATA_QUERY 类型文档不支持切换到旧版本");
            }
            document.setCurrentVersionId(versionId);
            boolean docUpdateResult = knowledgeDocumentService.updateById(document);
            Assert.isTrue(docUpdateResult, "更新文档版本失败");
            return document;
        }

        // 更新原版本文档片段状态为 STORED
        LambdaUpdateWrapper<KnowledgeSegment> updateWrapper = Wrappers.<KnowledgeSegment>lambdaUpdate()
                .set(KnowledgeSegment::getStatus, SegmentStatus.STORED)
                .eq(KnowledgeSegment::getDocumentId, document.getDocId())
                .eq(KnowledgeSegment::getDocumentVersion, document.getCurrentVersionId());
        int segAffected = knowledgeSegmentMapper.update(null, updateWrapper);
        log.info("切换版本：旧版本分段状态降级完成, affected={}", segAffected);

        boolean embedResult = embedAndStore(versionRecord);
        Assert.isTrue(embedResult, "更新文档片段状态失败");

        // 更新文档版本
        document.setCurrentVersionId(versionId);
        boolean docUpdateResult = knowledgeDocumentService.updateById(document);
        Assert.isTrue(docUpdateResult, "更新文档版本失败");

        return document;
    }

    @Override
    public Page<Map<String, Object>> previewData(Long docId, int current, int size) {
        KnowledgeDocument document = knowledgeDocumentService.getById(docId);
        Assert.notNull(document, "文档不存在");
        Assert.isTrue(document.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY, "仅支持数据查询类型文档");

        String logicalTableName = document.getTableName();
        Assert.hasText(logicalTableName, "文档未配置数据表名称");

        String physicalTableName = excelProcessServiceImpl.generatePhysicalTableName(logicalTableName);
        Assert.isTrue(tableMetaMapper.checkTableExists(physicalTableName) > 0, "数据表不存在");

        long total = ((Number) tableMetaMapper.executeQuery(
                "SELECT COUNT(*) AS cnt FROM `" + physicalTableName + "`").get(0).get("cnt")).longValue();
        Page<Map<String, Object>> page = new Page<>(current, size, total);
        if (total > 0) {
            long offset = (long) (current - 1) * size;
            String querySql = "SELECT * FROM `" + physicalTableName + "` ORDER BY id ASC LIMIT " + size + " OFFSET " + offset;
            List<Map<String, Object>> records = tableMetaMapper.executeQuery(querySql);
            page.setRecords(records);
        }
        return page;
    }

    // ==================== 事件发布方法 ====================

    /**
     * 发送文档已转换事件
     *
     * @Deprecated 不再使用事件驱动，靠用户在前端手动触发分段，因为需要用户选择分段方式。
     */
    @Deprecated
    private void publishConvertedEvent(KnowledgeDocument document) {
        log.info("发送文档CONVERTED事件，documentId: {}", document.getDocId());
        DocumentConvertedEvent event = new DocumentConvertedEvent(this, document.getDocId(), document);
        eventPublisher.publishEvent(event);
    }

    /**
     * 发送文档已分段事件
     */
    private void publishChunkedEvent(KnowledgeDocument document, int segmentCount) {
        log.info("发送文档CHUNKED事件，documentId: {}, segmentCount: {}", document.getDocId(), segmentCount);
        DocumentChunkedEvent event = new DocumentChunkedEvent(this, document.getDocId(), document.getCurrentVersionId(), segmentCount);
        eventPublisher.publishEvent(event);
    }

    // ==================== 辅助方法 ====================

    /**
     * 通过后缀名判断是否为 PDF 文件
     */
    private boolean isPdfFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".pdf");
    }

    /**
     * 通过 Apache Tika 检测文件内容类型判断是否为 PDF 文件
     */
    private boolean isPdfContent(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            String mimeType = new Tika().detect(is);
            return "application/pdf".equals(mimeType);
        } catch (IOException e) {
            log.error("文件类型检测失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从MinIO URL中提取对象名称
     */
    private String extractObjectNameFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        // URL格式: http://endpoint/bucketName/objectName
        int lastSlashIndex = url.lastIndexOf(bucketName) + bucketName.length();
        if (lastSlashIndex == -1 || lastSlashIndex == url.length() - 1) {
            return null;
        }
        return url.substring(lastSlashIndex + 1);
    }

    /**
     * 创建版本记录
     *
     * @param docId           文档ID
     * @param version         版本号（语义化版本，如 "1.0.0"）
     * @param docUrl          原始文档URL（MinIO）
     * @param convertedDocUrl 转换后的文档URL
     * @param uploadUser      上传用户
     * @param contentHash     内容哈希
     * @param status          文档状态
     * @param changelog       变更说明
     * @return 保存后的版本记录
     */
    private KnowledgeDocumentVersion createVersionRecord(Long docId, String version, String docUrl,
                                                         String convertedDocUrl, String uploadUser,
                                                         String contentHash, DocumentStatus status, String changelog) {
        KnowledgeDocumentVersion versionRecord = new KnowledgeDocumentVersion();
        versionRecord.setDocId(docId);
        versionRecord.setVersion(version);
        versionRecord.setDocUrl(docUrl);
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        versionRecord.setContentHash(contentHash);
        versionRecord.setStatus(status);
        versionRecord.setUploadUser(uploadUser);
        versionRecord.setChangelog(changelog);
        knowledgeDocumentVersionService.save(versionRecord);
        log.info("创建版本记录成功, docId: {}, version: {}, versionId: {}",
                docId, version, versionRecord.getVersionId());
        return versionRecord;
    }

    /**
     * 计算文件内容的SHA-256哈希值
     *
     * @param file 上传的文件
     * @return SHA-256哈希的十六进制字符串
     */
    private String calculateContentHash(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256算法不可用", e);
        }
    }
}
