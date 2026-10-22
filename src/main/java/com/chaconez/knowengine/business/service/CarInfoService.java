package com.chaconez.knowengine.business.service;


import com.chaconez.knowengine.business.entity.CarInfo;

import java.util.List;

/**
 * 车型信息服务接口
 */
public interface CarInfoService {

    /**
     * 保存车型信息
     */
    void saveCarInfo(CarInfo carInfo);

    /**
     * 根据车型ID获取车型信息
     */
    CarInfo getCarInfoById(String infoId);

    /**
     * 根据品牌获取车型列表
     */
    List<CarInfo> getCarInfoByBrand(String brand);

    /**
     * 根据型号获取车型列表
     */
    List<CarInfo> getCarInfoByModel(String brand, String model);

    /**
     * 根据车辆类型获取车型列表
     */
    List<CarInfo> getCarInfoByVehicleType(String vehicleType);

    /**
     * 根据燃油类型获取车型列表
     */
    List<CarInfo> getCarInfoByFuelType(String fuelType);

    /**
     * 根据全称模糊查询车型
     */
    List<CarInfo> searchByFullName(String keyword);

    /**
     * 更新车型信息
     */
    void updateCarInfo(CarInfo carInfo);


    /**
     * 获取所有车型列表
     */
    List<CarInfo> getAllCarInfo();

    /**
     * 根据状态获取车型列表
     */
    List<CarInfo> getCarInfoByStatus(String status);

    /**
     * 获取车型数量
     */
    int getCarInfoCount();
}
