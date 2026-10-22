package com.chaconez.knowengine.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 我的车辆信息实体类
 * 对应数据库表 my_car
 */
@Data
@TableName("my_car")
public class MyCar {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 车辆唯一标识
     */
    @TableField("car_id")
    private String carId;

    /**
     * 用户ID，标识车辆归属
     */
    @TableField("user_id")
    private String userId;

    /**
     * 关联的车型信息ID
     */
    @TableField("car_info_id")
    private String carInfoId;

    /**
     * 车辆昵称（车主自定义名称）
     */
    @TableField("nickname")
    private String nickname;

    /**
     * 车辆全称（如：Tesla Model 3 2025焕新版）
     */
    @TableField("full_name")
    private String fullName;

    /**
     * 车辆图片URL
     */
    @TableField("image_url")
    private String imageUrl;

    /**
     * 关联的购车订单ID
     */
    @TableField("order_id")
    private String orderId;

    /**
     * 车牌号
     */
    @TableField("plate_number")
    private String plateNumber;

    /**
     * 车辆颜色（具体颜色，如：珍珠白、深海蓝等）
     */
    @TableField("color")
    private String color;

    /**
     * 车辆识别代号(VIN码)
     */
    @TableField("vin")
    private String vin;

    /**
     * 发动机号
     */
    @TableField("engine_number")
    private String engineNumber;

    /**
     * 购买日期
     */
    @TableField("purchase_date")
    private LocalDate purchaseDate;

    /**
     * 购买价格
     */
    @TableField("purchase_price")
    private BigDecimal purchasePrice;

    /**
     * 行驶里程(公里)
     */
    @TableField("mileage")
    private Integer mileage;

    /**
     * 注册日期
     */
    @TableField("register_date")
    private LocalDate registerDate;

    /**
     * 保险到期日
     */
    @TableField("insurance_expire_date")
    private LocalDate insuranceExpireDate;

    /**
     * 年检到期日
     */
    @TableField("inspection_expire_date")
    private LocalDate inspectionExpireDate;

    /**
     * 备注
     */
    @TableField("remark")
    private String remark;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
