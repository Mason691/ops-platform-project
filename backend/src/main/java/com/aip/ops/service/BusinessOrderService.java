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
                                 BigDecimal totalAmount, String calcStatus);

    OpsBusinessOrder findByOrderKey(Long corpId, String orderKey);

    OpsBusinessOrder getById(Long id);

    List<OpsBusinessOrder> findByIds(Collection<Long> ids);

    /** 重算主表 total_amount / total_commission。 */
    void refreshTotals(Long orderId);

    /** 子表状态变更后同步主表 calc_status。 */
    void syncStatus(Long orderId);

    /** 重算主表经办人姓名/手机号汇总（、分隔）。 */
    void refreshAgentSummary(Long orderId);

    /** 财务核对字段回写主表。 */
    void updateFinanceFields(Long orderId, String receiptNo, LocalDate financeReceiptDate,
                             Integer fundDynamicRows, String isReceived, Long userId,
                             String userName, java.time.LocalDateTime time);

    /** 删除已无子表行的主表（物理删除，仅限传入的订单集合）。 */
    void deleteEmptyOrders(Collection<Long> orderIds);

    /** 以子表首行补建订单头（兼容历史/旧 JAR 写入后 order_id 为空的场景）。 */
    OpsBusinessOrder ensureOrderForItem(OpsBusinessData item);
}
