package com.aip.ops.service.impl;

import com.aip.ops.dto.LoginRequest;
import com.aip.ops.dto.RegisterRequest;
import com.aip.ops.entity.GsUser;
import com.aip.ops.mapper.GsUserMapper;
import com.aip.ops.service.IUserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MacSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements IUserService {

    private final GsUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${platform.jwt-secret}")
    private String jwtSecret;

    @Value("${platform.jwt-expiration:43200}")
    private long jwtExpiration;

    // ── 手机号校验 ──
    private static final java.util.regex.Pattern PHONE_PATTERN =
            java.util.regex.Pattern.compile("^1[3-9]\\d{9}$");

    @Override
    public Map<String, Object> register(RegisterRequest req) {
        String phone = req.getPhone();
        String password = req.getPassword();
        String confirm = req.getConfirmPassword();

        // 参数校验
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches())
            throw new IllegalArgumentException("请输入正确的手机号");
        if (password == null || password.length() < 6)
            throw new IllegalArgumentException("密码长度不能少于6位");
        if (!password.equals(confirm))
            throw new IllegalArgumentException("两次输入的密码不一致");

        // 检查手机号是否已注册
        Long corpId = req.getCorpId() != null ? req.getCorpId() : 4L;
        GsUser exist = userMapper.selectOne(new LambdaQueryWrapper<GsUser>()
                .eq(GsUser::getPhone, phone)
                .eq(GsUser::getCorpId, corpId));
        if (exist != null && exist.getPassword() != null)
            throw new IllegalArgumentException("该手机号已注册，请直接登录");

        // 创建用户
        GsUser user;
        if (exist != null) {
            // 老用户（无密码的），补设密码
            user = exist;
        } else {
            user = new GsUser();
            user.setPhone(phone);
            user.setCorpId(corpId);
            user.setNickname(phone.substring(0, 3) + "****" + phone.substring(7));
            user.setPointsBalance(200);
            user.setStatus(1);
        }
        user.setPassword(passwordEncoder.encode(password));
        user.setLastLoginTime(LocalDateTime.now());

        if (exist != null) {
            userMapper.updateById(user);
        } else {
            userMapper.insert(user);
        }

        log.info("用户注册成功: phone={}, userId={}, corpId={}", phone, user.getId(), corpId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getId());
        result.put("phone", user.getPhone());
        result.put("nickname", user.getNickname());
        return result;
    }

    @Override
    public Map<String, Object> login(LoginRequest req) {
        String phone = req.getPhone();
        String password = req.getPassword();
        Long corpId = req.getCorpId() != null ? req.getCorpId() : 4L;

        if (phone == null || password == null || password.isEmpty())
            throw new IllegalArgumentException("手机号或密码不能为空");

        GsUser user = userMapper.selectOne(new LambdaQueryWrapper<GsUser>()
                .eq(GsUser::getPhone, phone)
                .eq(GsUser::getCorpId, corpId));

        if (user == null || user.getPassword() == null
                || !passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("手机号或密码错误");
        }

        if (user.getStatus() == null || user.getStatus() != 1)
            throw new IllegalArgumentException("账号已被禁用，请联系管理员");

        // 更新登录时间
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        // 生成 JWT — 与 SDK PlatformAuthenticationFilter 兼容
        String token = generateJwt(user);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getId());
        result.put("phone", user.getPhone());
        result.put("nickname", user.getNickname());
        result.put("avatar", user.getAvatar());
        result.put("pointsBalance", user.getPointsBalance());
        result.put("token", token);
        result.put("corpId", user.getCorpId());
        result.put("expiresIn", jwtExpiration);

        log.info("用户登录成功: phone={}, userId={}", phone, user.getId());
        return result;
    }

    private String generateJwt(GsUser user) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(String.valueOf(user.getId()))
                    .claim("corp_id", user.getCorpId())
                    .claim("username", user.getPhone())
                    .claim("is_admin", false)
                    .claim("permissions", Collections.emptyList())
                    .issuer("ops-platform")
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + jwtExpiration * 1000))
                    .build();

            JWSSigner signer = new MacSigner(jwtSecret.getBytes(StandardCharsets.UTF_8));
            JWSObject jws = new JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                    new Payload(claims.toJSONObject()));
            jws.sign(signer);
            return jws.serialize();
        } catch (Exception e) {
            throw new RuntimeException("JWT生成失败", e);
        }
    }
}
