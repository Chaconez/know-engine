package com.chaconez.knowengine.document.service.impl;

import com.chaconez.knowengine.document.entity.KnowledgeSegment;
import com.chaconez.knowengine.document.event.SegmentEditedEvent;
import com.chaconez.knowengine.document.mapper.KnowledgeSegmentMapper;
import com.chaconez.knowengine.document.service.KnowledgeSegmentService;
import com.chaconez.knowengine.document.service.VectorStoreService;
import com.chaconez.knowengine.rag.constant.MetadataKeyConstant;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识片段表 Service 实现类
 */
@Slf4j
@Service
public class KnowledgeSegmentServiceImpl extends ServiceImpl<KnowledgeSegmentMapper, KnowledgeSegment> implements KnowledgeSegmentService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public String getTextByChunkId(Serializable chunkId) {
        String text = stringRedisTemplate.opsForValue().get(chunkId);
        if (text != null) {
            if (text.isEmpty()) {
                return null;
            }
            return text;
        }

        QueryWrapper<KnowledgeSegment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("chunk_id", chunkId);
        KnowledgeSegment segment = super.getOne(queryWrapper);

        if (segment != null) {
            stringRedisTemplate.opsForValue().set(chunkId.toString(), segment.getText(), 30, TimeUnit.SECONDS);
            return segment.getText();
        } else {
            // 缓存空值，避免缓存击穿，重复查询数据库
            stringRedisTemplate.opsForValue().set(chunkId.toString(), "");
        }

        return null;
    }

    /**
     * 根据文档ID物理删除所有分段（绕过 @TableLogic）
     */
    @Override
    public int removeSegmentsByDocId(Long docId) {
        return baseMapper.physicalDeleteByDocumentId(docId);
    }

    /**
     * 删除分段，同时删除向量存储中对应的向量（物理删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        // 先查询分段获取 embeddingId，再删除向量
        KnowledgeSegment segment = super.getById(id);
        if (segment != null) {
            vectorStoreService.remove(segment.getEmbeddingId());
        }
        // 物理删除，绕过 @TableLogic
        return baseMapper.physicalDeleteById((Long) id) > 0;
    }

    /**
     * 批量删除分段，同时删除向量存储中对应的向量（物理删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeByIds(Collection<?> idList) {
        // 先查询分段获取 embeddingId 列表，再批量删除向量
        QueryWrapper<KnowledgeSegment> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("id", idList);
        List<KnowledgeSegment> segments = super.list(queryWrapper);
        List<String> embeddingIds = segments.stream()
                .map(KnowledgeSegment::getEmbeddingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        vectorStoreService.removeAll(embeddingIds);
        // 物理删除，绕过 @TableLogic
        return baseMapper.physicalDeleteByIds(idList) > 0;
    }

    /**
     * 更新分段，当文本内容变更时同步更新向量数据库（遵循分段更新与向量库同步规范）：
     * 1. 若子分段文本变更，同步更新对应父分段文本及 Redis 缓存；
     * 2. 仅当文本内容变更 && 已有 embeddingId && skipEmbedding ≠ 1 时，
     *    执行 ES 向量删除 → 重嵌入 → 写入流程；ES 操作失败仅记录日志，不中断 DB 更新。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(KnowledgeSegment entity, Boolean updateVectorStore) {
        KnowledgeSegment oldSegment = super.getById(entity.getId());
        if (oldSegment == null) {
            return super.updateById(entity);
        }

        boolean textChanged = entity.getText() != null && !entity.getText().equals(oldSegment.getText());

        if (updateVectorStore) {
            boolean hasEmbedding = oldSegment.getEmbeddingId() != null;
            boolean skipEmbedding = oldSegment.getSkipEmbedding() != null && oldSegment.getSkipEmbedding() == 1;

            if (textChanged && hasEmbedding && !skipEmbedding) {
                // 1. 删除旧向量
                vectorStoreService.remove(oldSegment.getEmbeddingId());

                // 2. 生成新向量并写入 ES
                try {
                    // 若更新请求未携带 metadata，复用旧分段的 metadata，避免检索过滤信息丢失
                    if (entity.getMetadata() == null && oldSegment.getMetadata() != null) {
                        entity.setMetadata(oldSegment.getMetadata());
                    }
                    String newEmbeddingId = vectorStoreService.embedAndStore(entity);
                    entity.setEmbeddingId(newEmbeddingId);
                    log.info("更新向量成功, segmentId: {}, oldEmbeddingId: {}, newEmbeddingId: {}",
                            entity.getId(), oldSegment.getEmbeddingId(), newEmbeddingId);
                } catch (Exception e) {
                    // 旧向量已删除，新向量写入失败，清空 embeddingId 保持 DB 与 ES 一致
                    entity.setEmbeddingId(null);
                    log.error("更新向量失败, segmentId: {}, error: {}", entity.getId(), e.getMessage(), e);
                }
            }

        }

        // 文本变更时，同步更新父分段内容
        if (textChanged) {
            syncParentSegmentText(oldSegment, entity.getText());
            // 通知缓存等模块执行分段级精确失效。
            if (oldSegment.getChunkId() != null) {
                log.info("分段内容变更，通知缓存等模块执行分段级精确失效, chunkId: {}", oldSegment.getChunkId());
                eventPublisher.publishEvent(new SegmentEditedEvent(this, oldSegment.getChunkId()));
            }
        }

        return super.updateById(entity);
    }

    /**
     * 当子分段文本变更时，同步更新对应父分段的文本内容。
     * <p>
     * 父分段存储完整文本（skipEmbedding=1），子分段是其中的子串。
     * 修改子分段时需将父分段中对应的旧文本替换为新文本，并清除 Redis 缓存，保证检索时获取最新内容。
     *
     * @param oldSegment 修改前的子分段（包含旧文本和 metadata）
     * @param newText   修改后的新文本
     */
    private void syncParentSegmentText(KnowledgeSegment oldSegment, String newText) {
        Map<String, String> metadataMap = oldSegment.getMetadataMap();
        if (metadataMap == null) {
            return;
        }
        String parentChunkId = metadataMap.get(MetadataKeyConstant.PARENT_CHUNK_ID);
        if (parentChunkId == null) {
            return;
        }

        // 查找父分段
        QueryWrapper<KnowledgeSegment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("chunk_id", parentChunkId);
        KnowledgeSegment parentSegment = super.getOne(queryWrapper);
        if (parentSegment == null) {
            log.warn("子分段修改后同步父分段失败：未找到父分段, parentChunkId: {}", parentChunkId);
            return;
        }

        // 将父分段文本中的旧子分段文本替换为新文本
        String oldText = oldSegment.getText();
        String parentText = parentSegment.getText();
        String updatedParentText = parentText.replaceFirst(Pattern.quote(oldText), Matcher.quoteReplacement(newText));

        if (!updatedParentText.equals(parentText)) {
            parentSegment.setText(updatedParentText);
            super.updateById(parentSegment);
            log.info("子分段修改已同步更新父分段, parentChunkId: {}, parentSegmentId: {}", parentChunkId, parentSegment.getId());

            // 清除父分段的 Redis 缓存
            stringRedisTemplate.delete(parentChunkId);
        } else {
            log.warn("子分段修改后父分段文本未匹配到旧文本，跳过同步, parentChunkId: {}, segmentId: {}", parentChunkId, oldSegment.getId());
        }
    }
}
