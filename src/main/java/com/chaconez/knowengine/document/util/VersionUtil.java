package com.chaconez.knowengine.document.util;

public class VersionUtil {
    /**
     * 语义化版本号比较（如 "1.0.0" vs "2.1.3"）
     *
     * @return 负数表示 v1 < v2，0 表示相等，正数表示 v1 > v2
     */
    public static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLength = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLength; i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }
}
