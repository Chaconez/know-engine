package com.chaconez.knowengine.document.mapper;

import com.chaconez.knowengine.document.entity.KnowledgeSegment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
 * 知识片段表 Mapper 接口
 */
@Mapper
public interface KnowledgeSegmentMapper extends BaseMapper<KnowledgeSegment> {

    /** 按主键物理删除单条分段（绕过 @TableLogic） */
    @Delete("DELETE FROM knowledge_segment WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);

    /** 按主键列表批量物理删除分段 */
    @Delete("<script>DELETE FROM knowledge_segment WHERE id IN " +
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int physicalDeleteByIds(@Param("ids") Collection<?> ids);

    /** 按文档ID物理删除该文档下所有分段 */
    @Delete("DELETE FROM knowledge_segment WHERE document_id = #{docId}")
    int physicalDeleteByDocumentId(@Param("docId") Long docId);

    /** 按文档ID列表批量物理删除所有分段 */
    @Delete("<script>DELETE FROM knowledge_segment WHERE document_id IN " +
            "<foreach item='docId' collection='docIds' open='(' separator=',' close=')'>#{docId}</foreach></script>")
    int physicalDeleteByDocumentIds(@Param("docIds") Collection<Long> docIds);
}
