package com.chaconez.knowengine.business.mapper;

import com.chaconez.knowengine.business.entity.CarInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 车型信息 Mapper 接口
 */
@Mapper
public interface CarInfoMapper extends BaseMapper<CarInfo> {
}
