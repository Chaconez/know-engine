package com.chaconez.knowengine.business.service.impl;

import com.chaconez.knowengine.business.entity.UserInfo;
import com.chaconez.knowengine.business.mapper.UserInfoMapper;
import com.chaconez.knowengine.business.service.UserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 客户信息表 Service 实现类
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements UserInfoService {
}
