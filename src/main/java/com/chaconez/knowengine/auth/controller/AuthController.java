package com.chaconez.knowengine.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.chaconez.knowengine.auth.dto.LoginDTO;
import com.chaconez.knowengine.auth.dto.LoginUserVO;
import com.chaconez.knowengine.auth.dto.StaffLoginDTO;
import com.chaconez.knowengine.auth.service.AuthService;
import com.chaconez.knowengine.common.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * 登录
     */
    @PostMapping("/login")
    public R<LoginUserVO> login(@RequestBody LoginDTO loginDTO) {
        try {
            LoginUserVO user = authService.login(loginDTO);
            return R.ok(user, "登录成功");
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 员工登录（工号 + 姓名）
     */
    @PostMapping("/staffLogin")
    public R<LoginUserVO> staffLogin(@RequestBody StaffLoginDTO staffLoginDTO) {
        try {
            LoginUserVO user = authService.staffLogin(staffLoginDTO);
            return R.ok(user, "员工登录成功");
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 登出
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/userInfo")
    public R<LoginUserVO> userInfo() {
        try {
            return R.ok(authService.getCurrentUser());
        } catch (RuntimeException e) {
            return R.fail(401, e.getMessage());
        }
    }

    /**
     * 是否已登录
     */
    @GetMapping("/isLogin")
    public R<Boolean> isLogin() {
        return R.ok(StpUtil.isLogin());
    }
}
