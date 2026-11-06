package com.aip.ops.service.impl;

import com.aip.ops.client.ScmBaseDataClient;
import com.aip.ops.dto.EmployeeBusinessEntryRequest;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.ops.security.JwtTokenInspector;
import com.aip.ops.service.EmployeeBusinessEntryService;
import com.aip.ops.service.GasMemberMatcher;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
import com.aip.sdk.security.SecurityUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 员工手工新增业务数据实现。
 *
 * <p>安全边界：corp_id / agent / agent_phone 全部由 JWT + 平台 sys_user 派生；
 * 批次必须属于当前组织且未上报集团；品牌/品类/型号必须命中 SCM 基础资料。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeBusinessEntryServiceImpl implements EmployeeBusinessEntryService {

    private static final String REPORTED_STATUS = "已上报集团";
    private static final String INITIAL_CALC_STATUS = "未计算";
    private static final int MAX_VOUCHERS = 3;
    private static final int MAX_TEXT_LENGTH = 100;
    private static final int MAX_DETAIL_ADDRESS_LENGTH = 200;
    /** sys_member.real_name / nickname 为 VARCHAR(50)，自动建会员时不能超长 */
    private static final int MAX_CUSTOMER_LENGTH = 50;

    private final JdbcTemplate jdbcTemplate;
    private final PlatformInternalClient platformInternalClient;
    private final OpsBusinessDataMapper businessDataMapper;
    private final OpsValueAddedProductMapper valueAddedProductMapper;
    private final GasMemberMatcher memberMatcher;
    private final ScmBaseDataClient scmClient;
    private final JwtTokenInspector jwtTokenInspector;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void initTransactionTemplate() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Map<String, Object> getManualEntryOptions() {
        EmployeeContext ctx = resolveEmployeeContext();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("corpId", ctx.corpId());
        result.put("corpName", ctx.corpName());
        result.put("company", ctx.company());
        result.put("employeeName", ctx.employeeName());
        result.put("employeePhone", ctx.employeePhone());
        result.put("batches", selectableBatches(ctx.corpId()));
        result.put("newBatchPrefix", ctx.company() + "-" + today() + "-");
        result.put("communities", loadCommunities(ctx.corpId()));
        fillProductOptions(result, ctx.corpId());
        return result;
    }

    @Override
    public Map<String, Object> lookupMember(String phone) {
        assertEmployee();
        String normalized = normalizePhone(phone);
        if (!GasMemberMatcher.PHONE_PATTERN.matcher(normalized).matches()) {
            throw BusinessException.of("请输入正确的11位大陆手机号");
        }
        try {
            Map<String, Object> info = memberMatcher.findMemberInfoByPhone(normalized);
            if (info == null) return Map.of("found", false);
            Long memberId = info.get("id") instanceof Number n ? n.longValue() : null;
            Long communityId = info.get("communityId") instanceof Number n ? n.longValue() : null;
            if (communityId == null) {
                communityId = findGasUserCommunity(memberId, normalized);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("memberId", memberId);
            result.put("name", info.get("name"));
            result.put("communityId", communityId);
            result.put("communityName", communityName(communityId));
            return result;
        } catch (IllegalStateException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    // ── 小区 / 详细地址 ──

    /** 当前员工所属组织的小区下拉（sys_community.corp_id 精确过滤）。 */
    private List<Map<String, Object>> loadCommunities(Long corpId) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id, community_name AS name FROM ai_platform.sys_community "
                            + "WHERE delete_flag = 0 AND corp_id = ? ORDER BY community_name",
                    corpId);
        } catch (Exception e) {
            log.warn("加载组织小区列表失败 corpId={}: {}", corpId, e.getMessage());
            return List.of();
        }
    }

    /** 会员未直接关联小区时，按手机号/会员ID 从燃气用户回退取小区。 */
    private Long findGasUserCommunity(Long memberId, String phone) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT community_id FROM ops_gas_user "
                            + "WHERE delete_flag = 0 AND community_id IS NOT NULL "
                            + "AND (phone = ? OR platform_member_id = ?) "
                            + "ORDER BY (platform_member_id = ?) DESC, id LIMIT 1",
                    phone, memberId, memberId);
            if (rows.isEmpty()) return null;
            Object value = rows.get(0).get("community_id");
            if (!(value instanceof Number n)) return null;
            return n.longValue() > 0 ? n.longValue() : null;
        } catch (Exception e) {
            log.debug("回退查询会员小区失败 memberId={}, phone={}: {}",
                    memberId, phone, e.getMessage());
            return null;
        }
    }

    private String communityName(Long communityId) {
        if (communityId == null || communityId <= 0) return null;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT community_name FROM ai_platform.sys_community "
                            + "WHERE id = ? AND delete_flag = 0 LIMIT 1", communityId);
            return rows.isEmpty() ? null : str(rows.get(0).get("community_name"));
        } catch (Exception e) {
            log.debug("查询小区名称失败 communityId={}: {}", communityId, e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Object> createManualEntry(EmployeeBusinessEntryRequest request) {
        EmployeeContext ctx = resolveEmployeeContext();
        Validated v = validateRequest(request, ctx.corpId());
        // 平台/SCM 的 HTTP 查询在事务外完成；事务只包裹批次取号+行锁、会员创建、INSERT，
        // 避免 Hikari 连接在外部 HTTP 期间被长期占用（连接池耗尽风险）。
        return transactionTemplate.execute(status -> persistManualEntry(request, ctx, v));
    }

    private Map<String, Object> persistManualEntry(EmployeeBusinessEntryRequest request,
                                                   EmployeeContext ctx, Validated v) {
        String batchNo = resolveBatch(request, ctx);
        MemberResolution member = resolveOrCreateMember(v.phone(), v.customer(), ctx.corpId());
        OpsBusinessData entity = buildEntity(ctx, v, batchNo, member.id());
        businessDataMapper.insert(entity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("batchNo", batchNo);
        result.put("receiptAmount", v.amount());
        result.put("platformMemberId", member.id());
        result.put("memberCreated", member.created());
        return result;
    }

    // ── 员工上下文 ──

    private EmployeeContext resolveEmployeeContext() {
        EmployeeIdentity identity = assertEmployee();
        CorpInfo corp = fetchCorpInfo(identity.corpId());
        return new EmployeeContext(identity.corpId(), corp.corpName(), corp.company(),
                identity.name(), identity.phone());
    }

    /** 校验当前 JWT 确为在职员工；返回组织 + 经办人姓名/手机号。 */
    private EmployeeIdentity assertEmployee() {
        if ("sso".equals(jwtTokenInspector.currentTokenType())) {
            throw BusinessException.of("仅员工账号可录入业务数据");
        }
        Long corpId = SecurityUtils.getCurrentCorpId();
        if (corpId == null || corpId <= 0) {
            throw BusinessException.of("请选择所属公司后再录入业务数据");
        }
        Map<String, Object> user = loadPlatformUser(SecurityUtils.getCurrentUserId());
        String username = str(user.get("username"));
        String userPhone = str(user.get("phone"));
        // 员工 JWT 的 username 来自 sys_user.username。会员 SSO JWT 的 sub 是
        // sys_member.id，恰与某个 sys_user.id 相同时不能仅凭 id 放行为员工。
        String jwtUsername = SecurityUtils.getCurrentUser().getUsername();
        if (!username.equals(jwtUsername) && !userPhone.equals(jwtUsername)) {
            throw BusinessException.of("仅员工账号可录入业务数据");
        }
        String phone = GasMemberMatcher.PHONE_PATTERN.matcher(username).matches()
                ? username : userPhone;
        if (!GasMemberMatcher.PHONE_PATTERN.matcher(phone).matches()) {
            throw BusinessException.of("员工账号未绑定有效手机号，请联系管理员");
        }
        String name = str(user.get("real_name"));
        if (name.isEmpty()) name = fallbackMemberName();
        if (name.isEmpty()) name = username;
        return new EmployeeIdentity(corpId, name, phone);
    }

    private Map<String, Object> loadPlatformUser(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT username, real_name, phone, status, delete_flag "
                        + "FROM ai_platform.sys_user WHERE id = ? LIMIT 1", userId);
        if (rows.isEmpty()) {
            throw BusinessException.of("仅员工账号可录入业务数据");
        }
        Map<String, Object> row = rows.get(0);
        if (num(row.get("status")) != 1 || num(row.get("delete_flag")) != 0) {
            throw BusinessException.of("员工账号已被禁用，请联系管理员");
        }
        return row;
    }

    /** 员工姓名缺失时，用 JWT member_id 关联的平台会员姓名兜底。 */
    private String fallbackMemberName() {
        try {
            Long memberId = SecurityUtils.getCurrentUser().getMemberId();
            if (memberId == null) return "";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT real_name, nickname FROM ai_platform.sys_member "
                            + "WHERE id = ? AND delete_flag = 0 LIMIT 1", memberId);
            if (rows.isEmpty()) return "";
            String realName = str(rows.get(0).get("real_name"));
            return realName.isEmpty() ? str(rows.get(0).get("nickname")) : realName;
        } catch (Exception e) {
            log.debug("员工姓名兜底查询失败: {}", e.getMessage());
            return "";
        }
    }

    private CorpInfo fetchCorpInfo(Long corpId) {
        try {
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/internal/corp/" + corpId, null);
            Object dataObj = resp.get("data");
            if (!(dataObj instanceof Map<?, ?> data)) {
                throw BusinessException.of("无法获取所属公司信息");
            }
            String name = str(data.get("name"));
            String shortName = str(data.get("shortName"));
            // 批次前缀优先用组织简称；简称缺失时沿用该组织历史业务数据的“公司”口径，
            // 最后才回退到全称，避免批次前缀与既有数据（临洮/康乐/…）不一致。
            String company = shortName.isEmpty() ? establishedCompany(corpId) : shortName;
            if (company.isEmpty()) company = name;
            if (company.isEmpty()) {
                throw BusinessException.of("所属公司名称为空，请联系管理员");
            }
            if (company.length() > 32) company = company.substring(0, 32);
            return new CorpInfo(name.isEmpty() ? company : name, company);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("获取组织信息失败 corpId={}: {}", corpId, e.getMessage());
            throw BusinessException.of("无法获取所属公司信息，请稍后重试");
        }
    }

    /** 组织历史业务数据中最常用的公司名（仅用于组织无简称时的批次前缀兜底）。 */
    private String establishedCompany(Long corpId) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT company FROM ops_business_data "
                            + "WHERE corp_id = ? AND delete_flag = 0 "
                            + "AND company IS NOT NULL AND company <> '' "
                            + "GROUP BY company ORDER BY COUNT(*) DESC LIMIT 1",
                    String.class, corpId);
            return rows.isEmpty() ? "" : str(rows.get(0));
        } catch (Exception e) {
            log.debug("查询组织历史公司名失败 corpId={}: {}", corpId, e.getMessage());
            return "";
        }
    }

    // ── 批次 ──

    private List<String> selectableBatches(Long corpId) {
        String sql = "SELECT batch_no FROM ops_business_data "
                + "WHERE corp_id = ? AND delete_flag = 0 "
                + "AND batch_no IS NOT NULL AND batch_no <> '' "
                + "GROUP BY batch_no "
                + "HAVING SUM(CASE WHEN calc_status = ? THEN 1 ELSE 0 END) = 0 "
                + "ORDER BY MAX(id) DESC LIMIT 200";
        return jdbcTemplate.queryForList(sql, String.class, corpId, REPORTED_STATUS);
    }

    private String resolveBatch(EmployeeBusinessEntryRequest req, EmployeeContext ctx) {
        if (Boolean.TRUE.equals(req.getNewBatch())) {
            return generateBatchNo(ctx);
        }
        String batchNo = str(req.getBatchNo());
        if (batchNo.isEmpty()) {
            throw BusinessException.of("请选择批次或新建批次");
        }
        if (batchNo.length() > 64) {
            throw BusinessException.of("批次号过长");
        }
        lockAndCheckBatch(ctx.corpId(), batchNo);
        return batchNo;
    }

    /**
     * 锁定批次现有行并检查是否已上报集团（SELECT ... FOR UPDATE，锁定读）。
     * 与「提成上报集团」更新串行化，避免“检查未上报 → 上报提交 → 新行插入”
     * 竞态产生部分上报批次；锁定读也保证能看到最新已提交的上报状态，
     * 不受 REPEATABLE READ 快照影响。空结果 = 批次不存在或不属于当前公司。
     */
    private void lockAndCheckBatch(Long corpId, String batchNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT calc_status FROM ops_business_data "
                        + "WHERE corp_id = ? AND batch_no = ? AND delete_flag = 0 FOR UPDATE",
                corpId, batchNo);
        if (rows.isEmpty()) {
            throw BusinessException.of("批次不存在或不属于当前公司");
        }
        for (Map<String, Object> row : rows) {
            if (REPORTED_STATUS.equals(str(row.get("calc_status")))) {
                throw BusinessException.of("该批次已上报集团，不能新增数据");
            }
        }
    }

    private String generateBatchNo(EmployeeContext ctx) {
        String date = today();
        String prefix = ctx.company() + "-" + date + "-";
        for (int attempt = 0; attempt < 100; attempt++) {
            int seq = nextSequence(ctx.corpId(), date);
            String batchNo = prefix + String.format(Locale.ROOT, "%03d", seq);
            if (countByBatch(ctx.corpId(), batchNo, null) == 0) {
                return batchNo;
            }
        }
        throw BusinessException.of("批次号生成失败，请稍后重试");
    }

    /**
     * 原子取号：唯一键 (corp_id, seq_date) + ON DUPLICATE KEY UPDATE 行锁串行化，
     * 避免并发 SELECT MAX+1 重号。失败时随事务回滚，不留下无主流水号。
     */
    private int nextSequence(Long corpId, String date) {
        jdbcTemplate.update("INSERT INTO ops_batch_sequence "
                        + "(corp_id, seq_date, last_seq, delete_flag) VALUES (?, ?, 1, 0) "
                        + "ON DUPLICATE KEY UPDATE last_seq = last_seq + 1",
                corpId, date);
        Integer seq = jdbcTemplate.queryForObject(
                "SELECT last_seq FROM ops_batch_sequence WHERE corp_id = ? AND seq_date = ?",
                Integer.class, corpId, date);
        if (seq == null || seq <= 0) {
            throw BusinessException.of("批次流水号生成失败，请稍后重试");
        }
        return seq;
    }

    private long countByBatch(Long corpId, String batchNo, String calcStatus) {
        String sql = "SELECT COUNT(*) FROM ops_business_data "
                + "WHERE corp_id = ? AND batch_no = ? AND delete_flag = 0"
                + (calcStatus != null ? " AND calc_status = ?" : "");
        Long count = calcStatus != null
                ? jdbcTemplate.queryForObject(sql, Long.class, corpId, batchNo, calcStatus)
                : jdbcTemplate.queryForObject(sql, Long.class, corpId, batchNo);
        return count != null ? count : 0L;
    }

    // ── 校验 ──

    private Validated validateRequest(EmployeeBusinessEntryRequest req, Long corpId) {
        if (req == null) throw BusinessException.of("请求参数不能为空");
        String phone = normalizePhone(req.getCustomerPhone());
        if (!GasMemberMatcher.PHONE_PATTERN.matcher(phone).matches()) {
            throw BusinessException.of("客户手机号须为11位大陆手机号");
        }
        String customer = requireText(req.getCustomer(), "客户名称", MAX_CUSTOMER_LENGTH);
        boolean special = Boolean.TRUE.equals(req.getIsSpecial());
        BigDecimal rate = validateCommissionRate(special, req.getCommissionRate());
        String brand = requireText(req.getBrand(), "品牌");
        String categoryName = requireText(req.getCategory(), "品类");
        String model = requireText(req.getModel(), "型号");
        BigDecimal price = normalizePrice(req.getSalePrice());
        int qty = req.getSaleQuantity() != null ? req.getSaleQuantity() : 0;
        if (qty < 1) throw BusinessException.of("销售数量必须为正整数");
        BigDecimal amount = price.multiply(BigDecimal.valueOf(qty))
                .setScale(2, RoundingMode.HALF_UP);
        String receiptDate = normalizeDate(req.getReceiptDate());
        boolean received = Boolean.TRUE.equals(req.getIsReceived());
        String voucherUrl = normalizeVouchers(received, req.getVoucherUrls());
        Long communityId = req.getCommunityId();
        if (communityId != null && communityId <= 0) communityId = null;
        // 小区选填（会员查询可自动带出），详细地址必填 —— 地址拆分后必须能定位到具体门牌
        String detailAddress = requireText(
                req.getDetailAddress(), "详细地址", MAX_DETAIL_ADDRESS_LENGTH);
        String categoryKey = scmClient.requireCategoryKey(corpId, categoryName);
        scmClient.requireMaterial(corpId, brand, categoryName, model);
        return new Validated(phone, customer, special, rate, brand, categoryKey,
                model, price, qty, amount, receiptDate, received, voucherUrl,
                communityId, detailAddress);
    }

    private BigDecimal validateCommissionRate(boolean special, BigDecimal raw) {
        if (!special) return BigDecimal.ZERO;
        if (raw == null) throw BusinessException.of("特批时提成比例不能为空");
        if (raw.signum() < 0 || raw.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw BusinessException.of("提成比例必须在0-100之间");
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizePrice(BigDecimal raw) {
        if (raw == null) throw BusinessException.of("销售单价不能为空");
        BigDecimal price = raw.setScale(2, RoundingMode.HALF_UP);
        if (price.signum() <= 0) throw BusinessException.of("销售单价必须大于0");
        return price;
    }

    private String normalizeDate(String raw) {
        String value = str(raw);
        if (value.isEmpty()) return LocalDate.now().toString();
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw BusinessException.of("市场收款日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    private String normalizeVouchers(boolean received, List<String> urls) {
        if (!received) return null;
        if (urls == null || urls.isEmpty()) {
            throw BusinessException.of("是否收款为是时必须上传收款凭证");
        }
        List<String> cleaned = new ArrayList<>();
        for (String url : urls) {
            String value = str(url);
            if (value.isEmpty()) continue;
            if (value.contains(",") || value.length() > 500
                    || !value.startsWith("/uploads/")) {
                throw BusinessException.of("收款凭证地址不合法");
            }
            cleaned.add(value);
        }
        if (cleaned.isEmpty()) {
            throw BusinessException.of("是否收款为是时必须上传收款凭证");
        }
        if (cleaned.size() > MAX_VOUCHERS) {
            throw BusinessException.of("收款凭证最多上传" + MAX_VOUCHERS + "张");
        }
        String joined = String.join(",", cleaned);
        if (joined.length() > 1000) {
            throw BusinessException.of("收款凭证地址过长");
        }
        return joined;
    }

    private static String requireText(String raw, String label) {
        return requireText(raw, label, MAX_TEXT_LENGTH);
    }

    private static String requireText(String raw, String label, int maxLength) {
        String value = str(raw);
        if (value.isEmpty()) throw BusinessException.of(label + "不能为空");
        if (value.length() > maxLength) {
            throw BusinessException.of(label + "不能超过" + maxLength + "个字符");
        }
        return value;
    }

    // ── 会员 / 落库 ──

    private MemberResolution resolveOrCreateMember(String phone, String customer, Long corpId) {
        try {
            // 与导入链路一致：先在本组织燃气公司范围的燃气用户中按手机号匹配
            // （可拿到燃气用户已关联的 platform_member_id，即使 sys_member 手机号不同），
            // 再走全局 uk_phone 复用，最后自动建会员。
            String gasCompanyId = memberMatcher.resolveGasCompanyId(corpId);
            Map<String, Long> gasMembers = memberMatcher.memberIdByPhone(
                    List.of(phone), gasCompanyId);
            Long gasMember = gasMembers.get(phone);
            if (gasMember != null) return new MemberResolution(gasMember, false);

            Long existing = memberMatcher.findMemberIdByPhone(phone);
            if (existing != null) return new MemberResolution(existing, false);
            Long created = memberMatcher.createMember(phone, customer, corpId,
                    "员工手工录入自动创建");
            return new MemberResolution(created, true);
        } catch (IllegalStateException e) {
            throw BusinessException.of(e.getMessage());
        }
    }

    private OpsBusinessData buildEntity(EmployeeContext ctx, Validated v,
                                        String batchNo, Long memberId) {
        OpsValueAddedProduct product = matchValueAddedProduct(
                v.brand(), v.categoryKey(), v.model());
        OpsBusinessData entity = new OpsBusinessData();
        entity.setCorpId(ctx.corpId());
        entity.setOperatorId(ctx.corpId());
        entity.setBatchNo(batchNo);
        entity.setCompany(ctx.company());
        entity.setCustomer(v.customer());
        entity.setCustomerPhone(v.phone());
        entity.setPlatformMemberId(memberId);
        CommunityInfo community = resolveCommunity(v.communityId(), ctx.corpId());
        entity.setCommunityId(community.id());
        entity.setCommunityName(community.name());
        entity.setDetailedAddress(v.detailAddress());
        entity.setBrand(v.brand());
        entity.setCategory(v.categoryKey());
        entity.setModel(v.model());
        entity.setSalePrice(v.price());
        entity.setSaleQuantity(v.qty());
        entity.setReceiptAmount(v.amount());
        // 复用导入口径匹配增值产品底价；未命中则留空，提成按规则全额比例（与导入未匹配行一致）
        entity.setRetailFloorPrice(product != null ? product.getRetailFloorPrice() : null);
        entity.setGroupLowestPrice(product != null ? product.getGroupLowestPrice() : null);
        // 销售日期取市场收款日期：员工业务数据列表按 sale_date 分月，避免新数据落入「未知」
        entity.setSaleDate(v.receiptDate());
        entity.setReceiptDate(v.receiptDate());
        entity.setFinanceReceiptDate(null);
        entity.setAgent(ctx.employeeName());
        entity.setAgentPhone(ctx.employeePhone());
        entity.setIsSpecial(v.special() ? "是" : "否");
        entity.setCommissionRate(v.rate());
        entity.setIsReceived(v.received() ? "是" : "否");
        entity.setVoucherUrl(v.voucherUrl());
        entity.setCalcStatus(INITIAL_CALC_STATUS);
        entity.setDeleteFlag(0);
        return entity;
    }

    /**
     * 小区选填；有值时按 id + corp 校验归属并取名称（不接受前端传来的名称，防止跨租户引用）。
     * 小区名称落库为冗余字段，供列表/导出/出库打印直接展示。
     */
    private CommunityInfo resolveCommunity(Long communityId, Long corpId) {
        if (communityId == null) return new CommunityInfo(null, null);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT community_name FROM ai_platform.sys_community "
                        + "WHERE id = ? AND corp_id = ? AND delete_flag = 0 LIMIT 1",
                communityId, corpId);
        if (rows.isEmpty()) {
            throw BusinessException.of("所选小区不属于当前组织，请重新选择");
        }
        String name = str(rows.get(0).get("community_name"));
        if (name.isEmpty()) {
            throw BusinessException.of("小区名称为空，请重新选择");
        }
        return new CommunityInfo(communityId, name);
    }

    /** 复用导入的精确/关键字匹配口径，命中增值产品时写入底价，保证提成分档与导入一致。 */
    private OpsValueAddedProduct matchValueAddedProduct(String brand, String categoryKey, String model) {
        try {
            List<OpsValueAddedProduct> products = valueAddedProductMapper.selectList(null);
            return BusinessDataServiceImpl.matchProductExactOrKeyword(
                    brand, categoryKey, model, products);
        } catch (Exception e) {
            log.warn("匹配增值产品底价失败: {}", e.getMessage());
            return null;
        }
    }

    private void fillProductOptions(Map<String, Object> result, Long corpId) {
        try {
            ScmBaseDataClient.ScmOptions opts = scmClient.options(corpId);
            result.put("brands", opts.brands());
            result.put("categories", opts.categories());
            result.put("materials", opts.materials());
            boolean empty = opts.brands().isEmpty() || opts.categories().isEmpty()
                    || opts.materials().isEmpty();
            result.put("productError", empty
                    ? "供应链基础资料为空，请联系管理员在供应链管理模块同步品牌/商品类别/商品信息"
                    : null);
        } catch (Exception e) {
            log.warn("员工手工录入加载供应链基础资料失败 corpId={}: {}", corpId, e.getMessage());
            result.put("brands", List.of());
            result.put("categories", List.of());
            result.put("materials", List.of());
            result.put("productError", e.getMessage() != null
                    ? e.getMessage() : "供应链基础资料加载失败，请稍后重试");
        }
    }

    // ── helpers ──

    private static String normalizePhone(String raw) {
        if (raw == null) return "";
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").replace("-", "").trim();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static int num(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String today() {
        return LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    // ── records ──

    private record EmployeeIdentity(Long corpId, String name, String phone) {}

    private record EmployeeContext(Long corpId, String corpName, String company,
                                   String employeeName, String employeePhone) {}

    private record CorpInfo(String corpName, String company) {}

    private record Validated(String phone, String customer, boolean special, BigDecimal rate,
                             String brand, String categoryKey, String model, BigDecimal price,
                             int qty, BigDecimal amount, String receiptDate, boolean received,
                             String voucherUrl, Long communityId, String detailAddress) {}

    private record MemberResolution(Long id, boolean created) {}

    private record CommunityInfo(Long id, String name) {}
}
