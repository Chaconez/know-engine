package com.chaconez.knowengine.document.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Excel处理服务接口
 * 负责解析Excel文件、创建数据库表、插入数据并保存表元数据
 */
public interface ExcelProcessService {

    /**
     * 处理Excel文件
     * 解析Excel，创建表，插入数据，保存元数据
     *
     * @param file        Excel文件
     * @param tableName   目标表名（如果为空，则自动生成）
     * @param description 表描述
     * @param overwrite   是否覆盖已存在的表
     * @return 创建的表名
     * @throws IOException 当文件处理失败时抛出
     */
    String processExcel(MultipartFile file, String tableName, String description, boolean overwrite) throws IOException;

    /**
     * 处理Excel文件（使用自动生成的表名）
     *
     * @param file        Excel文件
     * @param description 表描述
     * @return 创建的表名
     * @throws IOException 当文件处理失败时抛出
     */
    String processExcel(MultipartFile file, String description) throws IOException;

}
