package com.chaconez.knowengine.document.service;

import com.chaconez.knowengine.document.entity.KnowledgeSegment;
import com.baomidou.mybatisplus.extension.service.IService;

import java.io.Serializable;

/**
 * 知识片段表 Service 接口
 */
public interface KnowledgeSegmentService extends IService<KnowledgeSegment> {

    public String getTextByChunkId(Serializable chunkId);

    /**
     * 根据文档ID逻辑删除所有分段
     *
     * @param docId 文档ID
     * @return 删除的分段数量
     */
    int removeSegmentsByDocId(Long docId);

    /**
     * 根据ID更新分段信息
     * @param entity
     * @param updateVectorStore
     * @return
     */
    public boolean updateById(KnowledgeSegment entity, Boolean updateVectorStore);
}
