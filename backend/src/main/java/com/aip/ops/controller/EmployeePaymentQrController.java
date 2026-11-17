package com.aip.ops.controller;

import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.security.qr.QrCodeIntegrityService;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 员工 H5 - 收款码取图（唯一出口）。
 *
 * <p>二维码打包在 jar 的 {@code secure/qr/} 下，不在 static 目录，Nginx 无法直出；
 * 每次请求由 {@link QrCodeIntegrityService} 做 ECDSA 验签，失败返回 503 + 统一文案，
 * 绝不返回未校验图片。响应强制 {@code Cache-Control: no-store}，避免浏览器缓存旧图。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/employee/business-data")
@RequiredArgsConstructor
public class EmployeePaymentQrController {

    private static final String ERROR_BODY =
            "{\"code\":503,\"msg\":\"收款码暂不可用，请联系工作人员\",\"data\":null}";

    private final QrCodeIntegrityService qrCodeIntegrityService;
    private final JwtTokenInspector jwtTokenInspector;

    @GetMapping("/payment-qr")
    public ResponseEntity<byte[]> paymentQr(HttpServletRequest request) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            throw BusinessException.forbidden("仅员工可查看收款码");
        }
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("登录组织信息无效，请重新登录");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        String ip = clientIp(request);

        QrCodeIntegrityService.Verification verification = qrCodeIntegrityService.loadVerified();
        if (!verification.valid()) {
            log.error("PAYMENT_QR_ACCESS_FAILED userId={} corpId={} ip={} reason={} imageSha256={}",
                    userId, corpId, ip, verification.reason(), verification.sha256());
            return ResponseEntity.status(503)
                    .contentType(MediaType.APPLICATION_JSON)
                    .cacheControl(CacheControl.noStore())
                    .body(ERROR_BODY.getBytes(StandardCharsets.UTF_8));
        }

        // 审计：谁、何时、从哪个 IP 取过收款码
        log.info("PAYMENT_QR_ACCESS userId={} corpId={} ip={} imageSha256={}",
                userId, corpId, ip, verification.sha256());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(verification.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(verification.bytes());
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        // 仅用于日志，去掉换行避免日志注入
        return ip == null ? "-" : ip.replace("\r", "").replace("\n", "");
    }
}
