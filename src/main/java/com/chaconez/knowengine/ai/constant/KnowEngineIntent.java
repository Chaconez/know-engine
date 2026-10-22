package com.chaconez.knowengine.ai.constant;

import com.chaconez.knowengine.ai.model.IntentRecognitionResult;

public enum KnowEngineIntent {

    /**
     * 汽车售前咨询
     */
    CAR_BEFORE_SALES("car-before-sales-query-prompt.txt"),
    /**
     * 汽车投诉
     */
    CAR_COMPLAINTS("car-complaints-query-prompt.txt"),
    /**
     * 汽车保养
     */
    CAR_MAINTENANCE("car-maintenance-query-prompt.txt"),
    /**
     * 汽车营销查询
     */
    CAR_MARKETING("car-marketing-query-prompt.txt"),
    /**
     * 汽车技术支持查询
     */
    CAR_TECH_SUPPORT("car-tech-support-query-prompt.txt"),
    /**
     * 汽车其他相关问题
     */
    CAR_OTHER("car-other-query-prompt.txt");

    private final String fileName;

    KnowEngineIntent(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return fileName;
    }

    public static KnowEngineIntent getIntent(IntentRecognitionResult intentRecognitionResult) {
        return switch (intentRecognitionResult.intent()) {
            case "售前咨询与购买" -> CAR_BEFORE_SALES;
            case "投诉与维权" -> CAR_COMPLAINTS;
            case "售后维修与保养" -> CAR_MAINTENANCE;
            case "汽车营销政策" -> CAR_MARKETING;
            case "车辆使用与技术指导" -> CAR_TECH_SUPPORT;
            default -> CAR_OTHER;
        };
    }
}
