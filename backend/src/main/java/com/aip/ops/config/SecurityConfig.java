package com.aip.ops.config;

import com.aip.sdk.security.PlatformAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.*;

@Configuration @EnableWebSecurity @RequiredArgsConstructor
public class SecurityConfig {

    private final PlatformAuthenticationFilter platformAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> {}).csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/**", "/uploads/**",
                    "/api/public-account/**",
                    "/api/ops/auth/**", "/api/ops/product/**",
                    "/api/ops/activity/**",
                    "/api/ops/home-data",
                    "/api/ops/coupon/list",
                    "/api/ops/ai/config",
                    "/api/ops/live-rooms/**",
                    "/api/ops/admin/business-data/template"
                ).permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(exh -> exh
                .authenticationEntryPoint((req, res, ex) ->
                    writeJson(res, 401, "未登录或登录已过期"))
                .accessDeniedHandler((req, res, ex) ->
                    writeJson(res, 403, "无权限")))
            .addFilterBefore(platformAuthenticationFilter,
                    UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(Collections.singletonList("*"));
        cfg.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(Collections.singletonList("*"));
        cfg.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }

    private void writeJson(HttpServletResponse resp, int code, String msg) {
        try {
            resp.setStatus(code);
            resp.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            Map<String, Object> body = new HashMap<>();
            body.put("code", code); body.put("msg", msg); body.put("data", null);
            resp.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (Exception ignored) {}
    }
}
