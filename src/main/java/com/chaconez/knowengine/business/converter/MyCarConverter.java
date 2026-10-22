package com.chaconez.knowengine.business.converter;

import com.chaconez.knowengine.business.entity.MyCar;
import com.chaconez.knowengine.business.vo.MyCarVO;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * MyCar对象转换器
 * 使用MapStruct实现MyCar与MyCarVO之间的转换
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MyCarConverter {

    MyCarConverter INSTANCE = Mappers.getMapper(MyCarConverter.class);

    /**
     * MyCar转MyCarVO
     * 字段名称一致，自动映射
     */
    MyCarVO toVO(MyCar myCar);

    /**
     * 批量转换
     */
    List<MyCarVO> toVOList(List<MyCar> myCars);
}
