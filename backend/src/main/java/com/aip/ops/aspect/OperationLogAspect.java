package com.aip.ops.aspect;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.service.OperationLogService;
import com.aip.sdk.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 拦截 {@code @OperationLog} 注解方法，异步写入操作审计日志。
 * 与主平台 OperationLogAspect 格式兼容，日志写入同一目录。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(opLog)")
    public Object around(ProceedingJoinPoint pjp, OperationLog opLog) throws Throwable {
        // Capture security context before method executes
        Long corpId = null;
        Long userId = null;
        String userName = null;
        try {
            corpId = SecurityUtils.getCurrentCorpId();
            userId = SecurityUtils.getCurrentUserId();
            userName = SecurityUtils.getCurrentUser().getUsername();
        } catch (Exception ignored) {}

        String ip = resolveIp();

        Object result = pjp.proceed();

        // Fire-and-forget async log write after successful execution
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", UUID.randomUUID().toString().replace("-", ""));
            entry.put("corpId", corpId);
            entry.put("userId", userId);
            entry.put("userName", userName);
            entry.put("timestamp", LocalDateTime.now().format(TS_FMT));
            entry.put("module", opLog.module());
            entry.put("action", opLog.action());
            entry.put("targetTable", opLog.targetTable());
            entry.put("ip", ip);
            entry.put("detail", serializeArgs(pjp.getArgs()));
            operationLogService.asyncWrite(entry);
        } catch (Exception e) {
            log.warn("Failed to submit operation log entry: {}", e.getMessage());
        }

        return result;
    }

    private String resolveIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String ip = req.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = req.getHeader("X-Real-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = req.getRemoteAddr();
            }
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            return ip;
        } catch (Exception e) {
            return null;
        }
    }

    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) return "{}";
        try {
            for (Object arg : args) {
                if (arg != null && !(arg instanceof jakarta.servlet.ServletRequest)
                        && !(arg instanceof jakarta.servlet.ServletResponse)) {
                    return objectMapper.writeValueAsString(arg);
                }
            }
        } catch (Exception e) {
            log.debug("Could not serialize args for operation log: {}", e.getMessage());
        }
        return "{}";
    }
}
