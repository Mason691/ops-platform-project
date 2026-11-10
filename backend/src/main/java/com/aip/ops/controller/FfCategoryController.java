package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.dto.FfCategoryImportResult;
import com.aip.ops.entity.FfCategory;
import com.aip.ops.service.FfCategoryService;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.result.R;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 财务管理 — 分类管理（ff_category）。
 *
 * <p>平台级基础数据；查看需 finance:category:view，写操作分别校验
 * finance:category:add/edit/delete/import。超管 JWT 权限列表可能为空，
 * 因此统一采用 {@code isSuperAdmin() || hasPermission(...)} 手动校验。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/admin/ff-category")
@RequiredArgsConstructor
public class FfCategoryController {

    private static final String PERM_VIEW = "finance:category:view";
    private static final String PERM_ADD = "finance:category:add";
    private static final String PERM_EDIT = "finance:category:edit";
    private static final String PERM_DELETE = "finance:category:delete";
    private static final String PERM_IMPORT = "finance:category:import";
    private static final long MAX_IMPORT_BYTES = 50L * 1024 * 1024;

    private final FfCategoryService service;

    /** 分页列表（编码/名称模糊查询 + 状态筛选） */
    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String categoryCode,
                     @RequestParam(required = false) String categoryName,
                     @RequestParam(required = false) Integer status) {
        requirePermission(PERM_VIEW);
        return R.ok(service.page(pageNum, pageSize, categoryCode, categoryName, status));
    }

    /** 详情 */
    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id) {
        requirePermission(PERM_VIEW);
        return R.ok(service.getDetail(id));
    }

    /** 新增 */
    @OperationLog(module = "财务管理", action = "新增分类", targetTable = "ff_category")
    @PostMapping
    public R<?> create(@RequestBody FfCategory category) {
        requirePermission(PERM_ADD);
        service.create(category);
        return R.ok("创建成功");
    }

    /** 编辑 */
    @OperationLog(module = "财务管理", action = "修改分类", targetTable = "ff_category")
    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id, @RequestBody FfCategory category) {
        requirePermission(PERM_EDIT);
        service.update(id, category);
        return R.ok("更新成功");
    }

    /** 逻辑删除 */
    @OperationLog(module = "财务管理", action = "删除分类", targetTable = "ff_category")
    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        requirePermission(PERM_DELETE);
        service.delete(id);
        return R.ok("删除成功");
    }

    /** 上传 Excel 导入（multipart） */
    @OperationLog(module = "财务管理", action = "导入分类", targetTable = "ff_category")
    @PostMapping("/import")
    public R<?> importFile(@RequestParam("file") MultipartFile file) {
        requirePermission(PERM_IMPORT);
        validateImportFile(file.getOriginalFilename(), file.getSize());
        try (InputStream input = file.getInputStream()) {
            FfCategoryImportResult result = service.importFromExcel(input);
            log.info("财务分类文件导入：file={}, total={}, inserted={}, updated={}, skipped={}, failed={}",
                    file.getOriginalFilename(), result.getTotal(), result.getInserted(),
                    result.getUpdated(), result.getSkipped(), result.getFailed());
            return R.ok("导入完成", result);
        } catch (IOException e) {
            throw BusinessException.of("读取上传文件失败：" + e.getMessage());
        }
    }

    /**
     * 按服务器路径导入。读取服务器任意文件属高风险操作，仅限平台超管；
     * 路径必须是 .xlsx/.xls 且不超过 50MB。
     */
    @OperationLog(module = "财务管理", action = "按路径导入分类", targetTable = "ff_category")
    @PostMapping("/import-path")
    public R<?> importPath(@RequestBody Map<String, String> body) {
        if (!SecurityUtils.isSuperAdmin()) {
            throw BusinessException.forbidden("仅平台管理员可按服务器路径导入");
        }
        String rawPath = body.get("path");
        if (rawPath == null || rawPath.strip().isEmpty()) {
            throw BusinessException.of("请填写服务器 Excel 文件路径");
        }
        Path file;
        try {
            file = Paths.get(rawPath.strip()).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw BusinessException.of("服务器文件路径不合法：" + rawPath);
        }
        try {
            if (!Files.isRegularFile(file)) {
                throw BusinessException.of("文件不存在：" + file);
            }
            validateImportFile(file.getFileName().toString(), Files.size(file));
            try (InputStream input = Files.newInputStream(file)) {
                FfCategoryImportResult result = service.importFromExcel(input);
                log.info("财务分类路径导入：path={}, total={}, inserted={}, updated={}, skipped={}, failed={}",
                        file, result.getTotal(), result.getInserted(), result.getUpdated(),
                        result.getSkipped(), result.getFailed());
                return R.ok("导入完成", result);
            }
        } catch (IOException e) {
            throw BusinessException.of("读取文件失败：" + e.getMessage());
        }
    }

    private void validateImportFile(String filename, long size) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw BusinessException.of("仅支持 .xlsx / .xls 文件");
        }
        if (size <= 0) {
            throw BusinessException.of("文件为空");
        }
        if (size > MAX_IMPORT_BYTES) {
            throw BusinessException.of("文件不能超过 50MB");
        }
    }

    private void requirePermission(String permissionCode) {
        if (!SecurityUtils.isSuperAdmin() && !SecurityUtils.hasPermission(permissionCode)) {
            throw BusinessException.forbidden("无权限：" + permissionCode);
        }
    }
}
