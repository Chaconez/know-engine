package com.chaconez.knowengine.business.converter;

import com.chaconez.knowengine.business.entity.CarInfo;
import com.chaconez.knowengine.business.vo.CarInfoVO;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * CarInfo对象转换器
 * 使用MapStruct实现CarInfo与CarInfoVO之间的转换
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface CarInfoConverter {

    CarInfoConverter INSTANCE = Mappers.getMapper(CarInfoConverter.class);

    /**
     * CarInfo转CarInfoVO
     * 字段名称一致，自动映射
     */
    CarInfoVO toVO(CarInfo carInfo);

    /**
     * 批量转换
     */
    List<CarInfoVO> toVOList(List<CarInfo> carInfoList);
}
