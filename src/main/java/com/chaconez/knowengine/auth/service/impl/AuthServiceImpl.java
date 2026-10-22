package com.chaconez.knowengine.auth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.chaconez.knowengine.auth.dto.LoginDTO;
import com.chaconez.knowengine.auth.dto.LoginUserVO;
import com.chaconez.knowengine.auth.dto.StaffLoginDTO;
import com.chaconez.knowengine.auth.service.AuthService;
import com.chaconez.knowengine.business.constant.StaffStatus;
import com.chaconez.knowengine.business.entity.StaffInfo;
import com.chaconez.knowengine.business.entity.UserInfo;
import com.chaconez.knowengine.business.service.StaffInfoService;
import com.chaconez.knowengine.business.service.UserInfoService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现（网页端客户登录）
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STAFF_LOGIN_PREFIX = "staff_";
    private static final String STAFF_DEVICE = "staff";

    @Autowired
    private UserInfoService userInfoService;

    @Autowired
    private StaffInfoService staffInfoService;

    @Override
    public LoginUserVO login(LoginDTO loginDTO) {
        if (loginDTO.getPhone() == null || loginDTO.getPhone().isBlank()) {
            throw new RuntimeException("手机号不能为空");
        }
        if (loginDTO.getPassword() == null || loginDTO.getPassword().isBlank()) {
            throw new RuntimeException("密码不能为空");
        }

        // 根据手机号查询客户
        UserInfo userInfo = userInfoService.getOne(
                new LambdaQueryWrapper<UserInfo>().eq(UserInfo::getPhone, loginDTO.getPhone()));
        if (userInfo == null) {
            throw new RuntimeException("手机号不存在");
        }

        // 校验密码（明文比对，生产环境建议使用 BCrypt 等加密算法）
        if (!loginDTO.getPassword().equals(userInfo.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        // 校验账号状态
        if (!STATUS_ACTIVE.equals(userInfo.getStatus())) {
            throw new RuntimeException("该账号已被冻结，请联系客服");
        }

        // 使用客户主键 id 作为 sa-token 的登录主键
        StpUtil.login(userInfo.getId());

        log.info("客户登录成功: phone={}, name={}, loginId={}", userInfo.getPhone(), userInfo.getName(), userInfo.getId());

        return toLoginUserVO(userInfo);
    }

    @Override
    public LoginUserVO staffLogin(StaffLoginDTO dto) {
        if (dto.getEmpId() == null || dto.getEmpId().isBlank()) {
            throw new RuntimeException("工号不能为空");
        }
        if (dto.getPassword() == null || dto.getPassword().isBlank()) {
            throw new RuntimeException("密码不能为空");
        }

        StaffInfo staffInfo = staffInfoService.getOne(
                new LambdaQueryWrapper<StaffInfo>().eq(StaffInfo::getEmpId, dto.getEmpId()));
        if (staffInfo == null) {
            throw new RuntimeException("工号不存在");
        }

        // 校验密码（明文比对，生产环境建议使用 BCrypt 等加密算法）
        if (staffInfo.getPassword() == null || !staffInfo.getPassword().equals(dto.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        if (staffInfo.getStatus() != StaffStatus.ON_JOB) {
            throw new RuntimeException("该员工已离职，无法登录");
        }

        // 员工登录：使用 staff_ 前缀 + 工号(empId) 作为登录主键，设备标识为 "staff"
        StpUtil.login(STAFF_LOGIN_PREFIX + staffInfo.getEmpId(), STAFF_DEVICE);

        log.info("员工登录成功: empId={}, name={}, loginId={}", staffInfo.getEmpId(), staffInfo.getName(), STAFF_LOGIN_PREFIX + staffInfo.getEmpId());

        return toStaffLoginUserVO(staffInfo);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    @Override
    public LoginUserVO getCurrentUser() {
        String loginId = getCurrentUserId();
        if (loginId.startsWith(STAFF_LOGIN_PREFIX)) {
            String empId = loginId.substring(STAFF_LOGIN_PREFIX.length());
            StaffInfo staffInfo = staffInfoService.getByEmpId(empId);
            if (staffInfo == null) {
                throw new RuntimeException("员工信息不存在");
            }
            return toStaffLoginUserVO(staffInfo);
        }
        UserInfo userInfo = userInfoService.getById(loginId);
        if (userInfo == null) {
            throw new RuntimeException("用户信息不存在");
        }
        return toLoginUserVO(userInfo);
    }

    @Override
    public String getCurrentUserId() {
        if (!StpUtil.isLogin()) {
            throw new RuntimeException("未登录");
        }
        return StpUtil.getLoginIdAsString();
    }

    @Override
    public boolean isStaffLogin() {
        if (!StpUtil.isLogin()) {
            return false;
        }
        return StpUtil.getLoginIdAsString().startsWith(STAFF_LOGIN_PREFIX);
    }

    private LoginUserVO toLoginUserVO(UserInfo userInfo) {
        LoginUserVO vo = new LoginUserVO();
        BeanUtils.copyProperties(userInfo, vo);
        vo.setUserType("user");
        return vo;
    }

    private LoginUserVO toStaffLoginUserVO(StaffInfo staffInfo) {
        LoginUserVO vo = new LoginUserVO();
        vo.setId(staffInfo.getId());
        vo.setName(staffInfo.getName());
        vo.setAvatar(staffInfo.getPicUrl());
        vo.setStatus(staffInfo.getStatus() != null ? staffInfo.getStatus().getCode() : null);
        vo.setUserType("staff");
        return vo;
    }
}
