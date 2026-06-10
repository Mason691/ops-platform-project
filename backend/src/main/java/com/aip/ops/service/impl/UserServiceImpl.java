package com.aip.ops.service.impl;

import com.aip.ops.client.MemberSyncClient;
import com.aip.ops.dto.LoginRequest;
import com.aip.ops.dto.RegisterRequest;
import com.aip.ops.entity.GsUser;
import com.aip.ops.mapper.GsUserMapper;
import com.aip.ops.service.IUserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class UserServiceImpl implements IUserService {

    private final GsUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final MemberSyncClient memberSyncClient;

    private final String jwtSecret;
    private final long jwtExpiration;
    private final boolean phoneMockEnabled;
    private final String phoneMockPhone;

    private static final java.util.regex.Pattern PHONE_PATTERN =
            java.util.regex.Pattern.compile("^1[3-9]\\d{9}$");

    public UserServiceImpl(
            GsUserMapper userMapper,
            PasswordEncoder passwordEncoder,
            MemberSyncClient memberSyncClient,
            @Value("${platform.jwt-secret}") String jwtSecret,
            @Value("${platform.jwt-expiration:43200}") long jwtExpiration,
            @Value("${wechat.phone-mock.enabled:false}") boolean phoneMockEnabled,
            @Value("${wechat.phone-mock.phone:13800138000}") String phoneMockPhone) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.memberSyncClient = memberSyncClient;
        this.jwtSecret = jwtSecret;
        this.jwtExpiration = jwtExpiration;
        this.phoneMockEnabled = phoneMockEnabled;
        this.phoneMockPhone = phoneMockPhone;
    }

    // ═══ 注册 ═══

    @Override
    public Map<String, Object> register(RegisterRequest req) {
        String password = req.getPassword();
        String confirm  = req.getConfirmPassword();
        String nickname = req.getNickname();
        Long corpId = req.getCorpId() != null ? req.getCorpId() : 4L;

        // ── 密码校验 ──
        if (password == null || password.length() < 6)
            throw new IllegalArgumentException("密码长度不能少于6位");
        if (!password.equals(confirm))
            throw new IllegalArgumentException("两次输入的密码不一致");

        // ── 获取手机号 ──
        String phone = resolvePhone(req);
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches())
            throw new IllegalArgumentException("未获取到手机号，请授权后重试");
        if (nickname == null || nickname.isEmpty())
            nickname = phone.substring(0, 3) + "****" + phone.substring(7);

        log.info("注册: phone={}, mock={}, corpId={}", phone, phoneMockEnabled, corpId);

        // ── 检查是否已注册 ──
        GsUser exist = userMapper.selectOne(new LambdaQueryWrapper<GsUser>()
                .eq(GsUser::getPhone, phone)
                .eq(GsUser::getCorpId, corpId));
        if (exist != null && exist.getPassword() != null)
            throw new IllegalArgumentException("该手机号已注册，请直接登录");

        // ── 创建/更新用户 ──
        GsUser user;
        if (exist != null) {
            user = exist;
        } else {
            user = new GsUser();
            user.setPhone(phone);
            user.setCorpId(corpId);
            user.setPointsBalance(200);
            user.setStatus(1);
        }
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setLastLoginTime(LocalDateTime.now());

        if (exist != null) {
            userMapper.updateById(user);
        } else {
            userMapper.insert(user);
        }

        // ── 同步到底座平台会员 ──
        syncToBasePlatform(user);

        log.info("用户注册成功: phone={}, userId={}, memberId={}", phone, user.getId(), user.getPlatformMemberId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getId());
        result.put("phone", user.getPhone());
        result.put("nickname", user.getNickname());
        result.put("platformMemberId", user.getPlatformMemberId());
        return result;
    }

    // ═══ 登录 ═══

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

        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        String token = generateJwt(user);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getId());
        result.put("phone", user.getPhone());
        result.put("nickname", user.getNickname());
        result.put("avatar", user.getAvatar());
        result.put("pointsBalance", user.getPointsBalance());
        result.put("platformMemberId", user.getPlatformMemberId());
        result.put("token", token);
        result.put("corpId", user.getCorpId());
        result.put("expiresIn", jwtExpiration);

        log.info("用户登录成功: phone={}, userId={}", phone, user.getId());
        return result;
    }

    // ═══ 私有方法 ═══

    /**
     * 获取手机号。优先级：
     * 1. 前端传入 mockPhone（开发模式）
     * 2. 微信 encryptedData + iv 解密（正式环境）
     * 3. 配置中的默认 mock 手机号
     */
    private String resolvePhone(RegisterRequest req) {
        // 开发模式：前端或配置注入的 mock 手机号
        if (phoneMockEnabled) {
            if (req.getMockPhone() != null && PHONE_PATTERN.matcher(req.getMockPhone()).matches()) {
                log.info("使用前端 mock 手机号: {}", req.getMockPhone());
                return req.getMockPhone();
            }
            log.info("使用配置 mock 手机号: {}", phoneMockPhone);
            return phoneMockPhone;
        }

        // 正式环境：解密微信加密数据
        if (req.getEncryptedData() != null && req.getIv() != null && req.getCode() != null) {
            return decryptWechatPhone(req.getCode(), req.getEncryptedData(), req.getIv());
        }

        return null;
    }

    /**
     * 从微信解密手机号（正式环境实现）。
     * 步骤：code → access_token + session_key → AES 解密 encryptedData
     */
    private String decryptWechatPhone(String code, String encryptedData, String iv) {
        // TODO: 实现微信 code→session_key 换取 + AES-CBC 解密
        // 需要先用 appId+appSecret 换 access_token，再拿 code 换 session_key
        // 然后用 session_key 做 AES-128-CBC 解密 encryptedData
        // 返回解密后的 JSON 中的 phoneNumber 字段
        log.warn("微信手机号解密尚未实现（需认证服务号），当前仅 mock 模式可用");
        return null;
    }

    /** 同步会员信息到底座平台（非致命，失败不阻塞注册） */
    private void syncToBasePlatform(GsUser user) {
        if (user.getPlatformMemberId() != null) return; // 已同步
        try {
            Map<String, Object> sync = memberSyncClient.sync(
                    user.getPhone(), user.getNickname(), user.getAvatar());
            if (sync != null && sync.get("memberDbId") != null) {
                Long memberDbId = Long.valueOf(sync.get("memberDbId").toString());
                user.setPlatformMemberId(memberDbId);
                userMapper.updateById(user);
                log.info("底座会员关联完成: userId={} → memberDbId={}", user.getId(), memberDbId);
            }
        } catch (Exception e) {
            log.error("底座会员同步异常（非致命）: phone={}, error={}", user.getPhone(), e.getMessage());
        }
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

            JWSSigner signer = new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8));
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
