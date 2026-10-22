package com.chaconez.knowengine.business.service.impl;

import com.chaconez.knowengine.business.entity.CarInfo;
import com.chaconez.knowengine.business.mapper.CarInfoMapper;
import com.chaconez.knowengine.business.service.CarInfoService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 车型信息服务实现类
 */
@Service
@Slf4j
public class CarInfoServiceImpl extends ServiceImpl<CarInfoMapper, CarInfo> implements CarInfoService {

    @Override
    public void saveCarInfo(CarInfo carInfo) {
        // 生成车型唯一标识
        if (carInfo.getInfoId() == null || carInfo.getInfoId().isEmpty()) {
            carInfo.setInfoId(UUID.randomUUID().toString().replace("-", ""));
        }
        carInfo.setCreatedAt(LocalDateTime.now());
        carInfo.setUpdatedAt(LocalDateTime.now());
        this.save(carInfo);
        log.info("车型信息已保存: infoId={}, fullName={}", carInfo.getInfoId(), carInfo.getFullName());
    }

    @Override
    public CarInfo getCarInfoById(String infoId) {
        QueryWrapper<CarInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("info_id", infoId);
        return this.getOne(wrapper);
    }

    @Override
    public List<CarInfo> getCarInfoByBrand(String brand) {
        QueryWrapper<CarInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("brand", brand);
        return this.list(wrapper);
    }

    @Override
    public List<CarInfo> getCarInfoByModel(String brand, String model) {
        QueryWrapper<CarInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("brand", brand);
        wrapper.eq("model_name", model);
        return this.list(wrapper);
    }

    @Override
    public List<CarInfo> getCarInfoByVehicleType(String vehicleType) {
        QueryWrapper<CarInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("vehicle_type", vehicleType);
        return this.list(wrapper);
    }

    @Override
    public List<CarInfo> getCarInfoByFuelType(String fuelType) {
        QueryWrapper<CarInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("fuel_type", fuelType);
        return this.list(wrapper);
    }

    @Override
    public List<CarInfo> searchByFullName(String keyword) {
        QueryWrapper<CarInfo> wrapper = new QueryWrapper<>();
        wrapper.like("full_name", keyword);
        return this.list(wrapper);
    }

    @Override
    public void updateCarInfo(CarInfo carInfo) {
        carInfo.setUpdatedAt(LocalDateTime.now());
        
        QueryWrapper<CarInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("info_id", carInfo.getInfoId());
        this.update(carInfo, wrapper);
        log.info("车型信息已更新: infoId={}", carInfo.getInfoId());
    }

    @Override
    public List<CarInfo> getAllCarInfo() {
        return this.list();
    }

    @Override
    public List<CarInfo> getCarInfoByStatus(String status) {
        QueryWrapper<CarInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("status", status);
        return this.list(wrapper);
    }

    @Override
    public int getCarInfoCount() {
        return Math.toIntExact(this.count());
    }
}

