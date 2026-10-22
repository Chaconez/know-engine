package com.chaconez.knowengine.rag.modules.splitter;

import dev.langchain4j.data.segment.TextSegment;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * RagFlowExcelParser 使用示例
 */
public class ExcelSplitterTest {

    @Test
    public void testSplit() throws IOException {
        // 创建解析器实例（使用默认配置）
        ExcelSplitter parser = new ExcelSplitter();

        // 或者使用自定义配置
        // RagFlowExcelParser parser = new RagFlowExcelParser(12, 12800);

        System.out.println("=== RAGFlow Excel Parser Java 实现示例 ===\n");

        // ==================== 示例1: 键值对模式 ====================
        System.out.println("【模式1】键值对输出（适合RAG检索）");
        System.out.println("格式: 表头1: 值1; 表头2: 值2; ...\n");

        // 模拟Excel数据（实际使用时读取文件）
        String csvContent = "姓名,部门,销售额,月份\n" +
                "张三,销售部,150万,2024-01\n" +
                "李四,技术部,200万,2024-01\n" +
                "王五,销售部,180万,2024-02";

        byte[] csvData = csvContent.getBytes();

        List<TextSegment> keyValueResults = parser.split(csvData);

        System.out.println("解析结果（共 " + keyValueResults.size() + " 条）:");
        for (int i = 0; i < keyValueResults.size(); i++) {
            System.out.println((i + 1) + ". " + keyValueResults.get(i));
        }

    }

    @Test
    public void testSplitHtml() throws IOException {
        ExcelSplitter parser = new ExcelSplitter(500, true);
        System.out.println("\n\n【模式2】HTML表格输出（适合保留表格结构）");
        System.out.println("按 " + parser.getChunkSize() + " 字符分块\n");

        // 模拟Excel数据（实际使用时读取文件）
        String csvContent = "姓名,部门,销售额,月份\n" +
                "张三,销售部,150万,2024-01\n" +
                "李四,技术部,200万,2024-01\n" +
                "王五,销售部,180万,2024-02";

        byte[] csvData = csvContent.getBytes();

        List<TextSegment> htmlResults = parser.split(csvData);

        System.out.println("解析结果（共 " + htmlResults.size() + " 个HTML块）:");
        for (int i = 0; i < htmlResults.size(); i++) {
            System.out.println("\n--- HTML块 " + (i + 1) + " ---");
            System.out.println(htmlResults.get(i));
        }
    }

    @Test
    public void testSplitHtml2() throws IOException {
        ExcelSplitter parser = new ExcelSplitter(500, true);
        System.out.println("\n\n【示例3】大表格分块演示");
        System.out.println("模拟20行数据，按500字符分块:\n");

        StringBuilder largeCsv = new StringBuilder("ID,名称,描述\n");
        for (int i = 1; i <= 20; i++) {
            largeCsv.append(i).append(",项目").append(i).append(",这是第").append(i).append("个项目的描述\n");
        }

        List<TextSegment> largeHtmlResults = parser.split(largeCsv.toString().getBytes());
        System.out.println("分块数量: " + largeHtmlResults.size());
        for (int i = 0; i < largeHtmlResults.size(); i++) {
            System.out.println("块" + (i + 1) + " 行数: " + countRows(largeHtmlResults.get(i).text()));
            System.out.println("块" + (i + 1) + ":\n" + largeHtmlResults.get(i));
        }
    }

    @Test
    @Ignore
    public void testSplitHtml3() throws IOException {
        ExcelSplitter parser = new ExcelSplitter(500, true);
        byte[] fileData = Files.readAllBytes(new File("data.xlsx").toPath());
        List<TextSegment> results = parser.split(fileData);
    }

    private static int countRows(String html) {
        int count = 0;
        int index = 0;
        while ((index = html.indexOf("<tr>", index)) != -1) {
            count++;
            index++;
        }
        return count - 1; // 减去表头
    }
}
