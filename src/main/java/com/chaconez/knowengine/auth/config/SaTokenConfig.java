package com.chaconez.knowengine.auth.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * Sa-Token 配置：注册全局拦截器，对除登录、静态资源外的所有接口进行登录校验
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            "html", "js", "css", "png", "jpg", "jpeg", "gif", "svg",
            "ico", "woff", "woff2", "ttf"
    );

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            // 全局登录校验（排除无需登录的路径和静态资源）
            SaRouter.match("/**")
                    .notMatch("/auth/**")
                    .notMatch("/dingtalk/**")
                    .notMatch("/druid/**")
                    .check(r -> {
                        if (isStaticResource()) return;
                        StpUtil.checkLogin();
                    });

            // 文档及问答缓存管理路由需要员工登录（通过 loginId 前缀识别员工身份）
            SaRouter.match("/api/document/**", "/api/segment/**", "/api/qa-cache/**")
                    .check(r -> {
                        StpUtil.checkLogin();
                        String loginId = StpUtil.getLoginIdAsString();
                        if (!loginId.startsWith("staff_")) {
                            throw new NotLoginException("需要员工登录", NotLoginException.NOT_TOKEN, null);
                        }
                    });
        })).addPathPatterns("/**");
    }

    private boolean isStaticResource() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return false;
        HttpServletRequest request = attrs.getRequest();
        String uri = request.getRequestURI();
        int dotIndex = uri.lastIndexOf('.');
        if (dotIndex == -1) return false;
        String ext = uri.substring(dotIndex + 1).toLowerCase();
        return STATIC_EXTENSIONS.contains(ext);
    }
}
