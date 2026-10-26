package com.chaconez.knowengine.common;

import cn.dev33.satoken.exception.NotLoginException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 * <p>
 * 主要解决 Sa-Token 拦截器抛出的 {@link NotLoginException} 未被捕获的问题：
 * 此前未登录访问受保护接口会冒泡到 Servlet 容器，返回 Tomcat 原生 500 页面，
 * 前端无法据此判断"需要登录"，也污染了错误监控。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 未登录 / 登录态失效：返回 401，交由前端跳转登录页
     */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleNotLogin(NotLoginException e) {
        log.debug("未登录访问受保护资源: type={}, message={}", e.getType(), e.getMessage());
        return R.fail(HttpStatus.UNAUTHORIZED.value(), "未登录或登录已过期");
    }
}
