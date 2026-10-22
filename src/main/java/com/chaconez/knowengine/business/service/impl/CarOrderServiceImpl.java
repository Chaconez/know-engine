package com.chaconez.knowengine.business.service.impl;

import com.chaconez.knowengine.business.entity.CarOrder;
import com.chaconez.knowengine.business.mapper.CarOrderMapper;
import com.chaconez.knowengine.business.service.CarOrderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 车辆订单服务实现类
 */
@Service
@Slf4j
public class CarOrderServiceImpl extends ServiceImpl<CarOrderMapper, CarOrder> implements CarOrderService {

    @Override
    public void saveOrder(CarOrder order) {
        // 生成订单唯一标识
        if (order.getOrderId() == null || order.getOrderId().isEmpty()) {
            order.setOrderId(UUID.randomUUID().toString().replace("-", ""));
        }
        // 生成订单编号
        if (order.getOrderNo() == null || order.getOrderNo().isEmpty()) {
            order.setOrderNo(generateOrderNo());
        }
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        this.save(order);
        log.info("订单已保存: orderId={}, orderNo={}", order.getOrderId(), order.getOrderNo());
    }

    @Override
    public CarOrder getOrderById(String orderId) {
        QueryWrapper<CarOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("order_id", orderId);
        return this.getOne(wrapper);
    }

    @Override
    public CarOrder getOrderByOrderNo(String orderNo) {
        QueryWrapper<CarOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("order_no", orderNo);
        return this.getOne(wrapper);
    }

    @Override
    public List<CarOrder> getOrdersByUserId(String userId) {
        QueryWrapper<CarOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        return this.list(wrapper);
    }

    @Override
    public CarOrder getOrderByCarId(String carId) {
        QueryWrapper<CarOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("car_id", carId);
        return this.getOne(wrapper);
    }

    @Override
    public List<CarOrder> getOrdersByStatus(String orderStatus) {
        QueryWrapper<CarOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("order_status", orderStatus);
        return this.list(wrapper);
    }

    @Override
    public List<CarOrder> getOrdersByType(String orderType) {
        QueryWrapper<CarOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("order_type", orderType);
        return this.list(wrapper);
    }

    @Override
    public void updateOrder(CarOrder order) {
        order.setUpdatedAt(LocalDateTime.now());
        
        QueryWrapper<CarOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("order_id", order.getOrderId());
        this.update(order, wrapper);
        log.info("订单已更新: orderId={}", order.getOrderId());
    }

    @Override
    public void deleteOrder(String orderId) {
        QueryWrapper<CarOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("order_id", orderId);
        this.remove(wrapper);
        log.info("订单已删除: orderId={}", orderId);
    }

    @Override
    public boolean exists(String orderId) {
        QueryWrapper<CarOrder> wrapper = new QueryWrapper<>();
        wrapper.eq("order_id", orderId);
        return this.count(wrapper) > 0;
    }

    @Override
    public List<CarOrder> getAllOrders() {
        return this.list();
    }

    @Override
    public int getOrderCount() {
        return Math.toIntExact(this.count());
    }

    /**
     * 生成订单编号
     * 格式: ORD + 年月日 + 6位随机数
     */
    private String generateOrderNo() {
        String dateStr = java.time.LocalDate.now().toString().replace("-", "");
        String randomStr = String.format("%06d", (int) (Math.random() * 1000000));
        return "ORD" + dateStr + randomStr;
    }
}

