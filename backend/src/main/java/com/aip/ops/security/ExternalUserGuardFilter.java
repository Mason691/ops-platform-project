package com.aip.ops.security;

import com.aip.sdk.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 外部用户路径守卫：员工工作台之外的 ops 接口一律 403。
 *
 * <p>平台侧已在登录/PC 过滤器拦截外部用户；但 ops 后端同时承载 PC 运营后台，
 * 若只依赖 JWT 验签，外部用户可用员工令牌直调 /api/ops/admin/**。此过滤器在
 * PlatformAuthenticationFilter 之后按 userType 做第二道边界。</p>
 */
@Component
@RequiredArgsConstructor
public class ExternalUserGuardFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityUtils.isExternalUser() && !isAllowed(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    Map.of("code", 403, "msg", com.aip.common.exception.UserTypeError
                            .EXTERNAL_USER_PC_API_FORBIDDEN.getMessage(), "data", null)));
            return;
        }
        chain.doFilter(request, response);
    }

    /** 只放行员工工作台、公开只读接口与静态资源。 */
    private boolean isAllowed(String uri) {
        return uri == null
                || uri.startsWith("/api/ops/employee/")
                || uri.startsWith("/api/ops/auth/employee/")
                || uri.startsWith("/api/ops/ai/employee/")
                || uri.startsWith("/api/ops/ai/config")
                || uri.startsWith("/api/ops/product/")
                || uri.startsWith("/api/ops/activity/")
                || uri.equals("/api/ops/home-data")
                || uri.startsWith("/api/ops/coupon/list")
                || uri.startsWith("/api/ops/live-rooms/")
                || uri.startsWith("/api/public-account/")
                || uri.startsWith("/uploads/")
                || uri.startsWith("/actuator/");
    }
}
