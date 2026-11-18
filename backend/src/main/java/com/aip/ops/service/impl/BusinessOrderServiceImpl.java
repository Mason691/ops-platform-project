package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.ops.service.BusinessOrderService;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessOrderServiceImpl implements BusinessOrderService {

    private static final String INITIAL_STATUS = "未计算";

    private final OpsBusinessOrderMapper orderMapper;
    private final OpsBusinessDataMapper itemMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public OpsBusinessOrder createOrder(Long corpId, String orderKey, String orderNo, String batchNo,
                                        String businessGroupNo, String receiptNo, String company,
                                        String customer, String customerPhone, Long platformMemberId,
                                        Long communityId, String communityName, String detailedAddress,
                                        String receiptDate, LocalDate financeReceiptDate,
                                        Integer fundDynamicRows, String isReceived, String voucherUrl,
                                        BigDecimal totalAmount, String calcStatus, String remark,
                                        String paymentMethod) {
        // 导入/历史回退：默认全款支付，保证改造前数据与行为不变
        return createOrderWithPayment(corpId, orderKey, orderNo, batchNo, businessGroupNo,
                receiptNo, company, customer, customerPhone, platformMemberId, communityId,
                communityName, detailedAddress, receiptDate, financeReceiptDate, fundDynamicRows,
                isReceived, voucherUrl, totalAmount, calcStatus, remark, paymentMethod,
                "FULL", BigDecimal.ZERO);
    }

    @Override
    public OpsBusinessOrder createOrderWithPayment(Long corpId, String orderKey, String orderNo,
                                                   String batchNo, String businessGroupNo,
                                                   String receiptNo, String company, String customer,
                                                   String customerPhone, Long platformMemberId,
                                                   Long communityId, String communityName,
                                                   String detailedAddress, String receiptDate,
                                                   LocalDate financeReceiptDate, Integer fundDynamicRows,
                                                   String isReceived, String voucherUrl,
                                                   BigDecimal totalAmount, String calcStatus,
                                                   String remark, String paymentMethod,
                                                   String paymentType, BigDecimal depositAmount) {
        // 收款语义单一来源：paymentType 是制单时的付款方式，isFullPayment 是收款结果；
        // 余款只在这里按「销售价合计 - 定金」计算，调用方不得传入 balanceAmount。
        String type = "DEPOSIT".equalsIgnoreCase(paymentType) ? "DEPOSIT" : "FULL";
        BigDecimal total = (totalAmount != null ? totalAmount : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal deposit = "DEPOSIT".equals(type) && depositAmount != null
                ? depositAmount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        if ("DEPOSIT".equals(type)
                && (deposit.signum() <= 0 || deposit.compareTo(total) >= 0)) {
            throw BusinessException.of("定金金额必须大于0且小于销售价合计");
        }
        BigDecimal balance = "DEPOSIT".equals(type)
                ? total.subtract(deposit).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        OpsBusinessOrder order = new OpsBusinessOrder();
        order.setCorpId(corpId);
        order.setOrderKey(orderKey);
        order.setOrderNo(orderNo);
        order.setBatchNo(batchNo);
        order.setBusinessGroupNo(businessGroupNo);
        order.setReceiptNo(receiptNo);
        order.setCompany(company);
        order.setCustomer(customer);
        order.setCustomerPhone(customerPhone);
        order.setPlatformMemberId(platformMemberId);
        order.setCommunityId(communityId);
        order.setCommunityName(communityName);
        order.setDetailedAddress(detailedAddress);
        order.setReceiptDate(receiptDate);
        order.setFinanceReceiptDate(financeReceiptDate);
        order.setFundDynamicRows(fundDynamicRows);
        order.setIsReceived(isReceived);
        order.setVoucherUrl(voucherUrl);
        order.setTotalAmount(total);
        order.setTotalCommission(BigDecimal.ZERO);
        order.setCalcStatus(calcStatus != null ? calcStatus : INITIAL_STATUS);
        order.setRemark(remark);
        order.setPaymentMethod(paymentMethod);
        order.setPaymentType(type);
        // 全款=1；定金支付初始 0，货款补录成功后由补录接口置 1
        order.setIsFullPayment("DEPOSIT".equals(type) ? 0 : 1);
        order.setDepositAmount(deposit);
        order.setBalanceAmount(balance);
        order.setDeleteFlag(0);
        orderMapper.insert(order);
        return order;
    }

    @Override
    public void updateVoucherAttachments(Long orderId, String receiptImageUrl,
                                         String paymentVoucherUrls,
                                         String contractAttachmentUrls) {
        if (orderId == null) return;
        orderMapper.update(null, new LambdaUpdateWrapper<OpsBusinessOrder>()
                .eq(OpsBusinessOrder::getId, orderId)
                .set(OpsBusinessOrder::getReceiptImageUrl, receiptImageUrl)
                .set(OpsBusinessOrder::getPaymentVoucherUrls, paymentVoucherUrls)
                .set(OpsBusinessOrder::getContractAttachmentUrls, contractAttachmentUrls));
    }

    @Override
    public OpsBusinessOrder findByOrderKey(Long corpId, String orderKey) {
        if (corpId == null || orderKey == null || orderKey.isBlank()) return null;
        return orderMapper.selectOne(new LambdaQueryWrapper<OpsBusinessOrder>()
                .eq(OpsBusinessOrder::getCorpId, corpId)
                .eq(OpsBusinessOrder::getOrderKey, orderKey)
                .eq(OpsBusinessOrder::getDeleteFlag, 0)
                .last("LIMIT 1"));
    }

    @Override
    public OpsBusinessOrder getById(Long id) {
        return id == null ? null : orderMapper.selectById(id);
    }

    @Override
    public List<OpsBusinessOrder> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return orderMapper.selectBatchIds(ids.stream().filter(Objects::nonNull).distinct().toList());
    }

    @Override
    public void refreshTotals(Long orderId) {
        if (orderId == null) return;
        // 派生表不引用 UPDATE 目标（MySQL 派生表不支持隐式关联），先查后写。
        // 正数分录按「产品组」取 MAX 去重多经办人；退货负数分录按「退货单+产品组」
        // 取 MAX 后作为独立冲减项，不参与正向去重（需求七.3）。
        BigDecimal amount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(x.amount), 0) FROM ("
                        + "  SELECT MAX(i.receipt_amount) AS amount FROM ops_business_order_item i "
                        + "  WHERE i.order_id = ? AND i.delete_flag = 0 "
                        + "    AND COALESCE(i.sale_quantity, 1) >= 0 "
                        + "  GROUP BY i.category, i.brand, i.model, i.sale_price"
                        + "  UNION ALL "
                        + "  SELECT MAX(i.receipt_amount) AS amount FROM ops_business_order_item i "
                        + "  WHERE i.order_id = ? AND i.delete_flag = 0 AND i.sale_quantity < 0 "
                        + "  GROUP BY COALESCE(i.source_return_id, 0), i.category, i.brand, i.model, i.sale_price"
                        + ") x", BigDecimal.class, orderId, orderId);
        BigDecimal commission = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(i.commission_amount), 0) FROM ops_business_order_item i "
                        + "WHERE i.order_id = ? AND i.delete_flag = 0",
                BigDecimal.class, orderId);
        // 抵扣按销售产品组 MAX 去重（多经办人行同值，不能 SUM 翻倍）
        BigDecimal deduction = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(x.deduction), 0) FROM ("
                        + "  SELECT MAX(i.deduction_amount) AS deduction FROM ops_business_order_item i "
                        + "  WHERE i.order_id = ? AND i.delete_flag = 0 "
                        + "    AND COALESCE(i.sale_quantity, 1) >= 0 "
                        + "  GROUP BY i.category, i.brand, i.model, i.sale_price"
                        + ") x", BigDecimal.class, orderId);
        Integer tradeInRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ops_business_order_item i "
                        + "WHERE i.order_id = ? AND i.delete_flag = 0 AND i.is_trade_in = '是'",
                Integer.class, orderId);
        BigDecimal total = amount != null ? amount : BigDecimal.ZERO;
        BigDecimal deduct = deduction != null ? deduction : BigDecimal.ZERO;
        BigDecimal payable = total.subtract(deduct).max(BigDecimal.ZERO);
        jdbcTemplate.update("UPDATE ops_business_order SET total_amount = ?, "
                        + "total_commission = ?, trade_in_deduction_amount = ?, payable_amount = ?, "
                        + "has_trade_in = ?, modify_time = NOW() WHERE id = ?",
                total,
                commission != null ? commission : BigDecimal.ZERO,
                deduct, payable,
                tradeInRows != null && tradeInRows > 0 ? 1 : 0, orderId);
    }

    @Override
    public void updateTradeInPurchaseStatus(Long orderId, String status, Long purchaseOrderId,
                                            String billNo, String error) {
        if (orderId == null) return;
        jdbcTemplate.update("UPDATE ops_business_order SET trade_in_purchase_status = ?, "
                        + "trade_in_purchase_order_id = ?, trade_in_purchase_bill_no = ?, "
                        + "trade_in_purchase_error = ?, modify_time = NOW() WHERE id = ?",
                status, purchaseOrderId, billNo,
                error == null || error.isBlank() ? null : error, orderId);
    }

    @Override
    public void syncStatus(Long orderId) {
        if (orderId == null) return;
        orderMapper.syncStatusFromItems(orderId);
    }

    @Override
    public void refreshAgentSummary(Long orderId) {
        if (orderId == null) return;
        jdbcTemplate.update(
                "UPDATE ops_business_order m SET "
                        + "m.agent_names = (SELECT GROUP_CONCAT(DISTINCT i.agent ORDER BY i.agent SEPARATOR '、') "
                        + "  FROM ops_business_order_item i WHERE i.order_id = m.id AND i.delete_flag = 0 "
                        + "  AND i.agent IS NOT NULL AND i.agent <> ''), "
                        + "m.agent_phones = (SELECT GROUP_CONCAT(DISTINCT i.agent_phone ORDER BY i.agent_phone SEPARATOR '、') "
                        + "  FROM ops_business_order_item i WHERE i.order_id = m.id AND i.delete_flag = 0 "
                        + "  AND i.agent_phone IS NOT NULL AND i.agent_phone <> '') "
                        + "WHERE m.id = ?", orderId);
    }

    @Override
    public void updateFinanceFields(Long orderId, String receiptNo, LocalDate financeReceiptDate,
                                    Integer fundDynamicRows, String isReceived, String conclusion,
                                    String checkRemark, Long userId, String userName,
                                    LocalDateTime time) {
        if (orderId == null) return;
        LambdaUpdateWrapper<OpsBusinessOrder> uw = new LambdaUpdateWrapper<>();
        uw.eq(OpsBusinessOrder::getId, orderId)
                .eq(OpsBusinessOrder::getDeleteFlag, 0)
                .set(OpsBusinessOrder::getReceiptNo, receiptNo)
                .set(OpsBusinessOrder::getFinanceReceiptDate, financeReceiptDate)
                .set(OpsBusinessOrder::getFundDynamicRows, fundDynamicRows)
                .set(OpsBusinessOrder::getIsReceived, isReceived)
                .set(OpsBusinessOrder::getFinanceCheckConclusion, conclusion)
                .set(OpsBusinessOrder::getFinanceCheckRemark, checkRemark)
                .set(OpsBusinessOrder::getFinanceCheckUserId, userId)
                .set(OpsBusinessOrder::getFinanceCheckUserName, userName)
                .set(OpsBusinessOrder::getFinanceCheckTime, time)
                .set(OpsBusinessOrder::getModifyTime, time)
                .set(OpsBusinessOrder::getModifyUser, userId)
                .set(OpsBusinessOrder::getModifyUserName, userName);
        orderMapper.update(null, uw);
    }

    @Override
    public void deleteEmptyOrders(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return;
        String csv = orderIds.stream().filter(Objects::nonNull).distinct()
                .map(String::valueOf).collect(Collectors.joining(","));
        if (csv.isEmpty()) return;
        jdbcTemplate.update("DELETE o FROM ops_business_order o WHERE o.id IN (" + csv + ") "
                + "AND NOT EXISTS (SELECT 1 FROM ops_business_order_item i "
                + "WHERE i.order_id = o.id AND i.delete_flag = 0)");
    }

    /**
     * 兼容路径：子表行没有 order_id（旧 JAR 经兼容视图写入）时，按迁移同一口径归组并补建主表。
     * 若主表已存在则直接回填 order_id。
     */
    @Override
    public OpsBusinessOrder ensureOrderForItem(OpsBusinessData item) {
        if (item == null) return null;
        if (item.getOrderId() != null) {
            OpsBusinessOrder existing = orderMapper.selectById(item.getOrderId());
            if (existing != null) return existing;
        }
        String key = legacyOrderKey(item);
        OpsBusinessOrder order = findByOrderKey(item.getCorpId(), key);
        if (order == null) {
            try {
                order = createOrder(item.getCorpId(), key, item.getOrderNo(), item.getBatchNo(),
                        item.getBusinessGroupNo(), item.getReceiptNo(), item.getCompany(),
                        item.getCustomer(), item.getCustomerPhone(), item.getPlatformMemberId(),
                        item.getCommunityId(), item.getCommunityName(), item.getDetailedAddress(),
                        item.getReceiptDate(), item.getFinanceReceiptDate(), item.getFundDynamicRows(),
                        item.getIsReceived(), item.getVoucherUrl(),
                        item.getReceiptAmount() != null ? item.getReceiptAmount() : BigDecimal.ZERO,
                        item.getCalcStatus() != null ? item.getCalcStatus() : INITIAL_STATUS,
                        null, item.getPaymentMethod());
                log.info("补建订单头 itemId={} orderId={} key={}", item.getId(), order.getId(), key);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发补建同一订单：唯一键兜底，读取胜出者
                order = findByOrderKey(item.getCorpId(), key);
                if (order == null) throw e;
                log.info("并发补建订单头，复用已有 orderId={} key={}", order.getId(), key);
            }
        }
        // 同一历史订单的全部无 order_id 行一起回填，避免财务核对/提成只看到当前行
        List<OpsBusinessData> unlinked = itemMapper.selectList(
                new LambdaQueryWrapper<OpsBusinessData>()
                        .eq(OpsBusinessData::getCorpId, item.getCorpId())
                        .isNull(OpsBusinessData::getOrderId)
                        .eq(OpsBusinessData::getDeleteFlag, 0));
        List<Long> ids = unlinked.stream()
                .filter(x -> key.equals(legacyOrderKey(x)))
                .map(OpsBusinessData::getId)
                .collect(Collectors.toList());
        if (ids.isEmpty()) ids = List.of(item.getId());
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(order.getId());
        args.addAll(ids);
        jdbcTemplate.update("UPDATE ops_business_order_item SET order_id = ? "
                + "WHERE id IN (" + placeholders + ")", args.toArray());
        item.setOrderId(order.getId());
        refreshTotals(order.getId());
        refreshAgentSummary(order.getId());
        syncStatus(order.getId());
        return order;
    }

    /** 与迁移脚本一致的归组键：订单编号 → 业务组号 → 收据号+批次+客户手机 → 单行。 */
    public static String legacyOrderKey(OpsBusinessData item) {
        if (hasText(item.getOrderNo())) return "O|" + item.getOrderNo().trim();
        if (hasText(item.getBusinessGroupNo())) return "G|" + item.getBusinessGroupNo().trim();
        if (hasText(item.getReceiptNo())) {
            return "R|" + item.getReceiptNo().trim() + "|"
                    + nullSafe(item.getBatchNo()) + "|" + nullSafe(item.getCustomerPhone());
        }
        return "I|" + item.getId();
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v.trim();
    }
}
