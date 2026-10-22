package com.chaconez.knowengine.business.mapper;

import com.chaconez.knowengine.business.entity.MyCar;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 我的车辆信息 Mapper 接口
 */
@Mapper
public interface MyCarMapper extends BaseMapper<MyCar> {
}
