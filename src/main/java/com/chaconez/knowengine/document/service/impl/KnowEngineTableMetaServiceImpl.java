package com.chaconez.knowengine.document.service.impl;

import com.chaconez.knowengine.document.entity.TableMeta;
import com.chaconez.knowengine.document.mapper.TableMetaMapper;
import com.chaconez.knowengine.document.service.KnowEngineTableMetaService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识片段表 Service 实现类
 */
@Service
public class KnowEngineTableMetaServiceImpl extends ServiceImpl<TableMetaMapper, TableMeta> implements KnowEngineTableMetaService {

    @Override
    public List<TableMeta> listActiveForQuery() {
        // DATA_QUERY 同一逻辑表在所有版本中复用同一个物理表，
        // 因此只要表元数据存在且未被逻辑删除，就暴露给 Text2SQL。
        List<TableMeta> allMetas = list();
        if (CollectionUtils.isEmpty(allMetas)) {
            return Collections.emptyList();
        }
        return allMetas.stream()
                .filter(meta -> meta.getCreateSql() != null && !meta.getCreateSql().isBlank())
                .collect(Collectors.toList());
    }
}
