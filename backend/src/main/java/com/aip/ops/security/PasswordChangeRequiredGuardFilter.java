package com.aip.ops.security;

import com.aip.sdk.security.LoginUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 初始密码守卫：JWT 带 {@code pwd_change_required=true} 时，除改密与登录外一律拦截。
 *
 * <p>平台员工登录已在响应中返回 forcePasswordChange，前端会跳转改密页；
 * 本过滤器防止用户绕过前端直接用旧令牌调用工作台业务接口。</p>
 */
@Component
@RequiredArgsConstructor
public class PasswordChangeRequiredGuardFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        LoginUser user = currentUser();
        if (user != null && user.isPwdChangeRequired()
                && !isAllowed(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    Map.of("code", 4030, "msg", "请先修改初始密码", "data", null)));
            return;
        }
        chain.doFilter(request, response);
    }

    private LoginUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginUser user ? user : null;
    }

    /** 只放行改密、登录/验证码、公开只读与静态资源；刷新令牌不放行，避免绕过改密。 */
    private boolean isAllowed(String uri) {
        return uri == null
                || uri.equals("/api/ops/auth/employee/captcha")
                || uri.equals("/api/ops/auth/employee/login")
                || uri.startsWith("/api/ops/auth/employee/wechat/")
                || uri.equals("/api/ops/employee/change-password")
                || uri.equals("/api/ops/employee/profile")
                || uri.startsWith("/api/public-account/")
                || uri.startsWith("/uploads/")
                || uri.startsWith("/actuator/");
    }
}
