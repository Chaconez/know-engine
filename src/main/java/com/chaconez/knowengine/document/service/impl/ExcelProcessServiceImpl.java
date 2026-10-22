package com.chaconez.knowengine.document.service.impl;

import com.chaconez.knowengine.document.constant.FileType;
import com.chaconez.knowengine.document.constant.KnowledgeBaseType;
import com.chaconez.knowengine.document.entity.KnowledgeDocument;
import com.chaconez.knowengine.document.entity.TableMeta;
import com.chaconez.knowengine.document.mapper.TableMetaMapper;
import com.chaconez.knowengine.document.service.FileProcessService;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Excel处理服务实现类
 */
@Slf4j
@Service
public class ExcelProcessServiceImpl implements FileProcessService {

    @Autowired
    private TableMetaMapper tableMetaMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 表名前缀
    private static final String TABLE_PREFIX = "custom_data_query_";
    // 有效的表名正则表达式
    private static final Pattern VALID_TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String processDocument(KnowledgeDocument document, InputStream inputStream) {
        String documentTitle = document.getDocTitle();
        String originalTableName = document.getTableName();
        Long versionId = document.getCurrentVersionId();
        Assert.notNull(versionId, "文档当前版本ID不能为空");
        log.info("开始处理Excel文件: {}, versionId={}", documentTitle, versionId);

        // 1. 解析Excel文件
        try {
            List<List<String>> excelData = parseExcel(inputStream);

            if (excelData.isEmpty() || excelData.size() < 2) {
                throw new IllegalArgumentException("Excel文件为空或只有表头，没有数据行");
            }

            // 2. 获取表头
            List<String> headers = excelData.get(0);
            if (headers.isEmpty()) {
                throw new IllegalArgumentException("Excel表头为空");
            }

            // 3. 生成表名（同一逻辑表在所有版本中共用）
            String tableName = generatePhysicalTableName(originalTableName);

            // 4. 生成列信息
            List<ColumnInfo> columns = generateColumnInfo(headers);

            // 5. 判断表是否已存在
            boolean tableExists = tableMetaMapper.checkTableExists(tableName) > 0;

            if (tableExists) {
                // 5.1 已存在：校验表结构必须与之前完全一致，否则禁止上传
                TableMeta existingMeta = tableMetaMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TableMeta>()
                                .eq(TableMeta::getTableName, tableName)
                );
                Assert.notNull(existingMeta, "表 " + tableName + " 的元数据不存在");
                List<ColumnInfo> existingColumns = parseColumnInfo(existingMeta.getColumnsInfo());
                if (!isSchemaCompatible(existingColumns, columns)) {
                    throw new IllegalArgumentException(
                            "Excel 表结构与已有表 " + tableName + " 不一致，禁止上传。请保持表头、列名、顺序及类型完全一致。");
                }

                // 5.2 结构一致：在一个事务内删除旧数据并写入新数据，实现数据替换
                log.info("表 {} 已存在且结构一致，执行数据替换", tableName);
                deleteAllData(tableName);
                List<List<String>> dataRows = excelData.subList(1, excelData.size());
                int insertedCount = insertData(tableName, columns, dataRows);
                log.info("表 {} 数据替换完成，新数据 {} 行", tableName, insertedCount);

                // 5.3 更新元数据中的版本绑定为当前版本
                existingMeta.setVersionId(versionId);
                existingMeta.setDescription(document.getDescription() != null ? document.getDescription() : "从Excel导入: " + documentTitle);
                existingMeta.setUpdatedAt(LocalDateTime.now());
                boolean updateResult = tableMetaMapper.updateById(existingMeta) > 0;
                Assert.isTrue(updateResult, "表元数据更新失败");
            } else {
                // 6. 表不存在：新建表并导数
                String createTableSql = generateCreateTableSql(tableName, document.getDescription(), columns);
                log.info("生成建表SQL: {}", createTableSql);

                tableMetaMapper.executeCreateTable(createTableSql);
                log.info("表 {} 创建成功", tableName);

                List<List<String>> dataRows = excelData.subList(1, excelData.size());
                int insertedCount = insertData(tableName, columns, dataRows);
                log.info("插入数据 {} 行", insertedCount);

                TableMeta tableMeta = new TableMeta();
                tableMeta.setTableName(tableName);
                tableMeta.setDescription(document.getDescription() != null ? document.getDescription() : "从Excel导入: " + documentTitle);
                tableMeta.setCreateSql(createTableSql);
                tableMeta.setColumnsInfo(JSON.toJSONString(columns));
                tableMeta.setVersionId(versionId);
                tableMeta.setCreatedAt(LocalDateTime.now());
                tableMeta.setUpdatedAt(LocalDateTime.now());
                int result = tableMetaMapper.insert(tableMeta);
                Assert.isTrue(result == 1, "表元数据保存失败");
                log.info("表元数据保存成功, ID: {}", tableMeta.getId());
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
        }
        return null;
    }

    /**
     * 根据逻辑表名生成物理表名
     * <p>
     * 同一逻辑表在所有版本中复用同一个物理表名。
     */
    public String generatePhysicalTableName(String originalFilename) {
        String baseName = originalFilename;
        // 去掉扩展名
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }
        // 清理非法字符
        baseName = sanitizeTableName(baseName);
        // 限制 baseName 长度，确保加上前缀后不超过 MySQL 表名上限 64
        int maxBaseLength = 64 - TABLE_PREFIX.length();
        if (baseName.length() > maxBaseLength) {
            baseName = baseName.substring(0, maxBaseLength);
        }
        baseName = baseName.replaceAll("_+$", "");
        if (baseName.isEmpty()) {
            baseName = "table";
        }
        return TABLE_PREFIX + baseName;
    }


    @Autowired
    protected TransactionTemplate transactionTemplate;

    public void dropTable(String tableName) {
        // 安全检查
        if (!isValidTableName(tableName)) {
            throw new IllegalArgumentException("无效的表名: " + tableName);
        }

        transactionTemplate.executeWithoutResult((status) -> {
            // 1. 删除物理表
            tableMetaMapper.dropTable(tableName);
            log.info("物理表 {} 删除成功", tableName);

            // 2. 物理删除元数据记录（BaseEntity 开启了逻辑删除，必须绕过 @TableLogic）
            tableMetaMapper.physicalDeleteByTableName(tableName);
            log.info("表 {} 的元数据删除成功", tableName);
        });
    }

    /**
     * 解析Excel文件
     */
    private List<List<String>> parseExcel(InputStream inputStream) throws IOException {
        List<List<String>> result = new ArrayList<>();

        EasyExcel.read(inputStream, new ReadListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> data, AnalysisContext context) {
                List<String> row = new ArrayList<>();
                // 获取当前行的最大索引
                int maxIndex = data.keySet().stream().max(Integer::compareTo).orElse(-1);
                // 按顺序填充每一列
                for (int i = 0; i <= maxIndex; i++) {
                    String value = data.getOrDefault(i, "");
                    row.add(value != null ? value : "");
                }
                result.add(row);
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.info("Excel解析完成，共 {} 行", result.size());
            }
            //EasyExcel 默认将第一行视为表头，不会通过 ReadListener.invoke() 回调返回。所以 parseExcel 返回的数据实际上是从 Excel 的第二行开始的。
            //需要设置 headRowNumber(0) 告诉 EasyExcel 从第一行就开始读取数据
        }).headRowNumber(0).sheet().doRead();

        return result;
    }

    /**
     * 解析已保存的列信息 JSON
     */
    private List<ColumnInfo> parseColumnInfo(String columnsInfoJson) {
        if (columnsInfoJson == null || columnsInfoJson.isBlank()) {
            return Collections.emptyList();
        }
        return JSON.parseArray(columnsInfoJson, ColumnInfo.class);
    }

    /**
     * 判断两次上传的表结构是否一致
     * <p>
     * 要求：列数量、列名、数据类型、顺序完全一致
     */
    private boolean isSchemaCompatible(List<ColumnInfo> existingColumns, List<ColumnInfo> newColumns) {
        if (existingColumns == null || newColumns == null) {
            return existingColumns == newColumns;
        }
        if (existingColumns.size() != newColumns.size()) {
            return false;
        }
        for (int i = 0; i < existingColumns.size(); i++) {
            ColumnInfo a = existingColumns.get(i);
            ColumnInfo b = newColumns.get(i);
            if (a == null || b == null) {
                return false;
            }
            if (!Objects.equals(a.getColumnName(), b.getColumnName())) {
                return false;
            }
            if (!Objects.equals(a.getDataType(), b.getDataType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 清空指定表的所有数据
     */
    private void deleteAllData(String tableName) {
        if (!isValidTableName(tableName)) {
            throw new IllegalArgumentException("无效的表名: " + tableName);
        }
        String deleteSql = "DELETE FROM `" + tableName + "`";
        jdbcTemplate.execute(deleteSql);
        log.info("表 {} 旧数据已清空", tableName);
    }

    /**
     * 清理表名，确保符合MySQL命名规范
     */
    private String sanitizeTableName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "table_" + System.currentTimeMillis();
        }

        // 转换为小写
        String sanitized = name.toLowerCase();
        // 替换非法字符为下划线
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9_]", "_");
        // 确保以字母或下划线开头
        if (!sanitized.matches("^[a-zA-Z_].*")) {
            sanitized = "t_" + sanitized;
        }
        // 限制长度（MySQL表名最大64字符）
        if (sanitized.length() > 60) {
            sanitized = sanitized.substring(0, 60);
        }
        // 去掉末尾的下划线
        sanitized = sanitized.replaceAll("_+$", "");

        return sanitized;
    }

    /**
     * 验证表名是否有效
     */
    private boolean isValidTableName(String tableName) {
        return tableName != null && VALID_TABLE_NAME_PATTERN.matcher(tableName).matches();
    }

    /**
     * 生成列信息
     */
    private List<ColumnInfo> generateColumnInfo(List<String> headers) {
        List<ColumnInfo> columns = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            String columnName = sanitizeColumnName(header);

            // 处理重复的列名
            String originalName = columnName;
            int suffix = 1;
            while (usedNames.contains(columnName)) {
                columnName = originalName + "_" + suffix++;
            }
            usedNames.add(columnName);

            ColumnInfo column = new ColumnInfo();
            column.setIndex(i);
            column.setOriginalHeader(header);
            column.setColumnName(columnName);
            column.setDataType("VARCHAR(500)"); // 默认使用VARCHAR类型
            columns.add(column);
        }

        return columns;
    }

    /**
     * 清理列名
     */
    private String sanitizeColumnName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "col";
        }

        // 转换为小写
        String sanitized = name.toLowerCase().trim();
        // 替换非法字符为下划线
        sanitized = sanitized.replaceAll("[^a-zA-Z0-9_]", "_");
        // 确保以字母开头
        if (!sanitized.matches("^[a-zA-Z].*")) {
            sanitized = "col_" + sanitized;
        }
        // 限制长度（MySQL列名最大64字符）
        if (sanitized.length() > 60) {
            sanitized = sanitized.substring(0, 60);
        }
        // 去掉连续和末尾的下划线
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("_+$", "");

        return sanitized;
    }

    /**
     * 生成建表SQL
     */
    private String generateCreateTableSql(String tableName, String description, List<ColumnInfo> columns) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("` (\n");

        // 添加自增主键
        sql.append("  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',\n");

        // 添加Excel列
        for (ColumnInfo column : columns) {
            sql.append("  `").append(column.getColumnName()).append("` ")
                    .append(column.getDataType())
                    .append(" DEFAULT NULL COMMENT '")
                    .append(escapeSqlComment(column.getOriginalHeader()))
                    .append("',\n");
        }

        // 添加创建时间和更新时间
        sql.append("  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',\n");
        sql.append("  `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',\n");

        // 设置主键
        sql.append("  PRIMARY KEY (`id`)\n");
        sql.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='" + description + "'");

        return sql.toString();
    }

    /**
     * 转义SQL注释中的特殊字符
     */
    private String escapeSqlComment(String comment) {
        if (comment == null) {
            return "";
        }
        return comment.replace("'", "\\'").replace("\\", "\\\\");
    }

    /**
     * 插入数据
     */
    private int insertData(String tableName, List<ColumnInfo> columns, List<List<String>> dataRows) {
        int batchSize = 500; // 每批插入500条
        int totalInserted = 0;

        for (int i = 0; i < dataRows.size(); i += batchSize) {
            List<List<String>> batch = dataRows.subList(i, Math.min(i + batchSize, dataRows.size()));
            String insertSql = generateBatchInsertSql(tableName, columns, batch);
            // 使用 JdbcTemplate 执行，绕过 MyBatis-Plus 的BlockAttackInnerInterceptor拦截器
            jdbcTemplate.execute(insertSql);
            totalInserted += batch.size();
        }

        return totalInserted;
    }

    /**
     * 生成批量插入SQL
     */
    private String generateBatchInsertSql(String tableName, List<ColumnInfo> columns, List<List<String>> rows) {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO `").append(tableName).append("` (");

        // 列名
        String columnNames = columns.stream()
                .map(c -> "`" + c.getColumnName() + "`")
                .collect(Collectors.joining(", "));
        sql.append(columnNames).append(") VALUES ");

        // 值
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(");

            for (int j = 0; j < columns.size(); j++) {
                if (j > 0) {
                    sql.append(", ");
                }

                String value = j < row.size() ? row.get(j) : "";
                sql.append(escapeSqlValue(value));
            }

            sql.append(")");
        }

        return sql.toString();
    }

    /**
     * 转义SQL值
     */
    private String escapeSqlValue(String value) {
        if (value == null || value.isEmpty()) {
            return "NULL";
        }

        // 转义单引号
        String escaped = value.replace("'", "''");
        // 处理换行符和制表符
        escaped = escaped.replace("\\", "\\\\");
        escaped = escaped.replace("\n", "\\n");
        escaped = escaped.replace("\r", "\\r");
        escaped = escaped.replace("\t", "\\t");

        return "'" + escaped + "'";
    }

    @Override
    public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        /**
         * 只有Excel和CSV文件，并且知识库类型为数据查询时支持
         */
        if(FileType.EXCEL.equals(fileType) || FileType.CSV.equals(fileType)){
            return knowledgeBaseType == KnowledgeBaseType.DATA_QUERY;
        }
        return false;
    }

    /**
     * 列信息内部类
     */
    public static class ColumnInfo {
        private int index;
        private String originalHeader;
        private String columnName;
        private String dataType;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public String getOriginalHeader() {
            return originalHeader;
        }

        public void setOriginalHeader(String originalHeader) {
            this.originalHeader = originalHeader;
        }

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }
    }
}
