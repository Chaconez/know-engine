package com.chaconez.knowengine.business.service.impl;

import com.chaconez.knowengine.business.entity.MyCar;
import com.chaconez.knowengine.business.mapper.MyCarMapper;
import com.chaconez.knowengine.business.service.MyCarService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 我的车辆信息服务实现类
 */
@Service
@Slf4j
public class MyCarServiceImpl extends ServiceImpl<MyCarMapper, MyCar> implements MyCarService {

    @Override
    public void saveCar(MyCar myCar) {
        // 生成车辆唯一标识
        if (myCar.getCarId() == null || myCar.getCarId().isEmpty()) {
            myCar.setCarId(UUID.randomUUID().toString().replace("-", ""));
        }
        myCar.setCreatedAt(LocalDateTime.now());
        myCar.setUpdatedAt(LocalDateTime.now());
        this.save(myCar);
        log.info("车辆信息已保存: carId={}, plateNumber={}", myCar.getCarId(), myCar.getPlateNumber());
    }

    @Override
    public MyCar getCarById(String carId) {
        QueryWrapper<MyCar> wrapper = new QueryWrapper<>();
        wrapper.eq("car_id", carId);
        return this.getOne(wrapper);
    }

    @Override
    public MyCar getCarByUser(String carId, String userId) {
        QueryWrapper<MyCar> wrapper = new QueryWrapper<>();
        wrapper.eq("car_id", carId);
        wrapper.eq("user_id", userId);
        return this.getOne(wrapper);
    }

    @Override
    public List<MyCar> getCarByUserId(String userId) {
        QueryWrapper<MyCar> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        return this.list(wrapper);
    }

    @Override
    public MyCar getCarByPlateNumber(String plateNumber) {
        QueryWrapper<MyCar> wrapper = new QueryWrapper<>();
        wrapper.eq("plate_number", plateNumber);
        return this.getOne(wrapper);
    }

    @Override
    public void updateCar(MyCar myCar) {
        myCar.setUpdatedAt(LocalDateTime.now());
        
        QueryWrapper<MyCar> wrapper = new QueryWrapper<>();
        wrapper.eq("car_id", myCar.getCarId());
        this.update(myCar, wrapper);
        log.info("车辆信息已更新: carId={}", myCar.getCarId());
    }

    @Override
    public void deleteCar(String carId) {
        QueryWrapper<MyCar> wrapper = new QueryWrapper<>();
        wrapper.eq("car_id", carId);
        this.remove(wrapper);
        log.info("车辆信息已删除: carId={}", carId);
    }

    @Override
    public boolean exists(String carId) {
        QueryWrapper<MyCar> wrapper = new QueryWrapper<>();
        wrapper.eq("car_id", carId);
        return this.count(wrapper) > 0;
    }

    @Override
    public List<MyCar> getAllCars() {
        return this.list();
    }

    @Override
    public List<MyCar> getCarsByStatus(String status) {
        QueryWrapper<MyCar> wrapper = new QueryWrapper<>();
        wrapper.eq("status", status);
        return this.list(wrapper);
    }

    @Override
    public int getCarCount() {
        return Math.toIntExact(this.count());
    }

    @Override
    public List<MyCar> getCarsByBrand(String brand) {
        QueryWrapper<MyCar> wrapper = new QueryWrapper<>();
        wrapper.like("brand", brand);
        return this.list(wrapper);
    }
}

