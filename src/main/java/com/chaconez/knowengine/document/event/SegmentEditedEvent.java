package com.chaconez.knowengine.document.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 分段内容编辑事件，用于通知缓存等模块执行精确失效。
 */
@Getter
public class SegmentEditedEvent extends ApplicationEvent {

    /** 被编辑分段的 chunkId。 */
    private final String chunkId;

    public SegmentEditedEvent(Object source, String chunkId) {
        super(source);
        this.chunkId = chunkId;
    }
}
