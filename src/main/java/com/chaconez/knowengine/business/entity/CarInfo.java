package com.chaconez.knowengine.business.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车型信息实体类
 * 对应数据库表 car_info
 * 用于记录车型的相关信息，如：Tesla Model 3 2025焕新版
 */
@Data
@TableName("car_info")
public class CarInfo {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 车型唯一标识
     */
    @TableField("info_id")
    private String infoId;

    /**
     * 品牌（如：Tesla、比亚迪、宝马等）
     */
    @TableField("brand")
    private String brand;

    /**
     * 型号名称（如：Model 3、汉、3系等）
     */
    @TableField("model_name")
    private String modelName;

    /**
     * 年款（如：2025、2024等）
     */
    @TableField("model_year")
    private Integer modelYear;

    /**
     * 版本描述（如：焕新版、长续航版、运动版等）
     */
    @TableField("version")
    private String version;

    /**
     * 全称（如：Tesla Model 3 2025焕新版）
     */
    @TableField("full_name")
    private String fullName;

    /**
     * 车辆类型：轿车/SUV/MPV/跑车/皮卡等
     */
    @TableField("vehicle_type")
    private String vehicleType;

    /**
     * 燃油类型：汽油/柴油/电动/混动/氢能源等
     */
    @TableField("fuel_type")
    private String fuelType;

    /**
     * 座位数
     */
    @TableField("seat_count")
    private Integer seatCount;

    /**
     * 排量（L），燃油车使用
     */
    @TableField("displacement")
    private BigDecimal displacement;

    /**
     * 电机功率（kW），电动车使用
     */
    @TableField("motor_power")
    private BigDecimal motorPower;

    /**
     * 续航里程（km），电动车使用
     */
    @TableField("range_km")
    private Integer rangeKm;

    /**
     * 官方指导价（万元）
     */
    @TableField("guide_price")
    private BigDecimal guidePrice;

    /**
     * 车身颜色选项（多个颜色用逗号分隔）
     */
    @TableField("color_options")
    private String colorOptions;

    /**
     * 车身尺寸（长x宽x高，单位mm）
     */
    @TableField("dimensions")
    private String dimensions;

    /**
     * 轴距（mm）
     */
    @TableField("wheelbase")
    private Integer wheelbase;

    /**
     * 生产厂商
     */
    @TableField("manufacturer")
    private String manufacturer;

    /**
     * 车型状态：在售/停售/即将上市
     */
    @TableField("status")
    private String status;

    /**
     * 车型图片URL
     */
    @TableField("image_url")
    private String imageUrl;

    /**
     * 车型描述
     */
    @TableField("description")
    private String description;

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
