package com.chaconez.knowengine.document.entity;

import org.springframework.web.multipart.MultipartFile;

/**
 * @author Chaconez
 * @param file
 * @param title
 * @param accessibleBy
 * @param description
 * @param knowledgeBaseType
 */
public record DocumentUploadParam(MultipartFile file, String title, String accessibleBy,
                                  String description, String knowledgeBaseType, String tableName, String version) {
}
