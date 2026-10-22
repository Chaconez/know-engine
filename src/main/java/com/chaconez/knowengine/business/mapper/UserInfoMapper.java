package com.chaconez.knowengine.business.mapper;

import com.chaconez.knowengine.business.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户信息表 Mapper
 */
@Mapper
public interface UserInfoMapper extends BaseMapper<UserInfo> {
}
