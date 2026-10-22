package com.chaconez.knowengine.document.service;

import com.chaconez.knowengine.document.entity.TableMeta;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;


public interface KnowEngineTableMetaService extends IService<TableMeta> {

    /**
     * 查询当前应暴露给 Text2SQL 的动态表元数据。
     * <p>
     * 只返回满足以下条件的表：
     * <ul>
     *   <li>version_id 不为空，且等于对应 knowledge_document.current_version_id</li>
     *   <li>version_id 为空的老数据（兼容旧表）</li>
     * </ul>
     * 这样新本版上传期间，旧版本的物理表仍然可以被查询；新版本数据就绪、
     * current_version_id 切换后，LLM 自动感知到新表，实现不停机更新。
     */
    List<TableMeta> listActiveForQuery();
}
