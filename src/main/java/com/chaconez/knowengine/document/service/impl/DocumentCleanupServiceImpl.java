package com.chaconez.knowengine.document.service.impl;

import com.chaconez.knowengine.document.service.DocumentCleanupService;
import com.chaconez.knowengine.document.service.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 文档版本清理服务实现
 * 负责清理向量（ES），保留当前版本数据
 */
@Slf4j
@Service
public class DocumentCleanupServiceImpl implements DocumentCleanupService {

    @Autowired
    private VectorStoreService vectorStoreService;

    @Override
    public boolean cleanupOldVersionData(Long docId, Long versionId) {
        log.info("开始清理文档 {} 的旧版本数据（保留 versionId={}）", docId, versionId);

        // 清理旧版本向量
        vectorStoreService.removeByDocIdAndVersion(docId, versionId);
        log.info("清理文档 {} 旧版本向量完成", docId);
        return true;
    }
}
