package com.chaconez.knowengine.document.util;

import com.chaconez.knowengine.document.constant.FileType;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
public class FileTypeUtil {

    private static final Tika tika = new Tika();

    public static FileType getFileType(String fileName, MultipartFile file) {
        if (file == null) {
            return null;
        }

        if (isPdfFile(fileName) || isPdfContent(file)) {
            return FileType.PDF;
        }
        if (isCsvFile(fileName)) {
            return FileType.CSV;
        }
        if (isExcelFile(fileName) || isExcelFile(file)) {
            return FileType.EXCEL;
        }

        if (isDocFile(fileName) || isDocFile(file)) {
            return FileType.DOC;
        }

        if (isMarkdownFile(fileName) || isMarkdownFile(file)) {
            return FileType.MARKDOWN;
        }

        if (isTxtFile(fileName) || isTxtFile(file)) {
            return FileType.TXT;
        }

        return null;
    }

    public static FileType getFileType(String fileName) {
        if (fileName == null) {
            return null;
        }

        if (isPdfFile(fileName)) {
            return FileType.PDF;
        }
        if (isCsvFile(fileName)) {
            return FileType.CSV;
        }
        if (isExcelFile(fileName)) {
            return FileType.EXCEL;
        }
        if (isDocFile(fileName)) {
            return FileType.DOC;
        }

        if (isTxtFile(fileName)) {
            return FileType.TXT;
        }
        if (isMarkdownFile(fileName)) {
            return FileType.MARKDOWN;
        }

        return null;
    }


    /**
     * 通过后缀名判断是否为 PDF 文件
     */
    private static boolean isPdfFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".pdf");
    }

    /**
     * 通过 Apache Tika 检测文件内容类型判断是否为 PDF 文件
     */
    private static boolean isPdfContent(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            String mimeType = tika.detect(is);
            return "application/pdf".equals(mimeType);
        } catch (IOException e) {
            log.error("文件类型检测失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean isExcelFile(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            String mimeType = tika.detect(is);
            return mimeType.equals("application/vnd.ms-excel") ||
                    mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (IOException e) {
            log.error("文件类型检测失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean isDocFile(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            String mimeType = tika.detect(is);
            return mimeType.equals("application/msword") ||
                    mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        } catch (IOException e) {
            log.error("文件类型检测失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean isTxtFile(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            String mimeType = tika.detect(is);
            return mimeType.equals("text/plain") ||
                    mimeType.equals("application/txt");
        } catch (IOException e) {
            log.error("文件类型检测失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean isMarkdownFile(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            String mimeType = tika.detect(is);
            return mimeType.equals("text/plain") ||
                    mimeType.equals("application/markdown");
        } catch (IOException e) {
            log.error("文件类型检测失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 通过后缀名判断是否为 Excel 文件
     */
    private static boolean isExcelFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls") || fileName.toLowerCase().endsWith(".csv");
    }

    /**
     * 通过后缀名判断是否为 Word 文件
     *
     * @param fileName
     * @return
     */
    private static boolean isDocFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".docx") || fileName.toLowerCase().endsWith(".doc");
    }

    /**
     * 通过后缀名判断是否为 markdown 文件
     *
     * @param fileName
     * @return
     */
    private static boolean isMarkdownFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".md");
    }

    /**
     * 通过后缀名判断是否为 txt 文件
     *
     * @param fileName
     * @return
     */
    private static boolean isTxtFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".txt");
    }

    /**
     * 通过后缀名判断是否为 CSV 文件
     */
    private static boolean isCsvFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        return fileName.toLowerCase().endsWith(".csv");
    }

}
