package com.chaconez.knowengine.document.mapper;

import com.chaconez.knowengine.document.entity.TableMeta;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 表元数据 Mapper 接口
 */
@Mapper
public interface TableMetaMapper extends BaseMapper<TableMeta> {

    /**
     * 执行动态SQL（建表）
     *
     * @param sql 建表SQL语句
     */
    @Update("${sql}")
    void executeCreateTable(@Param("sql") String sql);

    /**
     * 执行动态SQL（插入数据）
     *
     * @param sql 插入SQL语句
     */
    @Update("${sql}")
    void executeInsert(@Param("sql") String sql);

    /**
     * 查询动态表数据
     *
     * @param sql 查询SQL语句
     * @return 数据列表
     */
    @Select("${sql}")
    List<Map<String, Object>> executeQuery(@Param("sql") String sql);

    /**
     * 删除动态表
     *
     * @param tableName 表名
     */
    @Update("DROP TABLE IF EXISTS ${tableName}")
    void dropTable(@Param("tableName") String tableName);

    /**
     * 检查表是否存在
     *
     * @param tableName 表名
     * @return 存在返回1，不存在返回0
     */
    @Select("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = #{tableName} AND table_schema = DATABASE()")
    int checkTableExists(@Param("tableName") String tableName);

    /**
     * 根据表名物理删除 table_meta 记录（绕过逻辑删除）
     *
     * @param tableName 表名
     */
    @Delete("DELETE FROM table_meta WHERE table_name = #{tableName}")
    void physicalDeleteByTableName(@Param("tableName") String tableName);
}
