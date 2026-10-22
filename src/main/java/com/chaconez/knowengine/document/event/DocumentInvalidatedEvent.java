package com.chaconez.knowengine.document.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * 文档版本或可见性失效事件。
 */
@Getter
public class DocumentInvalidatedEvent extends ApplicationEvent {

    /** 受影响的文档 ID。 */
    private final List<Long> documentIds;

    /** 仅失效指定版本时填写；null 表示该文档的全部缓存依赖。 */
    private final Long versionId;

    /**
     * 创建文档失效事件。
     */
    public DocumentInvalidatedEvent(Object source, List<Long> documentIds, Long versionId) {
        super(source);
        this.documentIds = List.copyOf(documentIds);
        this.versionId = versionId;
    }
}
