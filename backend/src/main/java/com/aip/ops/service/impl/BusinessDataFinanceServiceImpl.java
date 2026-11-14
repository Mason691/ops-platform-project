package com.aip.ops.service.impl;

import com.aip.ops.dto.FinanceCheckRequest;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.ops.service.BusinessDataFinanceService;
import com.aip.ops.service.BusinessOrderService;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 财务核对实现。
 *
 * <p>业务规则（本次落地口径）：
 * <ul>
 *   <li>权限：运营商管理员/超管，或当前组织内角色名含「财务」的财务角色；</li>
 *   <li>已上报集团：只读锁定，前端按钮置灰，后端再次拒绝；</li>
 *   <li>收据号：必填，同 corp 内仅允许同一客户的多行共用（同订单拆分），
 *       被其他客户占用时拦截（与导入侧组内一致性口径对齐）；</li>
 *   <li>财务收款日期：必填，不得早于市场收款日期（市场日期可解析时）；</li>
 *   <li>资金动态表横排数：可空，非空时 >=0 整数；</li>
 *   <li>是否收款：必填 是/否；凭证缺失仅前端提示，不强制补传。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessDataFinanceServiceImpl implements BusinessDataFinanceService {

    private static final String REPORTED_STATUS = "已上报集团";
    private static final int MAX_RECEIPT_NO_LENGTH = 50;
    private static final Pattern FLEX_DATE = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$");

    private final OpsBusinessDataMapper businessDataMapper;
    private final OpsBusinessOrderMapper orderMapper;
    private final BusinessOrderService orderService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> financeCheckByOrder(Long orderId, FinanceCheckRequest request,
                                                   Long operatorId, String operatorName) {
        OpsBusinessOrder order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        List<OpsBusinessData> items = orderMapper.selectItems(orderId);
        if (items.isEmpty()) {
            throw BusinessException.of("订单没有业务明细，不能财务核对");
        }
        String name = operatorName == null || operatorName.isBlank()
                ? resolveUserName(operatorId) : operatorName.trim();
        return doCheck(items.get(0), order, request, operatorId, name);
    }

    /** 财务核对公共实现：整单一起核对，避免部分明细已核对、部分未核对。 */
    private Map<String, Object> doCheck(OpsBusinessData row, OpsBusinessOrder order,
                                        FinanceCheckRequest request, Long userId,
                                        String userName) {
        List<OpsBusinessData> group = loadFinanceGroup(row, order);
        for (OpsBusinessData item : group) {
            if (REPORTED_STATUS.equals(item.getCalcStatus())) {
                throw BusinessException.of("该订单包含已上报集团数据，不能财务核对");
            }
        }
        List<Long> groupIds = group.stream().map(OpsBusinessData::getId).toList();
        ValidatedFinance v = validate(request, row, order, groupIds);
        String conclusion = normalizeConclusion(request == null ? null : request.getCheckConclusion());
        String remark = request == null ? null : trimToNull(request.getCheckRemark());
        if ("REJECT".equals(conclusion) && (remark == null || remark.isEmpty())) {
            throw BusinessException.of("核对不通过时必须填写原因");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = updateFinance(groupIds, v, userId, userName, now);
        if (updated != groupIds.size()) {
            throw BusinessException.of("订单内部分数据已上报集团或状态已变更，请刷新后重试");
        }
        // 订单级字段以主表为准，同时回写主表（含核对结论/备注）
        orderService.updateFinanceFields(order.getId(), v.receiptNo(), v.financeReceiptDate(),
                v.fundDynamicRows(), v.isReceived(), conclusion, remark, userId, userName, now);
        Map<String, Object> result = result(row.getId(), order.getId(), v, userId, userName, now);
        result.put("checkConclusion", conclusion);
        result.put("checkRemark", remark);
        return result;
    }

    /** 同一订单的全部明细（order_id 优先）；无 order_id 时按订单编号/业务组号/收据号+客户回退。 */
    private List<OpsBusinessData> loadFinanceGroup(OpsBusinessData row, OpsBusinessOrder order) {
        if (order != null && order.getId() != null) {
            List<OpsBusinessData> items = orderMapper.selectItems(order.getId());
            if (!items.isEmpty()) return items;
        }
        String orderNo = str(row.getOrderNo());
        String groupNo = str(row.getBusinessGroupNo());
        String receiptNo = str(row.getReceiptNo());
        if (orderNo.isEmpty() && groupNo.isEmpty() && receiptNo.isEmpty()) {
            return List.of(row);
        }
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        qw.eq(OpsBusinessData::getCorpId, row.getCorpId())
                .eq(OpsBusinessData::getDeleteFlag, 0);
        if (!orderNo.isEmpty()) {
            qw.eq(OpsBusinessData::getOrderNo, orderNo);
        } else if (!groupNo.isEmpty()) {
            qw.eq(OpsBusinessData::getBusinessGroupNo, groupNo);
        } else {
            // 历史导入同收据号多经办人拆分行：按收据号+客户视为同一订单
            qw.eq(OpsBusinessData::getReceiptNo, receiptNo)
                    .eq(OpsBusinessData::getCustomer, row.getCustomer());
        }
        List<OpsBusinessData> rows = businessDataMapper.selectList(qw);
        return rows.isEmpty() ? List.of(row) : rows;
    }

    private ValidatedFinance validate(FinanceCheckRequest req, OpsBusinessData row,
                                      OpsBusinessOrder order, List<Long> groupIds) {
        if (req == null) throw BusinessException.of("请求参数不能为空");
        String receiptNo = str(req.getReceiptNo());
        if (receiptNo.isEmpty()) throw BusinessException.of("收据号不能为空");
        if (receiptNo.length() > MAX_RECEIPT_NO_LENGTH) {
            throw BusinessException.of("收据号不能超过" + MAX_RECEIPT_NO_LENGTH + "个字符");
        }
        LocalDate financeDate = parseRequiredDate(req.getFinanceReceiptDate());
        String marketRaw = order != null && str(order.getReceiptDate()).length() > 0
                ? order.getReceiptDate() : row.getReceiptDate();
        LocalDate marketDate = parseOptionalDate(marketRaw);
        if (marketDate != null && financeDate.isBefore(marketDate)) {
            throw BusinessException.of("财务收款日期不得早于市场收款日期（" + marketDate + "）");
        }
        Integer fundRows = validateFundRows(req.getFundDynamicRows());
        String isReceived = str(req.getIsReceived());
        if (!"是".equals(isReceived) && !"否".equals(isReceived)) {
            throw BusinessException.of("请选择是否收款");
        }
        assertReceiptNoUnique(receiptNo, row, groupIds);
        return new ValidatedFinance(receiptNo, financeDate, fundRows, isReceived);
    }

    /**
     * 收据号查重（排除本订单全部子表行），与导入侧的「同收据号组内客户一致」口径对齐：
     * 同一客户的多张订单可共用收据号（同订单拆分），仅拦截被其他客户占用的收据号。
     * 以子表为准可覆盖旧 JAR 经兼容视图写入、尚未回填 order_id 的行。
     */
    private void assertReceiptNoUnique(String receiptNo, OpsBusinessData row,
                                       List<Long> groupIds) {
        if (row.getCorpId() == null) return;
        List<Map<String, Object>> dups;
        String customer = str(row.getCustomer());
        if (groupIds == null || groupIds.isEmpty()) {
            dups = jdbcTemplate.queryForList(
                    "SELECT batch_no, customer FROM ops_business_order_item "
                            + "WHERE corp_id = ? AND receipt_no = ? AND delete_flag = 0 "
                            + "AND id <> ? ORDER BY id LIMIT 20",
                    row.getCorpId(), receiptNo, row.getId());
        } else {
            String placeholders = String.join(",", Collections.nCopies(groupIds.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(row.getCorpId());
            args.add(receiptNo);
            args.addAll(groupIds);
            dups = jdbcTemplate.queryForList(
                    "SELECT batch_no, customer FROM ops_business_order_item "
                            + "WHERE corp_id = ? AND receipt_no = ? AND delete_flag = 0 "
                            + "AND id NOT IN (" + placeholders + ") ORDER BY id LIMIT 20",
                    args.toArray());
        }
        for (Map<String, Object> dup : dups) {
            if (!customer.equals(str(dup.get("customer")))) {
                throw BusinessException.of("收据号 " + receiptNo + " 已被其他客户占用（批次 "
                        + str(dup.get("batch_no")) + "，客户 " + str(dup.get("customer")) + "），请更换");
            }
        }
    }

    /** 资金动态表横排数：可空；非空必须是 >=0 的整数（JSON 小数 1.5 也拒绝）。 */
    private Integer validateFundRows(java.math.BigDecimal raw) {
        if (raw == null) return null;
        if (raw.signum() < 0 || raw.stripTrailingZeros().scale() > 0) {
            throw BusinessException.of("资金动态表横排数必须为大于等于0的整数");
        }
        try {
            return raw.intValueExact();
        } catch (ArithmeticException e) {
            throw BusinessException.of("资金动态表横排数必须为大于等于0的整数");
        }
    }

    private LocalDate parseRequiredDate(String raw) {
        String value = str(raw);
        if (value.isEmpty()) throw BusinessException.of("财务收款日期不能为空");
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            Matcher m = FLEX_DATE.matcher(value);
            if (m.matches()) {
                try {
                    return LocalDate.of(Integer.parseInt(m.group(1)),
                            Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                } catch (DateTimeException ignored) {
                    // fall through
                }
            }
            throw BusinessException.of("财务收款日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    /** 市场收款日期为存量 varchar，兼容 2026/1/2、2026.1.2 等写法；无法解析时不阻断比较。 */
    private LocalDate parseOptionalDate(String raw) {
        String value = str(raw);
        if (value.isEmpty()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            // try flexible formats
        }
        Matcher m = FLEX_DATE.matcher(value);
        if (m.matches()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (DateTimeException ignored) {
                // fall through
            }
        }
        return null;
    }

    /**
     * 显式 set 全部四项 + 核对人/时间 + 审计字段：
     * 资金动态表横排数允许被清空（null 也要写回），故不能用实体非空更新的默认策略。
     */
    private int updateFinance(List<Long> ids, ValidatedFinance v, Long userId,
                              String userName, LocalDateTime now) {
        LambdaUpdateWrapper<OpsBusinessData> uw = new LambdaUpdateWrapper<>();
        uw.in(OpsBusinessData::getId, ids)
                .eq(OpsBusinessData::getDeleteFlag, 0)
                .and(w -> w.isNull(OpsBusinessData::getCalcStatus)
                        .or().ne(OpsBusinessData::getCalcStatus, REPORTED_STATUS))
                .set(OpsBusinessData::getReceiptNo, v.receiptNo())
                .set(OpsBusinessData::getFinanceReceiptDate, v.financeReceiptDate())
                .set(OpsBusinessData::getFundDynamicRows, v.fundDynamicRows())
                .set(OpsBusinessData::getIsReceived, v.isReceived())
                .set(OpsBusinessData::getFinanceCheckUserId, userId)
                .set(OpsBusinessData::getFinanceCheckUserName, userName)
                .set(OpsBusinessData::getFinanceCheckTime, now)
                .set(OpsBusinessData::getModifyTime, now)
                .set(OpsBusinessData::getModifyUser, userId)
                .set(OpsBusinessData::getModifyUserName, userName);
        // 不在这里叠加数据范围：调用前已校验订单归属（公开入口 assertScope，
        // 工作流入口由任务归属保证）；内部回调线程没有 SecurityContext，
        // 叠加可见范围会退化为空集合导致 0 行更新。
        return businessDataMapper.update(null, uw);
    }

    private String resolveUserName(Long userId) {
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT real_name FROM ai_platform.sys_user WHERE id = ?", String.class, userId);
            if (!names.isEmpty()) {
                String name = str(names.get(0));
                if (!name.isEmpty()) return name;
            }
        } catch (Exception e) {
            log.debug("财务核对人姓名查询失败 userId={}: {}", userId, e.getMessage());
        }
        try {
            String username = SecurityUtils.getCurrentUser().getUsername();
            return username != null ? username : "";
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> result(Long id, Long orderId, ValidatedFinance v, Long userId,
                                       String userName, LocalDateTime now) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("orderId", orderId);
        result.put("receiptNo", v.receiptNo());
        result.put("financeReceiptDate", v.financeReceiptDate());
        result.put("fundDynamicRows", v.fundDynamicRows());
        result.put("isReceived", v.isReceived());
        result.put("financeCheckUserId", userId);
        result.put("financeCheckUserName", userName);
        result.put("financeCheckTime", now);
        return result;
    }

    private static String normalizeConclusion(String raw) {
        String value = str(raw).toUpperCase();
        if (value.isEmpty()) return "PASS";
        if (!"PASS".equals(value) && !"REJECT".equals(value) && !"HOLD".equals(value)) {
            throw BusinessException.of("核对结论必须是 PASS / REJECT / HOLD");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private record ValidatedFinance(String receiptNo, LocalDate financeReceiptDate,
                                    Integer fundDynamicRows, String isReceived) {}
}
