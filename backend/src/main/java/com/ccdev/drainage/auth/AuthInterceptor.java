package com.ccdev.drainage.auth;

import com.ccdev.drainage.domain.Models.AppUser;
import com.ccdev.drainage.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 令牌拦截：除 /api/auth/login 外，所有 /api/** 必须带有效 X-Auth-Token。
 * 当前用户写入 request 属性，供控制器取用。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String CURRENT_USER = "currentUser";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = request.getHeader("X-Auth-Token");
        AppUser user = authService.requireUser(token);
        request.setAttribute(CURRENT_USER, user);
        return true;
    }
}
