package com.chaconez.knowengine.business.constant;

public enum StaffStatus {

    /**
     * 在职
     */
    ON_JOB("ON_JOB", "在职"),
    /**
     * 离职
     */
    OFF_JOB("OFF_JOB", "离职");

    private String code;
    private String desc;

    StaffStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
