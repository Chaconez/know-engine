package com.chaconez.knowengine.business.mapper;

import com.chaconez.knowengine.business.entity.CarOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 车辆订单 Mapper 接口
 */
@Mapper
public interface CarOrderMapper extends BaseMapper<CarOrder> {
}
