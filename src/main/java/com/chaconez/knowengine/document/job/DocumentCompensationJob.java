package com.chaconez.knowengine.document.job;

import com.chaconez.knowengine.document.constant.DocumentStatus;
import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.chaconez.knowengine.document.entity.KnowledgeDocumentVersion;
import com.chaconez.knowengine.document.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档处理补偿任务
 * 用于处理事件处理失败后的补偿逻辑
 */
@Slf4j
@Component
public class DocumentCompensationJob {

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeDocumentVersionService knowledgeDocumentVersionService;

    @Autowired
    private DocumentProcessService documentProcessService;

    /**
     * 最大重试次数
     */
    private static final int MAX_RETRY_COUNT = 5;

    /**
     * 补偿阈值（分钟）- 超过此时间才进行补偿
     */
    private static final int COMPENSATION_THRESHOLD_MINUTES = 5;

    /**
     * 文档分段补偿任务
     * 扫描 CONVERTED 状态超过阈值的文档，重新触发分段
     * @Deprecated 不再需要，靠用户在前端手动触发分段，因为需要用户选择分段方式。
     */
//    @Deprecated
//    @XxlJob("documentSplitCompensation")
//    public void documentSplitCompensation() {
//        log.info("========== 开始执行文档分段补偿任务 ==========");
//        int successCount = 0;
//        int failCount = 0;
//
//        try {
//            // 这里简化处理，查询所有 CONVERTED 状态的文档
//            LambdaQueryWrapper<KnowledgeDocument> queryWrapper = new LambdaQueryWrapper<>();
//            queryWrapper.eq(KnowledgeDocument::getStatus, DocumentStatus.CONVERTED);
//            queryWrapper.isNotNull(KnowledgeDocument::getConvertedDocUrl);
//
//            List<KnowledgeDocument> documents = knowledgeDocumentService.list(queryWrapper);
//            log.info("发现 {} 个待补偿的 CONVERTED 状态文档", documents.size());
//
//            for (KnowledgeDocument document : documents) {
//                try {
//                    // 检查重试次数（从 extension 字段解析，或使用默认值）
//                    int retryCount = getRetryCount(document);
//                    if (retryCount >= MAX_RETRY_COUNT) {
//                        log.warn("文档 {} 已达最大重试次数 {}，跳过补偿", document.getDocId(), retryCount);
//                        continue;
//                    }
//
//                    log.info("补偿处理文档分段，documentId: {}, retryCount: {}", document.getDocId(), retryCount);
//
//                    // 执行分段
//                    int segmentCount = documentProcessService.split(document);
//
//                    // 更新重试次数
//                    updateRetryCount(document.getDocId(), retryCount + 1);
//
//                    log.info("文档分段补偿成功，documentId: {}, segmentCount: {}", document.getDocId(), segmentCount);
//                    successCount++;
//                } catch (Exception e) {
//                    log.error("文档分段补偿失败，documentId: {}", document.getDocId(), e);
//                    failCount++;
//                }
//            }
//        } catch (Exception e) {
//            log.error("文档分段补偿任务执行异常", e);
//        }
//
//        log.info("========== 文档分段补偿任务完成，成功: {}，失败: {} ==========", successCount, failCount);
//    }

    /**
     * 向量化补偿任务
     * 扫描 CHUNKED 状态但存在未向量化的 segment，重新触发向量化
     */
    @XxlJob("documentEmbeddingCompensation")
    public void documentEmbeddingCompensation() {
        log.info("========== 开始执行向量化补偿任务 ==========");
        int successCount = 0;
        int failCount = 0;

        try {
            // 查询 CHUNKED 状态的文档
            LambdaQueryWrapper<KnowledgeDocumentVersion> docQueryWrapper = new LambdaQueryWrapper<>();
            docQueryWrapper.eq(KnowledgeDocumentVersion::getStatus, DocumentStatus.CHUNKED);

            List<KnowledgeDocumentVersion> documents = knowledgeDocumentVersionService.list(docQueryWrapper);
            log.info("发现 {} 个 CHUNKED 状态的文档", documents.size());

            for (KnowledgeDocumentVersion documentVersion : documents) {
                KnowledgeDocument document = knowledgeDocumentService.getById(documentVersion.getDocId());
                if (!document.getCurrentVersionId().equals(documentVersion.getVersionId())) {
                    log.warn("文档 {} 当前版本 {} 不匹配，跳过补偿", documentVersion.getDocId(), documentVersion.getVersion());
                    continue;
                }

                try {
                    // 执行向量化
                    boolean success = documentProcessService.embedAndStore(documentVersion);

                    if (success) {
                        // 更新重试次数
                        log.info("向量化补偿成功，documentId: {} , version: {}", documentVersion.getDocId(), documentVersion.getVersion());
                        successCount++;
                    } else {
                        log.warn("向量化补偿失败，documentId: {} , version: {}", documentVersion.getDocId(), documentVersion.getVersion());
                        failCount++;
                    }
                } catch (Exception e) {
                    log.error("向量化补偿失败，documentId: {} , version: {}", documentVersion.getDocId(), documentVersion.getVersion(), e);
                    failCount++;
                }
            }
        } catch (Exception e) {
            log.error("向量化补偿任务执行异常", e);
        }

        log.info("========== 向量化补偿任务完成，成功: {}，失败: {} ==========", successCount, failCount);
    }


    @Autowired
    private DocumentCleanupService documentCleanupService;

    /**
     * 扫描所有状态为 VECTOR_STORED 的文档，检查是否存在旧版本残留分段，
     */
    @XxlJob("retryFailedCleanups")
    public void retryFailedCleanups() {
        try {
            List<KnowledgeDocument> docsToCleanup = knowledgeDocumentService.scanDocumentsNeedingCleanup();
            if (docsToCleanup.isEmpty()) {
                return;
            }

            log.info("定时任务发现 {} 个文档需要清理旧版本数据", docsToCleanup.size());

            for (KnowledgeDocument docInfo : docsToCleanup) {
                documentCleanupService.cleanupOldVersionData(docInfo.getDocId(), docInfo.getCurrentVersionId());
            }
        } catch (Exception e) {
            log.error("定时清理任务执行异常: {}", e.getMessage(), e);
        }
    }
}
