package com.chaconez.knowengine.document.service;

import com.chaconez.knowengine.document.entity.DocumentSplitParam;
import com.chaconez.knowengine.document.entity.DocumentUploadParam;
import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.chaconez.knowengine.document.entity.KnowledgeDocumentVersion;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 文档处理服务接口
 * 负责文档的业务流程处理：上传、转换、分段、向量化
 */
public interface DocumentProcessService {

    /**
     * 上传文件
     * @param documentUploadParam 上传参数
     * @param uploadUser 上传用户
     * @return 保存后的文档记录
     * @throws IOException IO异常
     */
    public KnowledgeDocument upload(DocumentUploadParam documentUploadParam, String uploadUser) throws IOException;

    /**
     * 上传文档新版本
     * @param docId     文档ID（knowledge_document.doc_id）
     * @param version   新版本号（语义化版本，如 "2.0.0"，必须大于现有最大版本号）
     * @param file      新版本文件
     * @param uploadUser 上传用户
     * @param changelog 版本变更说明（可选）
     * @return 更新后的文档记录
     * @throws IOException IO异常
     */
    public KnowledgeDocument uploadNewVersion(Long docId, String version, MultipartFile file, String uploadUser, String changelog) throws IOException;

    /**
     * 对文档进行切分
     * 使用 MarkdownHeaderParentTextSplitter 进行切分
     *
     * @param document 文档ID
     * @return 切分后的片段数量
     */
    public int split(KnowledgeDocument document, DocumentSplitParam documentSplitParam);

    /**
     * 向量化并存储
     *
     * @param knowledgeDocumentVersion
     * @return 是否成功
     */
    public boolean embedAndStore(KnowledgeDocumentVersion knowledgeDocumentVersion);

    /**
     * 切换文档到指定版本
     * 将文档的当前激活版本切换为目标版本，清理旧版本分段和向量，恢复目标版本的文件URL和状态
     *
     * @param docId     文档ID
     * @param versionId 目标版本ID
     * @return 更新后的文档记录
     */
    public KnowledgeDocument switchVersion(Long docId, Long versionId);

    /**
     * 预览 DATA_QUERY 类型文档的动态表数据
     *
     * @param docId   文档ID
     * @param current 当前页
     * @param size    每页大小
     * @return 分页数据
     */
    public Page<Map<String, Object>> previewData(Long docId, int current, int size);
}
