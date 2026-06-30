package com.aip.ops.service.impl;

import com.aip.ops.client.MemberSyncClient;
import com.aip.ops.dto.LoginRequest;
import com.aip.ops.dto.RegisterRequest;
import com.aip.ops.dto.WechatLoginRequest;
import com.aip.ops.entity.OpsMember;
import com.aip.ops.mapper.OpsMemberMapper;
import com.aip.ops.service.IUserService;
import com.aip.ops.service.WechatPhoneService;
import com.aip.sdk.client.PlatformInternalClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class UserServiceImpl implements IUserService {

    private final OpsMemberMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final MemberSyncClient memberSyncClient;
    private final PlatformInternalClient platformInternalClient;
    private final WechatPhoneService wechatPhoneService;

    private final long jwtExpiration;

    private static final java.util.regex.Pattern PHONE_PATTERN =
            java.util.regex.Pattern.compile("^1[3-9]\\d{9}$");

    public UserServiceImpl(
            OpsMemberMapper userMapper,
            PasswordEncoder passwordEncoder,
            MemberSyncClient memberSyncClient,
            PlatformInternalClient platformInternalClient,
            WechatPhoneService wechatPhoneService,
            @Value("${platform.jwt-expiration:43200}") long jwtExpiration) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.memberSyncClient = memberSyncClient;
        this.platformInternalClient = platformInternalClient;
        this.wechatPhoneService = wechatPhoneService;
        this.jwtExpiration = jwtExpiration;
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
        PhoneResult phoneResult = resolvePhone(req);
        if (phoneResult == null || !PHONE_PATTERN.matcher(phoneResult.phone).matches())
            throw new IllegalArgumentException("未获取到手机号，请授权后重试");
        String phone = phoneResult.phone;
        String wechatOpenid = phoneResult.openid;
        if (nickname == null || nickname.isEmpty())
            nickname = phone.substring(0, 3) + "****" + phone.substring(7);

        log.info("注册: phone={}, corpId={}", phone, corpId);

        // ── 检查是否已注册 ──
        OpsMember exist = userMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getPhone, phone)
                .eq(OpsMember::getCorpId, corpId));
        if (exist != null && exist.getPassword() != null)
            throw new IllegalArgumentException("该手机号已注册，请直接登录");

        // ── 创建/更新用户 ──
        OpsMember user;
        if (exist != null) {
            user = exist;
        } else {
            user = new OpsMember();
            user.setPhone(phone);
            user.setCorpId(corpId);
            user.setPointsBalance(200);
            user.setStatus(1);
        }
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setLastLoginTime(LocalDateTime.now());
        // 存储微信 openid（用于后续静默登录）
        if (wechatOpenid != null && user.getOpenid() == null) {
            user.setOpenid(wechatOpenid);
        }

        if (exist != null) {
            userMapper.updateById(user);
        } else {
            userMapper.insert(user);
        }

        // ── 同步到底座平台会员 ──
        syncToBasePlatform(user);

        log.info("用户注册成功: phone={}, userId={}, platformMemberId={}",
                phone, user.getId(), user.getPlatformMemberId());

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

        OpsMember user = userMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getPhone, phone)
                .eq(OpsMember::getCorpId, corpId));

        if (user == null || user.getPassword() == null
                || !passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("手机号或密码错误");
        }

        if (user.getStatus() == null || user.getStatus() != 1)
            throw new IllegalArgumentException("账号已被禁用，请联系管理员");

        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        String token = getSsoToken(user);

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

    // ═══ 微信登录 ═══

    @Override
    public Map<String, Object> wechatLogin(WechatLoginRequest req) {
        Long corpId = req.getCorpId() != null ? req.getCorpId() : 4L;
        String nickname = req.getNickname();

        // ── 获取手机号 ──
        PhoneResult phoneResult = resolvePhoneForWechat(req);
        if (phoneResult == null || !PHONE_PATTERN.matcher(phoneResult.phone).matches())
            throw new IllegalArgumentException("未获取到手机号，请授权后重试");
        String phone = phoneResult.phone;
        String wechatOpenid = phoneResult.openid;

        if (nickname == null || nickname.isEmpty())
            nickname = phone.substring(0, 3) + "****" + phone.substring(7);

        log.info("微信登录: phone={}, corpId={}", phone, corpId);

        // ── 查找或创建用户 ──
        OpsMember user = userMapper.selectOne(new LambdaQueryWrapper<OpsMember>()
                .eq(OpsMember::getPhone, phone)
                .eq(OpsMember::getCorpId, corpId));

        boolean isNew = false;
        if (user == null) {
            user = new OpsMember();
            user.setPhone(phone);
            user.setCorpId(corpId);
            user.setPointsBalance(200);
            user.setStatus(1);
            user.setNickname(nickname);
            // 存储微信 openid
            if (wechatOpenid != null) user.setOpenid(wechatOpenid);
            userMapper.insert(user);
            isNew = true;
            // 同步到底座平台
            syncToBasePlatform(user);
            log.info("微信登录—新用户注册: phone={}, userId={}", phone, user.getId());
        } else if (wechatOpenid != null && user.getOpenid() == null) {
            // 已有用户但无 openid — 补充记录
            user.setOpenid(wechatOpenid);
            userMapper.updateById(user);
        }

        if (user.getStatus() == null || user.getStatus() != 1)
            throw new IllegalArgumentException("账号已被禁用，请联系管理员");

        user.setLastLoginTime(LocalDateTime.now());
        if (!isNew) userMapper.updateById(user);

        String token = getSsoToken(user);

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
        result.put("isNewUser", isNew);

        log.info("微信登录成功: phone={}, userId={}, isNew={}", phone, user.getId(), isNew);
        return result;
    }

    // ═══ 私有方法 ═══

    /** 获取手机号（开发模式优先使用 mockPhone） */
    private PhoneResult resolvePhone(RegisterRequest req) {
        if (req.getMockPhone() != null && !req.getMockPhone().isBlank()) {
            return new PhoneResult(req.getMockPhone().trim(), null);
        }
        if (req.getEncryptedData() != null && req.getIv() != null && req.getCode() != null) {
            String[] decrypted = decryptWechatPhone(req.getCode(), req.getEncryptedData(), req.getIv());
            if (decrypted != null) return new PhoneResult(decrypted[0], decrypted[1]);
        }
        return null;
    }

    /** 从 WechatLoginRequest 解析手机号（开发模式优先使用 mockPhone） */
    private PhoneResult resolvePhoneForWechat(WechatLoginRequest req) {
        if (req.getMockPhone() != null && !req.getMockPhone().isBlank()) {
            return new PhoneResult(req.getMockPhone().trim(), null);
        }
        if (req.getEncryptedData() != null && req.getIv() != null && req.getCode() != null) {
            String[] decrypted = decryptWechatPhone(req.getCode(), req.getEncryptedData(), req.getIv());
            if (decrypted != null) return new PhoneResult(decrypted[0], decrypted[1]);
        }
        return null;
    }

    private static class PhoneResult {
        final String phone;
        final String openid;
        PhoneResult(String phone, String openid) { this.phone = phone; this.openid = openid; }
    }

    /**
     * 从微信解密手机号，同时返回 openid（用于后续静默登录）。
     * 返回数组：[0]=phone, [1]=openid, 解密失败返回 null
     */
    private String[] decryptWechatPhone(String code, String encryptedData, String iv) {
        if (code == null || encryptedData == null || iv == null) return null;
        try {
            WechatPhoneService.WechatPhoneResult result =
                    wechatPhoneService.decryptPhone(code, encryptedData, iv);
            if (result == null) return null;
            return new String[]{result.getPhone(), result.getOpenid()};
        } catch (Exception e) {
            log.error("微信手机号解密失败: {}", e.getMessage());
            return null;
        }
    }

    /** 同步会员信息到底座平台（非致命，失败不阻塞注册） */
    private void syncToBasePlatform(OpsMember user) {
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

    /**
     * 调用底座平台 SSO 获取全局 JWT（替代子系统自签 JWT）。
     * SSO 端点通过 identifierType + identifier 查找或创建会员后签发 JWT。
     */
    @SuppressWarnings("unchecked")
    private String getSsoToken(OpsMember user) {
        try {
            Map<String, Object> ssoReq = new LinkedHashMap<>();
            ssoReq.put("identifierType", "PHONE");
            ssoReq.put("identifier", user.getPhone());
            ssoReq.put("nickname", user.getNickname());
            ssoReq.put("avatar", user.getAvatar());
            ssoReq.put("subsystem", "ops-platform");
            ssoReq.put("corpId", user.getCorpId());

            Map<String, Object> ssoResp = platformInternalClient.post(
                    "/api/auth/sso/token", ssoReq, null);

            Map<String, Object> data = (Map<String, Object>) ssoResp.get("data");
            if (data != null && data.get("token") != null) {
                return data.get("token").toString();
            }
            throw new RuntimeException("SSO 令牌响应缺少 token 字段");
        } catch (RuntimeException e) {
            throw e; // re-throw our own errors
        } catch (Exception e) {
            log.error("获取 SSO 令牌失败: phone={}, error={}", user.getPhone(), e.getMessage());
            throw new RuntimeException("认证服务异常，请稍后重试", e);
        }
    }
}
