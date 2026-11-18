package com.aip.ops.service;

import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 订单头（主表）服务：创建、合计/状态同步、经办人汇总、删除残留主表。
 */
public interface BusinessOrderService {

    /** 新建订单头；totalAmount 为产品去重后的合计，calcStatus 默认“未计算”。 */
    OpsBusinessOrder createOrder(Long corpId, String orderKey, String orderNo, String batchNo,
                                 String businessGroupNo, String receiptNo, String company,
                                 String customer, String customerPhone, Long platformMemberId,
                                 Long communityId, String communityName, String detailedAddress,
                                 String receiptDate, LocalDate financeReceiptDate,
                                 Integer fundDynamicRows, String isReceived, String voucherUrl,
                                 BigDecimal totalAmount, String calcStatus, String remark,
                                 String paymentMethod);

    /**
     * 新建订单头并写入收款方式（员工手工制单使用）。
     *
     * <p>paymentType：FULL/DEPOSIT；isFullPayment 由 paymentType 推导（FULL=1/DEPOSIT=0）；
     * balanceAmount 在方法内部按 totalAmount - depositAmount 计算，不接收调用方传入，
     * 保证任何入口都无法写入与销售价/定金不一致的余款。</p>
     */
    OpsBusinessOrder createOrderWithPayment(Long corpId, String orderKey, String orderNo,
                                            String batchNo, String businessGroupNo, String receiptNo,
                                            String company, String customer, String customerPhone,
                                            Long platformMemberId, Long communityId,
                                            String communityName, String detailedAddress,
                                            String receiptDate, LocalDate financeReceiptDate,
                                            Integer fundDynamicRows, String isReceived,
                                            String voucherUrl, BigDecimal totalAmount,
                                            String calcStatus, String remark, String paymentMethod,
                                            String paymentType, BigDecimal depositAmount);

    /** 回写凭证信息；按订单+组织双条件，防止跨租户改写。 */
    void updateVoucherAttachments(Long orderId, Long corpId, String receiptImageUrl,
                                  String paymentVoucherUrls, String contractAttachmentUrls);

    OpsBusinessOrder findByOrderKey(Long corpId, String orderKey);

    OpsBusinessOrder getById(Long id);

    List<OpsBusinessOrder> findByIds(Collection<Long> ids);

    /** 重算主表 total_amount / total_commission / 以旧换新抵扣 / 应付合计 / has_trade_in。 */
    void refreshTotals(Long orderId);

    /** 以旧换新自动采购单状态回写（PENDING/SUCCESS/FAILED + 采购单ID/号/错误）。 */
    void updateTradeInPurchaseStatus(Long orderId, String status, Long purchaseOrderId,
                                     String billNo, String error);

    /** 子表状态变更后同步主表 calc_status。 */
    void syncStatus(Long orderId);

    /** 重算主表经办人姓名/手机号汇总（、分隔）。 */
    void refreshAgentSummary(Long orderId);

    /** 财务核对字段回写主表（含核对结论/备注）。 */
    void updateFinanceFields(Long orderId, String receiptNo, LocalDate financeReceiptDate,
                             Integer fundDynamicRows, String isReceived, String conclusion,
                             String checkRemark, Long userId, String userName,
                             java.time.LocalDateTime time);

    /** 删除已无子表行的主表（物理删除，仅限传入的订单集合）。 */
    void deleteEmptyOrders(Collection<Long> orderIds);

    /** 以子表首行补建订单头（兼容历史/旧 JAR 写入后 order_id 为空的场景）。 */
    OpsBusinessOrder ensureOrderForItem(OpsBusinessData item);
}
