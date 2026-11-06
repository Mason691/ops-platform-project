package com.aip.ops.service.impl;

import com.aip.ops.dto.FinanceCheckRequest;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.service.BusinessDataFinanceService;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.FinancePermissionService;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
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
 *   <li>收据号：必填，同 corp 内唯一（与导入口径一致），重复直接拦截；</li>
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
    private final JdbcTemplate jdbcTemplate;
    private final DataScopeService dataScopeService;
    private final FinancePermissionService financePermissionService;

    @Override
    public Map<String, Object> financeCheck(Long id, FinanceCheckRequest request) {
        if (id == null) throw BusinessException.of("数据ID不能为空");
        if (!financePermissionService.canFinanceCheck()) {
            throw BusinessException.forbidden("仅财务角色或运营商管理员可执行财务核对");
        }
        OpsBusinessData row = businessDataMapper.selectById(id);
        if (row == null) throw BusinessException.notFound("业务数据不存在");
        assertScope(row);
        if (REPORTED_STATUS.equals(row.getCalcStatus())) {
            throw BusinessException.of("已上报集团的数据已锁定，不能财务核对");
        }

        ValidatedFinance v = validate(request, row);
        Long userId = SecurityUtils.getCurrentUserId();
        String userName = resolveUserName(userId);
        LocalDateTime now = LocalDateTime.now();
        int updated = updateFinance(id, v, userId, userName, now);
        if (updated == 0) {
            throw BusinessException.of("数据已上报集团或状态已变更，请刷新后重试");
        }
        return result(id, v, userId, userName, now);
    }

    private void assertScope(OpsBusinessData row) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible == null) return; // super admin
        if (row.getCorpId() == null || !visible.contains(row.getCorpId())) {
            throw BusinessException.forbidden("无权操作该业务数据");
        }
    }

    private ValidatedFinance validate(FinanceCheckRequest req, OpsBusinessData row) {
        if (req == null) throw BusinessException.of("请求参数不能为空");
        String receiptNo = str(req.getReceiptNo());
        if (receiptNo.isEmpty()) throw BusinessException.of("收据号不能为空");
        if (receiptNo.length() > MAX_RECEIPT_NO_LENGTH) {
            throw BusinessException.of("收据号不能超过" + MAX_RECEIPT_NO_LENGTH + "个字符");
        }
        LocalDate financeDate = parseRequiredDate(req.getFinanceReceiptDate());
        LocalDate marketDate = parseOptionalDate(row.getReceiptDate());
        if (marketDate != null && financeDate.isBefore(marketDate)) {
            throw BusinessException.of("财务收款日期不得早于市场收款日期（" + marketDate + "）");
        }
        Integer fundRows = validateFundRows(req.getFundDynamicRows());
        String isReceived = str(req.getIsReceived());
        if (!"是".equals(isReceived) && !"否".equals(isReceived)) {
            throw BusinessException.of("请选择是否收款");
        }
        assertReceiptNoUnique(receiptNo, row);
        return new ValidatedFinance(receiptNo, financeDate, fundRows, isReceived);
    }

    /** 收据号同 corp 内唯一（排除当前行），与 BusinessDataImportHandler 的查重口径一致。 */
    private void assertReceiptNoUnique(String receiptNo, OpsBusinessData row) {
        if (row.getCorpId() == null) return;
        List<Map<String, Object>> dups = jdbcTemplate.queryForList(
                "SELECT batch_no, customer FROM ops_business_data "
                        + "WHERE corp_id = ? AND receipt_no = ? AND id <> ? AND delete_flag = 0 "
                        + "ORDER BY id LIMIT 1",
                row.getCorpId(), receiptNo, row.getId());
        if (!dups.isEmpty()) {
            Map<String, Object> dup = dups.get(0);
            throw BusinessException.of("收据号 " + receiptNo + " 已存在（批次 "
                    + str(dup.get("batch_no")) + "，客户 " + str(dup.get("customer")) + "），请更换");
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
    private int updateFinance(Long id, ValidatedFinance v, Long userId,
                              String userName, LocalDateTime now) {
        LambdaUpdateWrapper<OpsBusinessData> uw = new LambdaUpdateWrapper<>();
        uw.eq(OpsBusinessData::getId, id)
                .eq(OpsBusinessData::getDeleteFlag, 0)
                .ne(OpsBusinessData::getCalcStatus, REPORTED_STATUS)
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
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null) uw.in(OpsBusinessData::getCorpId, visible);
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
        String username = SecurityUtils.getCurrentUser().getUsername();
        return username != null ? username : "";
    }

    private Map<String, Object> result(Long id, ValidatedFinance v, Long userId,
                                       String userName, LocalDateTime now) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("receiptNo", v.receiptNo());
        result.put("financeReceiptDate", v.financeReceiptDate());
        result.put("fundDynamicRows", v.fundDynamicRows());
        result.put("isReceived", v.isReceived());
        result.put("financeCheckUserId", userId);
        result.put("financeCheckUserName", userName);
        result.put("financeCheckTime", now);
        return result;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private record ValidatedFinance(String receiptNo, LocalDate financeReceiptDate,
                                    Integer fundDynamicRows, String isReceived) {}
}
