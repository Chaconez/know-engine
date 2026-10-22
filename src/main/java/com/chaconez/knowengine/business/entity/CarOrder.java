package com.chaconez.knowengine.business.entity;

import com.chaconez.knowengine.business.constant.CarOrderStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 车辆订单实体类
 * 对应数据库表 car_order
 */
@Data
@TableName("car_order")
public class CarOrder {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 订单唯一标识
     */
    @TableField("order_id")
    private String orderId;

    /**
     * 用户ID，标识订单归属
     */
    @TableField("user_id")
    private String userId;

    /**
     * 关联的车辆ID
     */
    @TableField("car_id")
    private String carId;

    /**
     * 订单编号
     */
    @TableField("order_no")
    private String orderNo;

    /**
     * 订单类型：购买/出售/置换
     */
    @TableField("order_type")
    private String orderType;

    /**
     * 订单状态：待支付/已支付/已完成/已取消
     */
    @TableField("order_status")
    private CarOrderStatus orderStatus;

    /**
     * 车辆品牌
     */
    @TableField("brand")
    private String brand;

    /**
     * 车辆型号
     */
    @TableField("model")
    private String model;

    /**
     * 车辆颜色
     */
    @TableField("color")
    private String color;

    /**
     * 车辆VIN码
     */
    @TableField("vin")
    private String vin;

    /**
     * 卖家/经销商名称
     */
    @TableField("seller_name")
    private String sellerName;

    /**
     * 卖家/经销商联系方式
     */
    @TableField("seller_contact")
    private String sellerContact;

    /**
     * 车辆价格
     */
    @TableField("vehicle_price")
    private BigDecimal vehiclePrice;

    /**
     * 购置税
     */
    @TableField("purchase_tax")
    private BigDecimal purchaseTax;

    /**
     * 保险费用
     */
    @TableField("insurance_fee")
    private BigDecimal insuranceFee;

    /**
     * 其他费用
     */
    @TableField("other_fee")
    private BigDecimal otherFee;

    /**
     * 订单总金额
     */
    @TableField("total_amount")
    private BigDecimal totalAmount;

    /**
     * 优惠金额
     */
    @TableField("discount_amount")
    private BigDecimal discountAmount;

    /**
     * 实际支付金额
     */
    @TableField("actual_amount")
    private BigDecimal actualAmount;

    /**
     * 付款方式：全款/分期
     */
    @TableField("payment_method")
    private String paymentMethod;

    /**
     * 订单日期
     */
    @TableField("order_date")
    private LocalDate orderDate;

    /**
     * 交付日期
     */
    @TableField("delivery_date")
    private LocalDate deliveryDate;

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
