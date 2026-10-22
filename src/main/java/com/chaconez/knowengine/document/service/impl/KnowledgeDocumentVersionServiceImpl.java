package com.chaconez.knowengine.document.service.impl;

import com.chaconez.knowengine.document.entity.KnowledgeDocumentVersion;
import com.chaconez.knowengine.document.mapper.KnowledgeDocumentVersionMapper;
import com.chaconez.knowengine.document.service.KnowledgeDocumentVersionService;
import com.chaconez.knowengine.document.util.VersionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 文档版本表 Service 实现类
 */
@Service
public class KnowledgeDocumentVersionServiceImpl
        extends ServiceImpl<KnowledgeDocumentVersionMapper, KnowledgeDocumentVersion>
        implements KnowledgeDocumentVersionService {

    /**
     * 语义化版本比较器（按 major.minor.patch 数值比较）
     */
    private static final Comparator<KnowledgeDocumentVersion> VERSION_COMPARATOR =
            Comparator.comparing(KnowledgeDocumentVersion::getVersion, VersionUtil::compareVersions);

    @Override
    public List<KnowledgeDocumentVersion> listByDocId(Long docId) {
        List<KnowledgeDocumentVersion> versions = list(new QueryWrapper<KnowledgeDocumentVersion>()
                .eq("doc_id", docId));
        // 在 Java 层按语义版本降序排序
        versions.sort(VERSION_COMPARATOR.reversed());
        return versions;
    }

    @Override
    public List<KnowledgeDocumentVersion> listByDocIdAndVersion(Long docId, String version) {
        return list(new QueryWrapper<KnowledgeDocumentVersion>()
                .eq("doc_id", docId)
                .eq("version", version));
    }

    @Override
    public String getLatestVersion(Long docId) {
        List<KnowledgeDocumentVersion> versions = listByDocId(docId);
        if (versions.isEmpty()) {
            return null;
        }
        return versions.get(0).getVersion();
    }

    @Override
    public boolean existsByContentHash(String contentHash) {
        return count(new QueryWrapper<KnowledgeDocumentVersion>()
                .eq("content_hash", contentHash)) > 0;
    }


}
