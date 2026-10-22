package com.chaconez.knowengine.document.mapper;

import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
 * 知识文档表 Mapper 接口
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /** 按主键物理删除单个文档 */
    @Delete("DELETE FROM knowledge_document WHERE doc_id = #{docId}")
    int physicalDeleteByDocId(@Param("docId") Long docId);

    /** 按主键列表批量物理删除文档 */
    @Delete("<script>DELETE FROM knowledge_document WHERE doc_id IN " +
            "<foreach item='docId' collection='docIds' open='(' separator=',' close=')'>#{docId}</foreach></script>")
    int physicalDeleteByDocIds(@Param("docIds") Collection<Long> docIds);
}
