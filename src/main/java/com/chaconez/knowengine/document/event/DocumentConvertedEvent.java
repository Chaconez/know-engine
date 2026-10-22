package com.chaconez.knowengine.document.event;

import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import org.springframework.context.ApplicationEvent;

/**
 * 文档已转换事件
 * 当文档状态变更为CONVERTED时发送此事件
 *
 * @Deprecated 不再使用事件驱动，靠用户在前端手动触发分段，因为需要用户选择分段方式。
 */
@Deprecated
public class DocumentConvertedEvent extends ApplicationEvent {

    /**
     * 文档ID
     */
    private final Long documentId;

    public DocumentConvertedEvent(Object source, Long documentId, KnowledgeDocument document) {
        super(source);
        this.documentId = documentId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    @Override
    public String toString() {
        return "DocumentConvertedEvent{documentId=" + documentId + '}';
    }
}
