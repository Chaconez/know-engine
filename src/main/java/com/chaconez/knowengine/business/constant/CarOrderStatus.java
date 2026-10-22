package com.chaconez.knowengine.business.constant;

public enum CarOrderStatus {
    /**
     * 等待支付
     */
    WAITING_PAYMENT("等待支付"),
    /**
     * 已支付
     */
    PAID("已支付"),
    /**
     * 已完成
     */
    COMPLETED("已完成"),
    /**
     * 已取消
     */
    CANCELLED("已取消");

    private String status;

    CarOrderStatus(String status) {
        this.status = status;
    }

    public String getStatus() {
        return status;
    }
}
