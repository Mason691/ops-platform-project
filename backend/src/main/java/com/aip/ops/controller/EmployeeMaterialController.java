package com.aip.ops.controller;

import com.aip.ops.dto.MaterialOptionsView;
import com.aip.ops.dto.MaterialRequisitionRequest;
import com.aip.ops.dto.MaterialRequisitionSummary;
import com.aip.ops.dto.MaterialRequisitionView;
import com.aip.ops.dto.MaterialSearchResult;
import com.aip.ops.dto.MaterialWriteOffRequest;
import com.aip.ops.dto.MaterialWriteOffSummary;
import com.aip.ops.dto.MaterialWriteOffView;
import com.aip.ops.service.EmployeeMaterialSupport;
import com.aip.ops.service.MaterialRequisitionService;
import com.aip.ops.service.MaterialWriteOffService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 员工物资领用/核销（公众号 H5）。
 *
 * <p>全部接口要求员工 JWT；组织/员工由服务端从 JWT 派生。领用为申请制（不绑仓库），
 * 核销基于已通过领用单，审批通过后回写领用余量。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/employee/material")
@RequiredArgsConstructor
public class EmployeeMaterialController {

    private static final Set<String> VOUCHER_EXTS = Set.of("jpg", "jpeg", "png", "webp", "pdf");
    private static final long MAX_VOUCHER_BYTES = 10L * 1024 * 1024;

    private final EmployeeMaterialSupport support;
    private final MaterialRequisitionService requisitionService;
    private final MaterialWriteOffService writeOffService;

    @Value("${app.upload.base-dir:/home/jason/ops-platform-project/uploads}")
    private String uploadBaseDir;

    @GetMapping("/options")
    public R<MaterialOptionsView> options() {
        return R.ok(support.options());
    }

    /** 商品类别 + 名称/编码/型号/品牌关键字搜索（数据源：供应链商品资料）。 */
    @GetMapping("/materials")
    public R<MaterialSearchResult> materials(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        EmployeeMaterialSupport.EmployeeContext ctx = support.context();
        return R.ok(support.searchMaterials(ctx.corpId(), category, keyword, pageNum, pageSize));
    }

    // ── 物资领用 ──────────────────────────────────────────────────────

    @PostMapping("/requisitions")
    public R<MaterialRequisitionView> createRequisition(
            @RequestBody MaterialRequisitionRequest request) {
        return R.ok(requisitionService.create(request));
    }

    @GetMapping("/requisitions")
    public R<?> listRequisitions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(requisitionService.page(status, keyword, dateFrom, dateTo, pageNum, pageSize));
    }

    @GetMapping("/requisitions/{id}")
    public R<MaterialRequisitionView> requisitionDetail(@PathVariable Long id) {
        return R.ok(requisitionService.detail(id));
    }

    /** 可核销领用单（已通过/已发放、未核销完成、无审批中核销单）。 */
    @GetMapping("/requisitions/pickable")
    public R<List<MaterialRequisitionSummary>> pickable(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return R.ok(requisitionService.pickable(keyword, dateFrom, dateTo));
    }

    // ── 物资核销 ──────────────────────────────────────────────────────

    @PostMapping("/write-offs")
    public R<MaterialWriteOffView> createWriteOff(@RequestBody MaterialWriteOffRequest request) {
        return R.ok(writeOffService.create(request));
    }

    @GetMapping("/write-offs")
    public R<?> listWriteOffs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(writeOffService.page(status, keyword, dateFrom, dateTo, pageNum, pageSize));
    }

    @GetMapping("/write-offs/{id}")
    public R<MaterialWriteOffView> writeOffDetail(@PathVariable Long id) {
        return R.ok(writeOffService.detail(id));
    }

    /** 使用凭证上传：JPG/PNG/WEBP/PDF，单个 ≤10MB。 */
    @PostMapping("/voucher")
    public R<Map<String, Object>> uploadVoucher(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) return R.fail("文件为空");
        String original = file.getOriginalFilename();
        String ext = original != null && original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!VOUCHER_EXTS.contains(ext)) {
            return R.fail("仅支持 JPG/PNG/WEBP/PDF 文件");
        }
        if (file.getSize() > MAX_VOUCHER_BYTES) {
            return R.fail("文件不能超过10MB");
        }
        try {
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            File dir = new File(uploadBaseDir, "ops/material/voucher/" + date);
            if (!dir.exists() && !dir.mkdirs()) {
                return R.fail("上传目录创建失败");
            }
            String name = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                    + "." + ext;
            file.transferTo(new File(dir, name));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", "/uploads/ops/material/voucher/" + date + "/" + name);
            result.put("name", original);
            result.put("size", file.getSize());
            return R.ok(result);
        } catch (Exception e) {
            log.error("物资使用凭证上传失败", e);
            return R.fail("上传失败，请重试");
        }
    }
}
