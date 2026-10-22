package com.chaconez.knowengine.business.service;



import com.chaconez.knowengine.business.entity.MyCar;

import java.util.List;

/**
 * 我的车辆信息服务接口
 */
public interface MyCarService {

    /**
     * 保存车辆信息
     */
    void saveCar(MyCar myCar);

    /**
     * 根据车辆ID获取车辆信息
     */
    MyCar getCarById(String carId);

    /**
     * 根据车辆ID和用户ID获取车辆信息
     * @param carId
     * @param userId
     * @return
     */
    MyCar getCarByUser(String carId,String userId);

    /**
     * 根据用户ID获取车辆信息
     * @param userId
     * @return
     */
    List<MyCar> getCarByUserId(String userId);

    /**
     * 根据车牌号获取车辆信息
     */
    MyCar getCarByPlateNumber(String plateNumber);

    /**
     * 更新车辆信息
     */
    void updateCar(MyCar myCar);

    /**
     * 删除车辆信息
     */
    void deleteCar(String carId);

    /**
     * 检查车辆是否存在
     */
    boolean exists(String carId);

    /**
     * 获取所有车辆列表
     */
    List<MyCar> getAllCars();

    /**
     * 根据状态获取车辆列表
     */
    List<MyCar> getCarsByStatus(String status);

    /**
     * 获取车辆数量
     */
    int getCarCount();

    /**
     * 根据品牌查询车辆
     */
    List<MyCar> getCarsByBrand(String brand);
}
