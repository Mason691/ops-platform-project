package com.aip.ops.security;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;

/**
 * 读取当前请求 JWT 的 {@code token_type} claim。
 *
 * <p>平台两种 JWT 共用同一签名密钥、且 subject 语义不同：
 * 员工 JWT（{@code JwtUtils.generateToken}）sub=sys_user.id，无 token_type；
 * 会员 SSO JWT（{@code JwtUtils.generateSsoToken}）sub=sys_member.id，
 * token_type=sso。两套自增 ID 可能撞号，员工专用接口若只按 id 判断，
 * 会员令牌有机会被误认为员工。此工具在签名校验通过后读取 token_type，
 * 供员工接口显式拒绝 SSO 令牌。</p>
 */
@Slf4j
@Component
public class JwtTokenInspector {

    private final byte[] secretBytes;

    public JwtTokenInspector(@Value("${platform.jwt-secret}") String jwtSecret) {
        this.secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

    /** 当前请求的 token_type；无该 claim（员工 JWT）或解析失败返回 null。 */
    public String currentTokenType() {
        String header = currentAuthorizationHeader();
        if (header == null) return null;
        try {
            SignedJWT jwt = SignedJWT.parse(header.substring(7));
            if (!jwt.verify(new MACVerifier(secretBytes))) {
                return null;
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            return claims.getStringClaim("token_type");
        } catch (Exception e) {
            log.debug("解析 token_type 失败: {}", e.getMessage());
            return null;
        }
    }

    private String currentAuthorizationHeader() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        String header = attrs.getRequest().getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header : null;
    }
}
