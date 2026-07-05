package com.aip.ops.controller;

import com.aip.ops.service.EmployeeAuthService;
import com.aip.ops.service.EmployeeService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.result.R;
import com.aip.sdk.security.LoginUser;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Employee identity verification and personal business data viewing.
 * All endpoints require JWT authentication (covered by SecurityConfig).
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/employee")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;
    private final EmployeeAuthService employeeAuthService;
    private final PlatformInternalClient platformInternalClient;

    @Value("${app.upload.base-dir:/home/jason/ops-platform-project/uploads}")
    private String uploadBaseDir;

    @Value("${app.resource.base-url:https://boss.jdzn99.com}")
    private String resourceBaseUrl;

    /** Check if current member is also an employee. Returns associated companies. */
    @GetMapping("/identity")
    public R<?> checkIdentity() {
        return R.ok(employeeService.checkIdentity());
    }

    /** List months (YYYY-MM) that have business data for this employee. */
    @GetMapping("/business-data/months")
    public R<?> getMonths() {
        return R.ok(employeeService.getAvailableMonths());
    }

    /** Category-level summary for a given month. */
    @GetMapping("/business-data/summary")
    public R<?> getSummary(@RequestParam String month) {
        return R.ok(employeeService.getCategorySummary(month));
    }

    /** Individual detail records for a given month + category. */
    @GetMapping("/business-data/detail")
    public R<?> getDetail(@RequestParam String month,
                          @RequestParam(required = false) String category) {
        return R.ok(employeeService.getDetailRecords(month, category));
    }

    /** Monthly aggregate stats with month-over-month comparison. */
    @GetMapping("/business-data/stats")
    public R<?> getStats(@RequestParam String month) {
        return R.ok(employeeService.getMonthlyStats(month));
    }

    /** All-in-one overview: all-time totals + monthly breakdown with detail records. */
    @GetMapping("/business-data/overview")
    public R<?> getOverview() {
        return R.ok(employeeService.getOverview());
    }

    /** Upload employee avatar, save to local disk, update platform sys_user.avatar. */
    @PostMapping("/avatar")
    public R<?> uploadAvatar(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) return R.fail("请选择图片文件");
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) return R.fail("仅支持图片格式");

        LoginUser user = SecurityUtils.getCurrentUser();
        Long userId = user.getUserId();

        // Save to uploads/employee/avatar/{userId}.jpg
        Path dir = Paths.get(uploadBaseDir, "employee", "avatar");
        Files.createDirectories(dir);
        String filename = userId + "_" + UUID.randomUUID().toString().substring(0, 8) + ".jpg";
        Path target = dir.resolve(filename);
        file.transferTo(target.toFile());

        String avatarUrl = resourceBaseUrl + "/uploads/employee/avatar/" + filename;

        // Update platform sys_user.avatar via internal API
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("avatar", avatarUrl);
        platformInternalClient.put("/api/internal/user/profile", body);

        // Store in ops_employee_info localStorage for immediate frontend refresh
        return R.ok(Map.of("avatar", avatarUrl));
    }

    /** Update employee realName on platform sys_user. */
    @PutMapping("/profile")
    public R<?> updateProfile(@RequestBody Map<String, String> body) {
        String realName = body.get("realName");
        if (realName == null || realName.isBlank()) return R.fail("昵称不能为空");
        if (realName.length() > 50) return R.fail("昵称长度不能超过50个字符");

        LoginUser user = SecurityUtils.getCurrentUser();

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("userId", user.getUserId());
        req.put("realName", realName);
        platformInternalClient.put("/api/internal/user/profile", req);

        return R.ok(Map.of("realName", realName));
    }

    /** Self-service password change. Requires valid JWT. */
    @PostMapping("/change-password")
    public R<?> changePassword(@RequestBody Map<String, String> body) {
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        if (oldPassword == null || oldPassword.isBlank()) return R.fail("当前密码不能为空");
        if (newPassword == null || newPassword.length() < 6) return R.fail("新密码至少6位");
        employeeAuthService.changePassword(oldPassword, newPassword);
        return R.ok(null);
    }
}
