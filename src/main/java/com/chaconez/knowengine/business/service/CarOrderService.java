package com.chaconez.knowengine.business.service;


import com.chaconez.knowengine.business.entity.CarOrder;

import java.util.List;

/**
 * 车辆订单服务接口
 */
public interface CarOrderService {

    /**
     * 保存订单
     */
    void saveOrder(CarOrder order);

    /**
     * 根据订单ID获取订单
     */
    CarOrder getOrderById(String orderId);

    /**
     * 根据订单编号获取订单
     */
    CarOrder getOrderByOrderNo(String orderNo);

    /**
     * 根据用户ID获取订单列表
     */
    List<CarOrder> getOrdersByUserId(String userId);

    /**
     * 根据车辆ID获取订单
     */
    CarOrder getOrderByCarId(String carId);

    /**
     * 根据订单状态获取订单列表
     */
    List<CarOrder> getOrdersByStatus(String orderStatus);

    /**
     * 根据订单类型获取订单列表
     */
    List<CarOrder> getOrdersByType(String orderType);

    /**
     * 更新订单信息
     */
    void updateOrder(CarOrder order);

    /**
     * 删除订单
     */
    void deleteOrder(String orderId);

    /**
     * 检查订单是否存在
     */
    boolean exists(String orderId);

    /**
     * 获取所有订单列表
     */
    List<CarOrder> getAllOrders();

    /**
     * 获取订单数量
     */
    int getOrderCount();
}
