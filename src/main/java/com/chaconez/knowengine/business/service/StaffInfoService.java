package com.chaconez.knowengine.business.service;

import com.chaconez.knowengine.business.entity.StaffInfo;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 员工信息表 Service 接口
 */
public interface StaffInfoService extends IService<StaffInfo> {

    /**
     * 根据工号查询员工信息
     *
     * @param empId 工号
     * @return 员工信息，不存在返回 null
     */
    StaffInfo getByEmpId(String empId);
}
