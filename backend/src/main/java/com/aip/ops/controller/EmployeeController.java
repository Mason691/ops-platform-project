package com.aip.ops.controller;

import com.aip.common.annotation.OperationLog;
import com.aip.ops.dto.BalanceReceiveRequest;
import com.aip.ops.dto.BusinessRefundApplyRequest;
import com.aip.ops.dto.CommunityCreateRequest;
import com.aip.ops.dto.EmployeeBusinessEntryRequest;
import com.aip.ops.dto.ReceiptOcrFeedbackRequest;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.BusinessAttachmentService;
import com.aip.ops.service.BusinessDataService;
import com.aip.ops.service.BusinessOrderRefundService;
import com.aip.ops.service.BusinessSpecialFileService;
import com.aip.ops.service.EmployeeAuthService;
import com.aip.ops.service.EmployeeBusinessEntryService;
import com.aip.ops.service.EmployeeService;
import com.aip.ops.service.ProductPriceService;
import com.aip.ops.service.ReceiptOcrService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.result.R;
import com.aip.sdk.security.LoginUser;
import com.aip.sdk.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

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
    private final BusinessDataService businessDataService;
    private final EmployeeBusinessEntryService employeeBusinessEntryService;
    private final ProductPriceService productPriceService;
    private final BusinessSpecialFileService businessSpecialFileService;
    private final BusinessAttachmentService businessAttachmentService;
    private final ReceiptOcrService receiptOcrService;
    private final BusinessOrderRefundService businessOrderRefundService;
    private final JwtTokenInspector jwtTokenInspector;
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
    public R<?> getOverview(@RequestParam(required = false) String batchNo) {
        return R.ok(employeeService.getOverview(batchNo));
    }

    /**
     * 员工端订单列表：订单主行 + 本人参与的商品明细 + 工作流进度
     * （当前/下一节点、当前/下一处理人）。仅当前组织 + 当前经办人。
     */
    @GetMapping("/business-data/orders")
    public R<?> employeeOrders(@RequestParam(required = false) String batchNo,
                               @RequestParam(required = false) String orderNo,
                               @RequestParam(required = false) String bizStatus,
                               @RequestParam(defaultValue = "1") int pageNum,
                               @RequestParam(defaultValue = "10") int pageSize) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可查看业务订单");
        }
        return R.ok(businessDataService.employeeOrderPage(
                SecurityUtils.getCurrentCorpId(), employeeService.getCurrentAgentPhone(),
                batchNo, orderNo, bizStatus, pageNum, pageSize));
    }

    /** 员工端订单详情：本人参与的商品明细 + 流程进度 + 工作流处理留痕。 */
    @GetMapping("/business-data/orders/{orderId}")
    public R<?> employeeOrderDetail(@PathVariable Long orderId) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可查看业务订单");
        }
        return R.ok(businessDataService.employeeOrderDetail(
                SecurityUtils.getCurrentCorpId(), employeeService.getCurrentAgentPhone(), orderId));
    }

    /**
     * 工作流待办处理人查看订单详情：按 taskId 校验任务归属，
     * 供派工施工员（可能不是订单经办人）在 H5 施工节点查看订单/地址。
     */
    @GetMapping("/business-data/tasks/{taskId}/order")
    public R<?> taskOrderDetail(@PathVariable Long taskId) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可查看业务订单");
        }
        return R.ok(businessDataService.employeeTaskOrderDetail(
                SecurityUtils.getCurrentUserId(), taskId));
    }

    /** 退回草稿：终止当前流程、状态回 0、允许编辑后沿用原订单编号重新提交。 */
    @OperationLog(module = "运营管理", action = "员工退回业务订单草稿",
            targetTable = "ops_business_order")
    @PostMapping("/business-data/orders/{orderId}/return-draft")
    public R<?> returnDraft(@PathVariable Long orderId) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可退回草稿");
        }
        return R.ok(businessOrderRefundService.returnDraft(orderId));
    }

    /** 发起作废退款：部长→财务→总经办审批，通过后订单置 -1。 */
    @OperationLog(module = "运营管理", action = "员工发起作废退款",
            targetTable = "ops_business_refund")
    @PostMapping("/business-data/orders/{orderId}/refund")
    public R<?> applyRefund(@PathVariable Long orderId,
                            @RequestBody BusinessRefundApplyRequest request) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可发起退款");
        }
        return R.ok(businessOrderRefundService.apply(orderId, request));
    }

    /** 当前订单最近一次作废退款（含流程进度与审批人）。 */
    @GetMapping("/business-data/orders/{orderId}/refund")
    public R<?> refundDetail(@PathVariable Long orderId) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可查看退款进度");
        }
        return R.ok(businessOrderRefundService.detail(orderId));
    }

    /** 作废驳回后重新发起正常审批（状态 0/1/2 且当前无流程实例）。 */
    @OperationLog(module = "运营管理", action = "员工重新提交业务订单审批",
            targetTable = "ops_business_order")
    @PostMapping("/business-data/orders/{orderId}/restart-flow")
    public R<?> restartFlow(@PathVariable Long orderId) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可重新提交审批");
        }
        return R.ok(businessOrderRefundService.restartFlow(orderId));
    }

    /** 草稿编辑回显数据（订单头 + 商品/旧品/凭证附件）。 */
    @GetMapping("/business-data/orders/{orderId}/edit")
    public R<?> draftEditData(@PathVariable Long orderId) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可编辑业务订单");
        }
        return R.ok(employeeBusinessEntryService.draftEditData(orderId));
    }

    /** 草稿编辑提交：沿用原订单编号/业务组号，替换明细并重新发起审批。 */
    @OperationLog(module = "运营管理", action = "员工编辑业务订单草稿",
            targetTable = "ops_business_order")
    @PutMapping("/business-data/orders/{orderId}/draft")
    public R<?> updateDraft(@PathVariable Long orderId,
                            @RequestBody EmployeeBusinessEntryRequest request) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可编辑业务订单");
        }
        return R.ok(employeeBusinessEntryService.updateDraft(orderId, request));
    }

    private static final int AVATAR_SIZE = 200;
    private static final float AVATAR_JPEG_QUALITY = 0.7f;

    /** Upload employee avatar, resize to 200x200 center-crop, compress JPEG, save. */
    @PostMapping("/avatar")
    public R<?> uploadAvatar(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) return R.fail("请选择图片文件");
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) return R.fail("仅支持图片格式");

        LoginUser user = SecurityUtils.getCurrentUser();
        Long userId = user.getUserId();

        // Read and resize to 200x200 center-cropped square
        BufferedImage src = ImageIO.read(file.getInputStream());
        if (src == null) return R.fail("无法解析图片文件");
        BufferedImage cropped = centerCropSquare(src);
        BufferedImage resized = new BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(cropped, 0, 0, AVATAR_SIZE, AVATAR_SIZE, null);
        g.dispose();

        // Compress JPEG to ~10-40KB
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (writers.hasNext()) {
            ImageWriter writer = writers.next();
            ImageOutputStream ios = ImageIO.createImageOutputStream(bos);
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(AVATAR_JPEG_QUALITY);
            writer.write(null, new IIOImage(resized, null, null), param);
            ios.close();
            writer.dispose();
        } else {
            ImageIO.write(resized, "jpg", bos);
        }

        // Save to disk
        Path dir = Paths.get(uploadBaseDir, "employee", "avatar");
        Files.createDirectories(dir);
        String filename = userId + "_" + UUID.randomUUID().toString().substring(0, 8) + ".jpg";
        Path target = dir.resolve(filename);
        Files.write(target, bos.toByteArray());

        String avatarUrl = resourceBaseUrl + "/uploads/employee/avatar/" + filename;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("avatar", avatarUrl);
        platformInternalClient.post("/api/internal/user/profile", body, null);

        return R.ok(Map.of("avatar", avatarUrl));
    }

    /** Crop the source image to the largest possible center square. */
    private BufferedImage centerCropSquare(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int size = Math.min(w, h);
        int x = (w - size) / 2;
        int y = (h - size) / 2;
        return src.getSubimage(x, y, size, size);
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
        platformInternalClient.post("/api/internal/user/profile", req, null);

        return R.ok(Map.of("realName", realName));
    }

    /** Self-service password change. Requires valid JWT. */
    @PostMapping("/change-password")
    public R<?> changePassword(@RequestBody Map<String, String> body) {
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        if (oldPassword == null || oldPassword.isBlank()) return R.fail("当前密码不能为空");
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 20
                || !newPassword.matches(".*[A-Za-z].*") || !newPassword.matches(".*\\d.*")) {
            return R.fail("新密码需为 8~20 位，且包含字母和数字");
        }
        employeeAuthService.changePassword(oldPassword, newPassword);
        return R.ok(null);
    }

    /** 获取当前经办人的批次列表 */
    @GetMapping("/business-data/batches")
    public R<?> getBatches() {
        return R.ok(employeeService.getBatchNos());
    }

    // ═══ 员工手工新增业务数据 ═══

    /** 表单选项：员工/公司信息、当前组织未上报批次、SCM 品牌/品类/型号。 */
    @GetMapping("/business-data/manual/options")
    public R<?> manualOptions() {
        return R.ok(employeeBusinessEntryService.getManualEntryOptions());
    }

    /** 按客户手机号查询平台会员姓名（仅返回是否找到 + 姓名）。 */
    @GetMapping("/business-data/manual/member")
    public R<?> manualMember(@RequestParam String phone) {
        return R.ok(employeeBusinessEntryService.lookupMember(phone));
    }

    /** 员工制单新建小区：自动关联当前组织所在区县，同名幂等。 */
    @OperationLog(module = "运营管理", action = "员工新建小区", targetTable = "sys_community")
    @PostMapping("/business-data/communities")
    public R<?> createCommunity(@RequestBody CommunityCreateRequest request) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可新建小区");
        }
        return R.ok(employeeBusinessEntryService.createCommunity(request));
    }

    /**
     * 制单时按商品查询标准售价：优先 productId（SCM 商品ID），
     * 旧前端仅传品牌/品类/型号时按字段兜底。
     */
    @GetMapping("/business-data/product-price")
    public R<?> manualProductPrice(@RequestParam(required = false) Long productId,
                                   @RequestParam(required = false) String brand,
                                   @RequestParam(required = false) String category,
                                   @RequestParam(required = false) String model) {
        // 与员工手工制单一致：拒绝会员 SSO 令牌（token_type=sso）
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可查询产品售价");
        }
        return R.ok(productPriceService.lookupForEmployee(
                SecurityUtils.getCurrentCorpIdOrNull(), productId, brand, category, model));
    }

    /**
     * 制单时收据号查重（输入框失焦提示）：同组织内同一客户可复用，
     * 被其他客户占用时返回 duplicate=true + message；最终提交仍由服务端二次校验。
     */
    @GetMapping("/business-data/receipt-no/check")
    public R<?> checkReceiptNo(@RequestParam String receiptNo,
                               @RequestParam(required = false) String customer) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可校验收据号");
        }
        return R.ok(employeeBusinessEntryService.checkReceiptNo(receiptNo, customer));
    }

    /**
     * 低价特批文件上传：PDF/JPG/PNG ≤10MB；文件信息落库供提交引用与审计。
     */
    @PostMapping("/business-data/special-file")
    public R<?> uploadSpecialFile(@RequestParam("file") MultipartFile file) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可上传特批文件");
        }
        return R.ok(businessSpecialFileService.upload(file));
    }

    /**
     * 业务附件上传：fileType=RECEIPT（收据图片）/PAYMENT（支付凭证）/CONTRACT（合同附件）/REFUND（退款补充附件）。
     * 图片服务端压缩为 JPEG，PDF 原样保存；返回附件 ID 供提交与 OCR 引用。
     */
    @PostMapping("/business-data/attachments")
    public R<?> uploadBusinessAttachment(@RequestParam("file") MultipartFile file,
                                         @RequestParam("fileType") String fileType) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可上传业务附件");
        }
        return R.ok(businessAttachmentService.upload(file, fileType));
    }

    /** 移除表单中未绑定的业务附件（文件+记录）；已绑定订单的附件不可删。 */
    @DeleteMapping("/business-data/attachments/{id}")
    public R<?> deleteBusinessAttachment(@PathVariable Long id) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可删除业务附件");
        }
        Long corpId = SecurityUtils.getCurrentCorpIdOrNull();
        if (corpId == null || corpId <= 0) {
            return R.fail("请先切换到具体组织");
        }
        businessAttachmentService.deleteUnbound(id, corpId);
        return R.ok(null);
    }

    /** 收据图片 OCR：返回识别到的收据号与提示；失败时返回手填提示，不阻塞制单。 */
    @PostMapping("/business-data/receipt-ocr")
    public R<?> recognizeReceiptNo(@RequestBody Map<String, Long> body) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可识别收据号");
        }
        Long attachmentId = body == null ? null : body.get("attachmentId");
        return R.ok(receiptOcrService.recognize(attachmentId));
    }

    /** 收据号 OCR 采用结果反馈：记录图片、红蓝候选、最终采用值与是否人工修改。 */
    @PostMapping("/business-data/receipt-ocr/feedback")
    public R<?> logReceiptOcrAdoption(
            @RequestBody(required = false) ReceiptOcrFeedbackRequest body) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可记录收据识别结果");
        }
        receiptOcrService.logAdoption(body);
        return R.ok(null);
    }

    /** 保存手工录入业务数据：组织/经办人由服务端从 JWT 派生。 */
    @OperationLog(module = "运营管理", action = "员工手工新增业务数据", targetTable = "ops_business_order_item")
    @PostMapping("/business-data/manual")
    public R<?> createManual(@RequestBody EmployeeBusinessEntryRequest request) {
        if (jwtTokenInspector.isExternalUser()) {
            return R.fail(403, "外部用户暂不可发起业务");
        }
        return R.ok(employeeBusinessEntryService.createManualEntry(request));
    }

    /** 以旧换新采购单创建失败/状态待同步时重试（SCM 幂等）。 */
    @PostMapping("/business-data/orders/{orderId}/trade-in-purchase/retry")
    public R<?> retryTradeInPurchase(@PathVariable Long orderId) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可重试旧品采购单");
        }
        return R.ok(employeeBusinessEntryService.retryTradeInPurchase(orderId));
    }

    /** 制单成功页轮询/详情兜底：查询订单当前审批人。 */
    @GetMapping("/business-data/manual/{orderId}/approver")
    public R<?> manualApprover(@PathVariable Long orderId) {
        return R.ok(employeeBusinessEntryService.currentApprover(orderId));
    }

    // ═══ 货款补录（定金支付 → 收齐余款） ═══

    /** 当前经办人名下定金支付且未全款的业务订单。 */
    @GetMapping("/business-data/balance-pending")
    public R<?> balancePending() {
        return R.ok(employeeBusinessEntryService.listDepositPending());
    }

    /** 货款补录详情（只读；校验订单属于当前经办人）。 */
    @GetMapping("/business-data/balance-pending/{orderId}")
    public R<?> balancePendingDetail(@PathVariable Long orderId) {
        return R.ok(employeeBusinessEntryService.depositPendingDetail(orderId));
    }

    /** 提交货款补录：实收余款=应补余款、凭证必填；成功后 is_full_payment=1。 */
    @OperationLog(module = "运营管理", action = "货款补录", targetTable = "ops_business_order")
    @PostMapping("/business-data/{orderId}/balance-receive")
    // 请求体放在第一个参数：@OperationLog 只序列化第一个非 Servlet 参数，
    // 这样审计日志能带上补录金额与凭证 URL（orderId 在路径中，日志 detail 外仍有接口上下文）
    public R<?> receiveBalance(@RequestBody BalanceReceiveRequest request,
                               @PathVariable Long orderId) {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            return R.fail(403, "仅员工可进行货款补录");
        }
        return R.ok(employeeBusinessEntryService.receiveBalance(orderId, request));
    }

    /** 经办人核对：仅将本组织 + 本人批次下已计算数据标记为已核对 */
    @OperationLog(module = "运营管理", action = "核对", targetTable = "ops_business_order_item")
    @PostMapping("/business-data/batch/check")
    public R<?> batchCheck(@RequestBody Map<String, String> params) {
        String batchNo = params.get("batchNo");
        if (batchNo == null || batchNo.isBlank()) return R.fail("批次号不能为空");
        int count = businessDataService.batchCheckOwned(batchNo,
                SecurityUtils.getCurrentCorpId(), employeeService.getCurrentAgentPhone());
        return R.ok(Map.of("checkedCount", count, "message", "已核对 " + count + " 条数据"));
    }
}
