package com.chaconez.knowengine.document.event;

import com.chaconez.knowengine.document.constant.DocumentStatus;
import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.chaconez.knowengine.document.entity.KnowledgeDocumentVersion;
import com.chaconez.knowengine.document.service.DocumentProcessService;
import com.chaconez.knowengine.document.service.KnowledgeDocumentService;
import com.chaconez.knowengine.document.service.KnowledgeDocumentVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.Assert;

/**
 * 文档事件监听器
 * 负责处理文档状态变更后的后续流程
 */
@Slf4j
@Component
public class DocumentEventListener {

    @Autowired
    private DocumentProcessService documentProcessService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeDocumentVersionService knowledgeDocumentVersionService;

    /**
     * 监听文档CHUNKED事件，触发向量嵌入流程
     *
     * <p>使用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 确保监听器在
     * 发布方事务提交后才执行。若改为 {@code @EventListener}，split() 的 @Transactional
     * 事务尚未提交时监听器即被触发（@Async线程可见旧数据），导致
     * embedAndStore() 读到的 status 仍为 CONVERTED 而非 CHUNKED，进而提前返回 false。</p>
     *
     * <p>注意：@Async + @TransactionalEventListener 组合时，若发布方事务回滚，
     * 监听器不会被触发（AFTER_COMMIT 语义保证）。</p>
     *
     * @param event 文档已分段事件
     */
    @Async("eventListenerExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentChunked(DocumentChunkedEvent event) {
        Long documentId = event.getDocumentId();
        log.info("收到文档CHUNKED事件（事务已提交），开始执行向量嵌入，documentId: {}, segmentCount: {}",
                documentId, event.getSegmentCount());

        try {
            KnowledgeDocumentVersion documentVersion = knowledgeDocumentVersionService.getById(event.getDocumentVersionId());
            boolean success = documentProcessService.embedAndStore(documentVersion);
            Assert.isTrue(success, "向量嵌入失败: documentId=" + documentId);
            log.info("向量嵌入完成，documentId: {}, success: {}", documentId, success);

            // 同步更新 KnowledgeDocument.status → VECTOR_STORED
            KnowledgeDocument document = knowledgeDocumentService.getById(event.getDocumentId());
            if (document != null && event.getDocumentVersionId().equals(document.getCurrentVersionId())) {
                document.setStatus(DocumentStatus.VECTOR_STORED);
                boolean docResult = knowledgeDocumentService.updateById(document);
                Assert.isTrue(docResult, "文档状态更新失败: docId=" + documentId);
                log.info("文档状态已同步为 VECTOR_STORED, docId={}", documentId);
            }

        } catch (Exception e) {
            log.error("向量嵌入失败，documentId: {}", documentId, e);
        }
    }
}
