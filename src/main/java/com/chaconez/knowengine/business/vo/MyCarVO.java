package com.chaconez.knowengine.business.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 我的车辆信息VO
 * 用于返回给前端展示
 */
@Data
@Builder
public class MyCarVO {

    /**
     * 车辆唯一标识
     */
    private String carId;

    /**
     * 车辆昵称（车主自定义名称）
     */
    private String nickname;

    /**
     * 车型全称（如：Tesla Model 3 2025焕新版）
     */
    private String fullName;

    /**
     * 车牌号
     */
    private String plateNumber;

    /**
     * 车辆颜色
     */
    private String color;

    /**
     * 行驶里程(公里)
     */
    private Integer mileage;

    /**
     * 购买日期
     */
    private LocalDate purchaseDate;

    /**
     * 购买价格
     */
    private BigDecimal purchasePrice;

    /**
     * 保险到期日
     */
    private LocalDate insuranceExpireDate;

    /**
     * 年检到期日
     */
    private LocalDate inspectionExpireDate;

    /**
     * 车辆图片URL
     */
    private String imageUrl;
}
