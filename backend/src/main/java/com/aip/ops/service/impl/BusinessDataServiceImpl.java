package com.aip.ops.service.impl;

import com.aip.sdk.exception.BusinessException;
import com.aip.ops.client.ScmPurchaseOrderClient;
import com.aip.ops.client.WorkflowPlatformClient;
import com.aip.ops.dto.ModelConfirmDecision;
import com.aip.ops.entity.OpsBusinessAttachment;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsBusinessOrderTradeIn;
import com.aip.ops.entity.OpsPersonalCommissionRule;
import com.aip.ops.entity.OpsValueAddedProduct;
import com.aip.ops.entity.SysImportTask;
import com.aip.ops.imports.BusinessDataImportHandler;
import com.aip.ops.imports.ImportResult;
import com.aip.ops.mapper.OpsBusinessAttachmentMapper;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.ops.mapper.OpsPersonalCommissionRuleMapper;
import com.aip.ops.mapper.OpsValueAddedProductMapper;
import com.aip.ops.service.BizStatusService;
import com.aip.ops.service.BusinessDataService;
import com.aip.ops.service.BusinessOrderRefundService;
import com.aip.ops.service.BusinessOrderService;
import com.aip.ops.service.DispatchEligibilityService;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.GasMemberMatcher;
import com.aip.ops.service.ImportTaskService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.security.LoginUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.text.Normalizer;

import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Objects;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BusinessDataServiceImpl implements BusinessDataService {

    private final OpsBusinessDataMapper mapper;
    private final OpsBusinessOrderMapper orderMapper;
    private final OpsBusinessAttachmentMapper attachmentMapper;
    private final OpsValueAddedProductMapper productMapper;
    private final OpsPersonalCommissionRuleMapper ruleMapper;
    private final DataScopeService dataScopeService;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformInternalClient platformInternalClient;
    private final BusinessDataImportHandler importHandler;
    private final ImportTaskService importTaskService;
    private final GasMemberMatcher memberMatcher;
    private final BusinessOrderService orderService;
    private final BusinessOrderRefundService refundService;
    private final BizStatusService bizStatusService;
    private final com.aip.ops.mapper.OpsBusinessFlowRecordMapper flowRecordMapper;
    private final DispatchEligibilityService dispatchEligibilityService;
    private final WorkflowPlatformClient workflowClient;
    private final ScmPurchaseOrderClient scmPurchaseOrderClient;
    private final com.aip.ops.mapper.OpsBusinessOrderTradeInMapper tradeInMapper;

    /** 锁定状态：已上报集团的数据禁止删除/修改/重新计算（所有组织一致） */
    public static final String REPORTED_STATUS = "已上报集团";

    /** 汇总 Excel 的公司列固定展示顺序（未在列表中的公司按名称排序追加） */
    private static final List<String> COMPANY_ORDER =
            List.of("临洮", "广河", "康乐", "武山", "彭阳", "德保");

    /** 汇总 Excel 的品类行固定展示顺序（字典 itemKey）：户内美装三品类（销售/工程/运维）
     *  6,8,9 连排相邻，定尺波纹管 7 后移至三元组之后，其余按 key 升序。
     *  未在映射中的品类追加在末尾。 */
    private static final List<String> CATEGORY_ORDER =
            List.of("1", "2", "3", "4", "5", "6", "8", "9", "7", "10", "11", "12");

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Dynamic category maps loaded from platform dict at first access. */
    private static volatile Map<String, String> CN_TO_KEY = null;
    public static volatile Map<String, String> KEY_TO_CN = null;

    static {
        // Init with hardcoded fallback maps (used when dict API is unreachable)
        Map<String, String> cn2key = new HashMap<>();
        cn2key.put("燃气灶", "1"); cn2key.put("抽油烟机", "2");
        cn2key.put("热水器", "3"); cn2key.put("壁挂炉", "4");
        cn2key.put("壁挂炉以旧换新销售", "5"); cn2key.put("户内美装销售", "6");
        cn2key.put("定尺波纹管", "7"); cn2key.put("户内美装工程", "8");
        cn2key.put("户内美装运维", "9"); cn2key.put("保险", "10");
        cn2key.put("美装管道整改", "11"); cn2key.put("美装安全评估", "12");
        cn2key.put("气密性检测", "qimi");
        CN_TO_KEY = Collections.unmodifiableMap(cn2key);
        Map<String, String> key2cn = new HashMap<>();
        cn2key.forEach((cn, k) -> key2cn.put(k, cn));
        KEY_TO_CN = Collections.unmodifiableMap(key2cn);
    }

    /** Category name cache from platform dict (volatile for DCL) */
    private volatile Set<String> categoryNameCache;
    private static final Set<String> DEFAULT_CATEGORIES = Set.of(
            "燃气灶", "抽油烟机", "热水器", "壁挂炉",
            "壁挂炉以旧换新销售", "户内美装销售", "定尺波纹管",
            "户内美装工程", "户内美装运维", "保险",
            "美装管道整改", "美装安全评估", "气密性检测"
    );

    public BusinessDataServiceImpl(
            OpsBusinessDataMapper mapper,
            OpsBusinessOrderMapper orderMapper,
            OpsBusinessAttachmentMapper attachmentMapper,
            OpsValueAddedProductMapper productMapper,
            OpsPersonalCommissionRuleMapper ruleMapper,
            DataScopeService dataScopeService,
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            PlatformInternalClient platformInternalClient,
            BusinessDataImportHandler importHandler,
            ImportTaskService importTaskService,
            GasMemberMatcher memberMatcher,
            BusinessOrderService orderService,
            BusinessOrderRefundService refundService,
            BizStatusService bizStatusService,
            com.aip.ops.mapper.OpsBusinessFlowRecordMapper flowRecordMapper,
            DispatchEligibilityService dispatchEligibilityService,
            WorkflowPlatformClient workflowClient,
            ScmPurchaseOrderClient scmPurchaseOrderClient,
            com.aip.ops.mapper.OpsBusinessOrderTradeInMapper tradeInMapper) {
        this.mapper = mapper;
        this.orderMapper = orderMapper;
        this.attachmentMapper = attachmentMapper;
        this.productMapper = productMapper;
        this.ruleMapper = ruleMapper;
        this.dataScopeService = dataScopeService;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.platformInternalClient = platformInternalClient;
        this.importHandler = importHandler;
        this.importTaskService = importTaskService;
        this.memberMatcher = memberMatcher;
        this.orderService = orderService;
        this.refundService = refundService;
        this.bizStatusService = bizStatusService;
        this.flowRecordMapper = flowRecordMapper;
        this.dispatchEligibilityService = dispatchEligibilityService;
        this.workflowClient = workflowClient;
        this.scmPurchaseOrderClient = scmPurchaseOrderClient;
        this.tradeInMapper = tradeInMapper;
    }
    public static String getCategoryDisplayName(String key) {
        if (key == null || key.isEmpty()) return "";
        return KEY_TO_CN.getOrDefault(key, key);
    }

    /**
     * 导出排序：收据号相同的行相邻（空/无收据号沉底），同收据号内按 id 升序，
     * 保证「收款金额首行显示」的首行 = 组内最早录入行。
     */
    public static void sortByReceiptNo(List<OpsBusinessData> rows) {
        rows.sort(Comparator
                .comparing((OpsBusinessData d) -> d.getReceiptNo() == null || d.getReceiptNo().isEmpty())
                .thenComparing(d -> d.getReceiptNo() == null ? "" : d.getReceiptNo())
                .thenComparing(OpsBusinessData::getId));
    }

    /**
     * 导出兜底：存量行型号只写关键字（如武山子公司）导入时无法精确匹配产品，
     * 价格为空。此处按「品牌+品类相同 + 产品型号含关键字」唯一命中产品后，
     * 内存填充零售底价/集团最低价——仅影响导出文件，不写库。
     */
    public static void fillExportPrices(List<OpsBusinessData> rows, List<OpsValueAddedProduct> products) {
        if (rows == null || rows.isEmpty() || products == null || products.isEmpty()) return;
        for (OpsBusinessData d : rows) {
            if (d.getRetailFloorPrice() != null && d.getGroupLowestPrice() != null) continue;
            OpsValueAddedProduct p = matchProductExactOrKeyword(d.getBrand(), d.getCategory(), d.getModel(), products);
            if (p == null) continue;
            if (d.getRetailFloorPrice() == null) d.setRetailFloorPrice(p.getRetailFloorPrice());
            if (d.getGroupLowestPrice() == null) d.setGroupLowestPrice(p.getGroupLowestPrice());
        }
    }

    /**
     * 品牌+品类必须一致；型号先做归一化精确匹配（去空格/转小写），
     * 否则退化关键字包含匹配。多个包含候选视为歧义 → 返回 null 不填充，避免错价。
     */
    public static OpsValueAddedProduct matchProductExactOrKeyword(
            String brand, String category, String model, List<OpsValueAddedProduct> products) {
        if (brand == null || brand.isBlank() || category == null || category.isBlank()
                || model == null || model.isBlank() || products == null) return null;
        String normModel = normalizeModel(model);
        if (normModel.isEmpty()) return null;
        List<OpsValueAddedProduct> containsMatches = new ArrayList<>();
        for (OpsValueAddedProduct p : products) {
            if (p == null || !brand.equals(p.getBrand()) || !category.equals(p.getCategory())) continue;
            if (p.getModel() == null || p.getModel().isBlank()) continue;
            String normPm = normalizeModel(p.getModel());
            if (normPm.equals(normModel)) return p;
            if (normPm.contains(normModel)) containsMatches.add(p);
        }
        return containsMatches.size() == 1 ? containsMatches.get(0) : null;
    }

    /** 型号归一化：NFKC 折叠全角 → 去空白/标点等特殊字符 → 转小写（与导入匹配口径一致） */
    private static String normalizeModel(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replaceAll("[^\\p{L}\\p{N}]", "")
                .toLowerCase(Locale.ROOT);
    }

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, String company, String batchNo, String agent, String receiptNo, String category, String isSpecial, String brand, String model, String receiptDateStart, String receiptDateEnd) {
        // Build visible corp IDs once
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (noVisibleCorp(visibleIds)) {
            return Map.of("records", List.of(), "total", 0,
                    "size", pageSize, "current", pageNum,
                    "totalSale", BigDecimal.ZERO, "totalCommission", BigDecimal.ZERO,
                    "categoryStats", List.of());
        }
        List<Long> corpIdList = (visibleIds != null && !visibleIds.isEmpty())
                ? new ArrayList<>(visibleIds) : null;

        // ── COUNT + STATS: single-row aggregate queries (no full table scan) ──
        long total = mapper.countFiltered(company, batchNo, agent, receiptNo, category, isSpecial, brand, model, receiptDateStart, receiptDateEnd, corpIdList);
        java.util.Map<String, java.math.BigDecimal> stats =
                mapper.aggregateStats(company, batchNo, agent, receiptNo, category, isSpecial, brand, model, receiptDateStart, receiptDateEnd, corpIdList);
        BigDecimal totalSale = stats != null && stats.get("totalSale") != null
                ? stats.get("totalSale") : BigDecimal.ZERO;
        BigDecimal totalCommission = stats != null && stats.get("totalCommission") != null
                ? stats.get("totalCommission") : BigDecimal.ZERO;

        // Per-category totals (same dedup rule as overall stats)
        List<Map<String, Object>> categoryStats =
                mapper.categoryStats(company, batchNo, agent, receiptNo, category, isSpecial, brand, model, receiptDateStart, receiptDateEnd, corpIdList);
        normalizeCategoryStats(categoryStats);

        // ── DATA: only fetch the requested page ──
        int offset = (pageNum - 1) * pageSize;
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        if (company != null && !company.isEmpty()) qw.eq(OpsBusinessData::getCompany, company);
        if (batchNo != null && !batchNo.isEmpty()) qw.eq(OpsBusinessData::getBatchNo, batchNo);
        if (agent != null && !agent.isEmpty()) qw.eq(OpsBusinessData::getAgent, agent);
        if (receiptNo != null && !receiptNo.isEmpty()) qw.eq(OpsBusinessData::getReceiptNo, receiptNo);
        if (category != null && !category.isEmpty()) qw.eq(OpsBusinessData::getCategory, category);
        if (isSpecial != null && !isSpecial.isEmpty()) qw.eq(OpsBusinessData::getIsSpecial, isSpecial);
        if (brand != null && !brand.isEmpty()) qw.eq(OpsBusinessData::getBrand, brand);
        if (model != null && !model.isEmpty()) qw.eq(OpsBusinessData::getModel, model);
        if (receiptDateStart != null && !receiptDateStart.isEmpty()) qw.ge(OpsBusinessData::getReceiptDate, receiptDateStart);
        if (receiptDateEnd != null && !receiptDateEnd.isEmpty()) qw.le(OpsBusinessData::getReceiptDate, receiptDateEnd);
        if (corpIdList != null) qw.in(OpsBusinessData::getCorpId, corpIdList);
        qw.orderByDesc(OpsBusinessData::getId);
        qw.last("LIMIT " + offset + "," + pageSize);
        List<OpsBusinessData> records = mapper.selectList(qw);

        return Map.of("records", records, "total", total,
                "size", pageSize, "current", pageNum,
                "totalSale", totalSale.setScale(2, RoundingMode.HALF_UP),
                "totalCommission", totalCommission.setScale(2, RoundingMode.HALF_UP),
                "categoryStats", categoryStats);
    }

    /**
     * 订单维度列表：按订单编号（无则业务组号/收据号/单行）聚合，
     * 组内按产品合并多经办人行，表头展示订单级公共字段与合计金额。
     * 当前数据量约万级，全量加载后在内存聚合；后续可按订单键 SQL 分页优化。
     */
    @Override
    public Map<String, Object> orderPage(int pageNum, int pageSize, String company, String batchNo,
                                         String agent, String receiptNo, String customer,
                                         String orderNo, String category, String isSpecial,
                                         String brand, String model, String receiptDateStart,
                                         String receiptDateEnd, String bizStatus) {
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (noVisibleCorp(visibleIds)) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("records", List.of());
            empty.put("total", 0);
            empty.put("size", pageSize);
            empty.put("current", pageNum);
            empty.put("totalSale", BigDecimal.ZERO);
            empty.put("totalCommission", BigDecimal.ZERO);
            empty.put("categoryStats", List.of());
            return empty;
        }
        List<Long> corpIdList = (visibleIds != null && !visibleIds.isEmpty())
                ? new ArrayList<>(visibleIds) : null;
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        if (hasText(company)) qw.eq(OpsBusinessData::getCompany, company);
        if (hasText(batchNo)) qw.eq(OpsBusinessData::getBatchNo, batchNo);
        if (hasText(receiptNo)) qw.eq(OpsBusinessData::getReceiptNo, receiptNo);
        if (hasText(customer)) qw.like(OpsBusinessData::getCustomer, customer.trim());
        // 订单编号支持精确/模糊查询（dd+每日流水，跨月可能同号，配合创建时间辨识）
        if (hasText(orderNo)) qw.like(OpsBusinessData::getOrderNo, orderNo.trim());
        if (hasText(category)) qw.eq(OpsBusinessData::getCategory, category);
        if (hasText(isSpecial)) qw.eq(OpsBusinessData::getIsSpecial, isSpecial);
        if (hasText(brand)) qw.eq(OpsBusinessData::getBrand, brand);
        if (hasText(model)) qw.eq(OpsBusinessData::getModel, model);
        if (hasText(receiptDateStart)) qw.ge(OpsBusinessData::getReceiptDate, receiptDateStart);
        if (hasText(receiptDateEnd)) qw.le(OpsBusinessData::getReceiptDate, receiptDateEnd);
        if (corpIdList != null) qw.in(OpsBusinessData::getCorpId, corpIdList);
        qw.orderByDesc(OpsBusinessData::getId);
        List<OpsBusinessData> rows = mapper.selectList(qw);

        Map<String, List<OpsBusinessData>> grouped = new LinkedHashMap<>();
        for (OpsBusinessData row : rows) {
            grouped.computeIfAbsent(orderKey(row), k -> new ArrayList<>()).add(row);
        }
        // 经办人筛选在订单级生效：保留包含该经办人的订单，再展示订单全部明细
        if (hasText(agent)) {
            String needle = agent.trim().toLowerCase(Locale.ROOT);
            grouped.entrySet().removeIf(e -> e.getValue().stream()
                    .noneMatch(r -> containsAgent(r, needle)));
        }
        List<Map<String, Object>> orders = new ArrayList<>();
        BigDecimal totalSale = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        Map<String, BigDecimal[]> categoryTotals = new LinkedHashMap<>();
        // 预载主页：订单级字段以主表为准（历史无 order_id 行由 buildOrderView 回退到子表首行）
        Set<Long> orderIds = grouped.values().stream()
                .map(g -> g.get(0).getOrderId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, OpsBusinessOrder> masters = orderService.findByIds(orderIds).stream()
                .collect(Collectors.toMap(OpsBusinessOrder::getId, x -> x, (a, b) -> a));
        for (List<OpsBusinessData> group : grouped.values()) {
            Map<String, Object> order = buildOrderView(group,
                    masters.get(group.get(0).getOrderId()));
            // 业务状态筛选在订单级生效（主表 biz_status）
            if (hasText(bizStatus)
                    && !bizStatus.trim().equals(String.valueOf(order.get("bizStatus")))) {
                continue;
            }
            orders.add(order);
            // 作废退款订单保留在列表供审计，但不计入销售/提成合计与品类汇总
            boolean voided = "-1".equals(String.valueOf(order.get("bizStatus")));
            if (!voided) {
                totalSale = totalSale.add((BigDecimal) order.get("totalAmount"));
                totalCommission = totalCommission.add((BigDecimal) order.get("totalCommission"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> products = (List<Map<String, Object>>) order.get("products");
                for (Map<String, Object> product : products) {
                    String key = Objects.toString(product.get("category"), "");
                    BigDecimal[] sums = categoryTotals.computeIfAbsent(key,
                            k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    sums[0] = sums[0].add((BigDecimal) product.get("receiptAmount"));
                    sums[1] = sums[1].add((BigDecimal) product.get("commissionAmount"));
                }
            }
        }
        orders.sort((a, b) -> Long.compare((Long) b.get("_maxId"), (Long) a.get("_maxId")));
        int total = orders.size();
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, pageSize);
        int from = Math.min(total, (safePage - 1) * safeSize);
        int to = Math.min(total, from + safeSize);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> order : orders.subList(from, to)) {
            order.remove("_maxId");
            records.add(order);
        }
        attachTradeInItems(records);
        List<Map<String, Object>> categoryStats = new ArrayList<>();
        categoryTotals.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0].compareTo(a.getValue()[0]))
                .forEach(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("category", e.getKey());
                    item.put("totalSale", e.getValue()[0].setScale(2, RoundingMode.HALF_UP));
                    item.put("totalCommission", e.getValue()[1].setScale(2, RoundingMode.HALF_UP));
                    categoryStats.add(item);
                });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("size", safeSize);
        result.put("current", safePage);
        result.put("totalSale", totalSale.setScale(2, RoundingMode.HALF_UP));
        result.put("totalCommission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        result.put("categoryStats", categoryStats);
        return result;
    }

    @Override
    public Map<String, Object> employeeOrderPage(Long corpId, String agentPhone, String batchNo,
                                                 String orderNo, String bizStatus,
                                                 int pageNum, int pageSize) {
        if (corpId == null || corpId <= 0 || !hasText(agentPhone)) {
            throw BusinessException.forbidden("缺少员工组织信息，请重新登录");
        }
        String phone = agentPhone.trim();
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        qw.eq(OpsBusinessData::getCorpId, corpId)
                .eq(OpsBusinessData::getAgentPhone, phone)
                .eq(OpsBusinessData::getDeleteFlag, 0)
                .eq(hasText(batchNo), OpsBusinessData::getBatchNo, batchNo)
                .like(hasText(orderNo), OpsBusinessData::getOrderNo, orderNo == null ? null : orderNo.trim())
                .orderByDesc(OpsBusinessData::getId);
        List<OpsBusinessData> rows = mapper.selectList(qw);
        Map<String, List<OpsBusinessData>> grouped = new LinkedHashMap<>();
        for (OpsBusinessData row : rows) {
            grouped.computeIfAbsent(orderKey(row), k -> new ArrayList<>()).add(row);
        }
        Set<Long> orderIds = grouped.values().stream()
                .map(group -> group.get(0).getOrderId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, OpsBusinessOrder> masters = orderService.findByIds(orderIds).stream()
                .filter(order -> corpId.equals(order.getCorpId()))
                .collect(Collectors.toMap(OpsBusinessOrder::getId, x -> x, (a, b) -> a));
        List<Map<String, Object>> orders = new ArrayList<>();
        BigDecimal totalSale = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        for (List<OpsBusinessData> group : grouped.values()) {
            Map<String, Object> order = buildOrderView(group,
                    masters.get(group.get(0).getOrderId()));
            if (hasText(bizStatus)
                    && !bizStatus.trim().equals(String.valueOf(order.get("bizStatus")))) {
                continue;
            }
            orders.add(order);
            // 作废退款订单保留在列表供审计，但不计入销售/提成合计
            if (!"-1".equals(String.valueOf(order.get("bizStatus")))) {
                totalSale = totalSale.add((BigDecimal) order.get("totalAmount"));
                totalCommission = totalCommission.add((BigDecimal) order.get("totalCommission"));
            }
        }
        orders.sort((a, b) -> Long.compare((Long) b.get("_maxId"), (Long) a.get("_maxId")));
        int total = orders.size();
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        int from = Math.min(total, (safePage - 1) * safeSize);
        int to = Math.min(total, from + safeSize);
        // 只对当前页订单查流程进度：避免一次把全部订单实例传给底座（上限100）
        List<Map<String, Object>> pageOrders = orders.subList(from, to);
        Set<Long> pageOrderIds = pageOrders.stream()
                .map(order -> order.get("orderId"))
                .filter(value -> value instanceof Number)
                .map(value -> ((Number) value).longValue())
                .collect(Collectors.toSet());
        Map<Long, String> pendingRefunds =
                refundService.pendingStatuses(new ArrayList<>(pageOrderIds));
        for (Map<String, Object> order : pageOrders) {
            Object oid = order.get("orderId");
            if (oid instanceof Number n && pendingRefunds.containsKey(n.longValue())) {
                order.put("refundStatus", "PENDING");
                order.put("refundStatusName", "作废审批中");
            }
        }
        Set<Long> instanceIds = pageOrders.stream()
                .map(order -> order.get("wfInstanceId"))
                .filter(value -> value instanceof Number)
                .map(value -> ((Number) value).longValue())
                .collect(Collectors.toSet());
        Map<Long, WorkflowPlatformClient.InstanceProgress> progress =
                workflowClient.instanceProgressBatch(new ArrayList<>(instanceIds));
        for (Map<String, Object> order : pageOrders) {
            order.put("flow", buildEmployeeFlow(order, progress));
            order.remove("_maxId");
        }
        attachTradeInItems(pageOrders);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", pageOrders);
        result.put("total", total);
        result.put("size", safeSize);
        result.put("current", safePage);
        result.put("totalSale", totalSale.setScale(2, RoundingMode.HALF_UP));
        result.put("totalCommission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        result.put("bizStatusOptions", bizStatusService.options());
        return result;
    }

    @Override
    public Map<String, Object> employeeOrderDetail(Long corpId, String agentPhone, Long orderId) {
        if (corpId == null || corpId <= 0 || !hasText(agentPhone) || orderId == null) {
            throw BusinessException.forbidden("缺少员工组织信息，请重新登录");
        }
        OpsBusinessOrder master = orderService.getById(orderId);
        if (master == null || Integer.valueOf(1).equals(master.getDeleteFlag())
                || !corpId.equals(master.getCorpId())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        qw.eq(OpsBusinessData::getOrderId, orderId)
                .eq(OpsBusinessData::getCorpId, corpId)
                .eq(OpsBusinessData::getAgentPhone, agentPhone.trim())
                .eq(OpsBusinessData::getDeleteFlag, 0)
                .orderByAsc(OpsBusinessData::getId);
        List<OpsBusinessData> rows = mapper.selectList(qw);
        if (rows.isEmpty()) {
            // 员工只能查看自己参与（经办）的订单，避免跨经办人/跨组织越权
            throw BusinessException.forbidden("无权查看该业务订单");
        }
        Map<String, Object> view = buildOrderView(rows, master);
        Set<Long> instanceIds = new LinkedHashSet<>();
        if (view.get("wfInstanceId") instanceof Number n) {
            instanceIds.add(n.longValue());
        }
        Map<Long, WorkflowPlatformClient.InstanceProgress> progress =
                workflowClient.instanceProgressBatch(new ArrayList<>(instanceIds));
        view.put("flow", buildEmployeeFlow(view, progress));
        view.put("bizStatusOptions", bizStatusService.options());
        view.putIfAbsent("receiptImageUrl", master != null ? master.getReceiptImageUrl() : null);
        view.putIfAbsent("paymentVoucherUrls", splitUrlCsv(
                master != null ? master.getPaymentVoucherUrls() : null));
        view.putIfAbsent("contractAttachmentUrls", splitUrlCsv(
                master != null ? master.getContractAttachmentUrls() : null));
        view.put("attachments", loadAttachments(orderId));
        view.remove("_maxId");
        List<com.aip.ops.entity.OpsBusinessFlowRecord> records = flowRecordMapper.selectList(
                new LambdaQueryWrapper<com.aip.ops.entity.OpsBusinessFlowRecord>()
                        .eq(com.aip.ops.entity.OpsBusinessFlowRecord::getOrderId, orderId)
                        .eq(com.aip.ops.entity.OpsBusinessFlowRecord::getDeleteFlag, 0)
                        .orderByDesc(com.aip.ops.entity.OpsBusinessFlowRecord::getId));
        view.put("flowRecords", records);
        view.put("draftFlag", master.getDraftFlag() == null ? 0 : master.getDraftFlag());
        view.put("refund", refundService.latestRefundView(orderId));
        view.put("canReturnDraft", refundService.canReturnDraft(master));
        view.put("canApplyRefund", refundService.canApplyRefund(master));
        view.put("canEdit", refundService.canEdit(master));
        view.put("canRestartFlow", refundService.canRestartFlow(master));
        attachTradeInItems(List.of(view));
        enrichTradeInStatus(view);
        return view;
    }

    @Override
    public void assertPendingDispatchTask(Long orderId, Long userId) {
        if (orderId == null || userId == null) {
            throw BusinessException.forbidden("缺少派工任务信息");
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ai_platform.wf_task t "
                        + "JOIN ai_platform.wf_instance i ON i.id = t.instance_id "
                        + "JOIN ai_platform.wf_definition_node n ON n.id = t.node_id "
                        + "WHERE t.approver_user_id = ? AND t.status = 'PENDING' "
                        + "AND t.delete_flag = 0 AND i.status = 'RUNNING' "
                        + "AND i.delete_flag = 0 AND n.delete_flag = 0 "
                        + "AND i.business_type = 'OPS_BUSINESS_ORDER' AND i.business_id = ? "
                        + "AND n.action_type = 'OPS_DISPATCH_OUTBOUND'",
                Long.class, userId, String.valueOf(orderId));
        if (count == null || count == 0) {
            throw BusinessException.forbidden("仅当前派工任务处理人可新增外部施工员");
        }
    }

    @Override
    public Map<String, Object> employeeTaskOrderDetail(Long userId, Long taskId) {
        if (userId == null || taskId == null) {
            throw BusinessException.forbidden("缺少员工/任务信息，请重新登录");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT i.business_id AS businessId "
                        + "FROM ai_platform.wf_task t "
                        + "JOIN ai_platform.wf_instance i ON i.id = t.instance_id "
                        + "JOIN mall_db.ops_business_order o "
                        + " ON o.id = CAST(i.business_id AS UNSIGNED) AND o.delete_flag = 0 "
                        + "WHERE t.id = ? AND t.approver_user_id = ? AND t.status = 'PENDING' "
                        + "AND t.delete_flag = 0 AND i.status = 'RUNNING' AND i.delete_flag = 0 "
                        + "AND i.business_type IN ('OPS_BUSINESS_ORDER',"
                        + "'OPS_BUSINESS_ORDER_VOID') LIMIT 1",
                taskId, userId);
        if (rows.isEmpty()) {
            throw BusinessException.forbidden("您不是该任务的待办处理人");
        }
        // 任务归属已验证；订单组织可能属于当前组织的下级（父组织派工员/部长处理子组织订单），
        // 也可能与外部施工员当前选择的组织不同，因此这里不再按当前组织数据范围拦截。
        Object businessId = rows.get(0).get("businessId");
        Long orderId = businessId instanceof Number num ? num.longValue()
                : Long.valueOf(String.valueOf(businessId));
        return buildOrderDetail(orderId, false);
    }

    /**
     * 订单流程进度：当前/下一节点取自工作流实例，业务状态名统一走字典；
     * 未发起/底座不可达分别给出明确文案，不影响订单其它字段展示。
     */
    private Map<String, Object> buildEmployeeFlow(Map<String, Object> order,
                                                  Map<Long, WorkflowPlatformClient.InstanceProgress> progress) {
        Map<String, Object> flow = new LinkedHashMap<>();
        Object raw = order.get("wfInstanceId");
        if (!(raw instanceof Number number)) {
            flow.put("status", "NOT_STARTED");
            flow.put("statusName", "未发起");
            flow.put("message", "流程未发起");
            flow.put("nextStatusName", "流程未发起");
            return flow;
        }
        Long instanceId = number.longValue();
        flow.put("instanceId", instanceId);
        WorkflowPlatformClient.InstanceProgress p = progress.get(instanceId);
        if (p == null) {
            flow.put("status", "UNKNOWN");
            flow.put("statusName", "待同步");
            flow.put("message", "流程信息待同步");
            flow.put("nextStatusName", "流程信息待同步");
            return flow;
        }
        String status = p.status() == null ? "" : p.status();
        flow.put("status", status);
        flow.put("statusName", employeeInstanceStatusLabel(status));
        flow.put("definitionName", p.definitionName());
        flow.put("currentNodeId", p.currentNodeId());
        flow.put("currentNodeName", p.currentNodeName());
        flow.put("currentNodeOrder", p.currentNodeOrder());
        flow.put("currentBizStatus", p.currentBizStatus());
        flow.put("currentBizStatusName", bizStatusService.label(p.currentBizStatus()));
        flow.put("nextNodeId", p.nextNodeId());
        flow.put("nextNodeName", p.nextNodeName());
        flow.put("nextNodeOrder", p.nextNodeOrder());
        flow.put("nextBizStatus", p.nextBizStatus());
        flow.put("nextStatusName", employeeNextStatusName(status, p.nextBizStatus(), p.nextNodeName()));
        flow.put("currentApprovers", p.currentApprovers());
        flow.put("currentApproval", p.currentApproval());
        flow.put("nextApprovers", p.nextApprovers());
        flow.put("nextApproval", p.nextApproval());
        flow.put("resolveStatus", p.resolveStatus());
        flow.put("resolveMessage", p.resolveMessage());
        flow.put("startedTime", p.startedTime());
        flow.put("finishedTime", p.finishedTime());
        return flow;
    }

    private String employeeNextStatusName(String status, String nextBizStatus, String nextNodeName) {
        if ("COMPLETED".equals(status)) return "流程已结束";
        if ("REJECTED".equals(status)) return "已驳回";
        if ("TERMINATED".equals(status)) return "已终止";
        if (hasText(nextBizStatus)) return bizStatusService.label(nextBizStatus);
        if (hasText(nextNodeName)) return nextNodeName;
        return "无下一状态";
    }

    private static String employeeInstanceStatusLabel(String status) {
        return switch (status) {
            case "RUNNING" -> "进行中";
            case "COMPLETED" -> "已完成";
            case "REJECTED" -> "已驳回";
            case "TERMINATED" -> "已终止";
            default -> status;
        };
    }

    @Override
    public List<Map<String, Object>> dispatchEligibility(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return List.of();
        List<Long> ids = orderIds.stream().filter(Objects::nonNull).distinct().limit(50).toList();
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null && visible.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (OpsBusinessOrder order : orderService.findByIds(ids)) {
            if (Integer.valueOf(1).equals(order.getDeleteFlag())) continue;
            if (visible != null && !visible.contains(order.getCorpId())) continue;
            DispatchEligibilityService.Eligibility e = dispatchEligibilityService.evaluate(order);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderId", e.orderId());
            item.put("orderNo", e.orderNo());
            item.put("canDispatch", e.canDispatch());
            item.put("visibleInDispatchTodo", e.visibleInDispatchTodo());
            item.put("fullPayment", e.fullPayment());
            item.put("financeChecked", e.financeChecked());
            item.put("balanceAmount", e.balanceAmount());
            item.put("reason", e.reason());
            item.put("dispatchBlockReason", e.reason());
            item.put("errorCode", e.errorCode());
            item.put("payType", e.payType());
            item.put("paymentStatus", e.paymentStatus());
            item.put("handlerName", e.handlerName());
            item.put("payTag", e.payTag());
            result.add(item);
        }
        return result;
    }

    @Override
    public Map<String, Object> orderDetail(Long orderId) {
        return buildOrderDetail(orderId, true);
    }

    /**
     * @param checkScope true=按当前登录组织的数据范围校验；
     *                   false 仅用于已校验待办任务归属的处理人（外部施工员可能
     *                   在多个组织任职，当前选择的组织不一定是订单组织）。
     */
    private Map<String, Object> buildOrderDetail(Long orderId, boolean checkScope) {
        OpsBusinessOrder order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw com.aip.sdk.exception.BusinessException.notFound("业务订单不存在");
        }
        if (checkScope) {
            Set<Long> visible = dataScopeService.getVisibleCorpIds();
            if (visible != null && (visible.isEmpty() || !visible.contains(order.getCorpId()))) {
                throw com.aip.sdk.exception.BusinessException.forbidden("无权查看该业务订单");
            }
        }
        List<OpsBusinessData> items = orderMapper.selectItems(orderId);
        Map<String, Object> view = items.isEmpty()
                ? new LinkedHashMap<>() : buildOrderView(items, order);
        view.putIfAbsent("orderId", order.getId());
        String status = order.getBizStatus() == null || order.getBizStatus().isBlank()
                ? "0" : order.getBizStatus();
        view.put("bizStatus", status);
        view.put("bizStatusName", bizStatusService.label(status));
        view.put("receiptImageUrl", order.getReceiptImageUrl());
        view.put("paymentVoucherUrls", splitUrlCsv(order.getPaymentVoucherUrls()));
        view.put("contractAttachmentUrls", splitUrlCsv(order.getContractAttachmentUrls()));
        view.put("attachments", loadAttachments(orderId));
        List<com.aip.ops.entity.OpsBusinessFlowRecord> records = flowRecordMapper.selectList(
                new LambdaQueryWrapper<com.aip.ops.entity.OpsBusinessFlowRecord>()
                        .eq(com.aip.ops.entity.OpsBusinessFlowRecord::getOrderId, orderId)
                        .eq(com.aip.ops.entity.OpsBusinessFlowRecord::getDeleteFlag, 0)
                        .orderByDesc(com.aip.ops.entity.OpsBusinessFlowRecord::getId));
        view.put("flowRecords", records);
        view.put("draftFlag", order.getDraftFlag() == null ? 0 : order.getDraftFlag());
        view.put("refund", refundService.latestRefundView(orderId));
        attachTradeInItems(List.of(view));
        enrichTradeInStatus(view);
        putDispatchEligibility(view, order);
        return view;
    }

    /** 派工资格与提示由后端统一下发，前端只渲染 canDispatch / dispatchBlockReason / payTag。 */
    private void putDispatchEligibility(Map<String, Object> view, OpsBusinessOrder order) {
        DispatchEligibilityService.Eligibility e = dispatchEligibilityService.evaluate(order);
        view.put("canDispatch", e.canDispatch());
        view.put("visibleInDispatchTodo", e.visibleInDispatchTodo());
        view.put("payType", e.payType());
        view.put("paymentStatus", e.paymentStatus());
        view.put("financeChecked", e.financeChecked());
        view.put("handlerName", e.handlerName());
        view.put("payTag", e.payTag());
        view.put("dispatchBlockReason", e.reason());
        view.put("dispatchBlockCode", e.errorCode());
    }

    @Override
    public Map<String, Object> orderDetailByNo(String orderNo) {
        String no = orderNo == null ? null : orderNo.trim();
        if (!hasText(no)) {
            throw com.aip.sdk.exception.BusinessException.notFound("业务订单不存在");
        }
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (visible != null && visible.isEmpty()) {
            throw com.aip.sdk.exception.BusinessException.forbidden("无权查看该业务订单");
        }
        OpsBusinessOrder master = findOrderByNo(no);
        if (master != null) {
            requireVisibleOrder(visible, master.getCorpId());
            // 主表存在时复用订单ID查询，保持与工作流节点页同一口径
            return orderDetail(master.getId());
        }
        List<OpsBusinessData> group = latestItemGroupByNo(no);
        if (group.isEmpty()) {
            throw com.aip.sdk.exception.BusinessException.notFound("业务订单不存在");
        }
        requireVisibleOrder(visible, group.get(0).getCorpId());
        Map<String, Object> view = buildOrderView(group, null);
        view.put("bizStatus", "0");
        view.put("bizStatusName", bizStatusService.label("0"));
        view.put("flowRecords", List.of());
        attachTradeInItems(List.of(view));
        enrichTradeInStatus(view);
        return view;
    }

    private OpsBusinessOrder findOrderByNo(String orderNo) {
        LambdaQueryWrapper<OpsBusinessOrder> qw = new LambdaQueryWrapper<>();
        qw.eq(OpsBusinessOrder::getOrderNo, orderNo)
                .orderByDesc(OpsBusinessOrder::getId)
                .last("LIMIT 1");
        return orderMapper.selectOne(qw);
    }

    /** 历史行可能未生成主表：按订单编号取最新一组子表行，避免多组混拼。 */
    private List<OpsBusinessData> latestItemGroupByNo(String orderNo) {
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        qw.eq(OpsBusinessData::getOrderNo, orderNo).orderByDesc(OpsBusinessData::getId);
        List<OpsBusinessData> rows = mapper.selectList(qw);
        if (rows.isEmpty()) return List.of();
        Map<String, List<OpsBusinessData>> groups = rows.stream()
                .collect(Collectors.groupingBy(BusinessDataServiceImpl::orderKey,
                        LinkedHashMap::new, Collectors.toList()));
        return groups.values().iterator().next();
    }

    private void requireVisibleOrder(Set<Long> visible, Long corpId) {
        if (visible != null && !visible.contains(corpId)) {
            throw com.aip.sdk.exception.BusinessException.forbidden("无权查看该业务订单");
        }
    }

    @Override
    public List<Map<String, Object>> flowAssignees(Long orderId, String keyword, Integer limit,
                                                   String roleName, Integer offset) {
        Set<Long> corpIds = resolveWorkerCorpIds(orderId);
        if (noVisibleCorp(corpIds)) return List.of();
        int safeLimit = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        int safeOffset = offset == null ? 0 : Math.max(offset, 0);
        return queryFlowAssignees(corpIds, keyword == null ? null : keyword.trim(), safeLimit,
                roleName == null ? null : roleName.trim(), safeOffset);
    }

    /** 派工可选员工范围：传订单时强制订单归属组织；否则取当前可见组织。 */
    private Set<Long> resolveWorkerCorpIds(Long orderId) {
        if (orderId == null) {
            return dataScopeService.getVisibleCorpIds();
        }
        OpsBusinessOrder order = orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw com.aip.sdk.exception.BusinessException.notFound("业务订单不存在");
        }
        requireVisibleOrder(dataScopeService.getVisibleCorpIds(), order.getCorpId());
        Long orderCorpId = order.getCorpId();
        return orderCorpId == null || orderCorpId <= 0 ? Set.of() : Set.of(orderCorpId);
    }

    /**
     * 可选员工 SQL：要求订单组织内的在职 sys_user_corp 任职，内部+外部都返回；
     * 施工员角色优先，其次姓名。手机号在 Java 侧统一脱敏。
     */
    private List<Map<String, Object>> queryFlowAssignees(Set<Long> corpIds, String keyword,
                                                         int limit, String roleName, int offset) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    buildAssigneeSql(corpIds, keyword, roleName),
                    buildAssigneeArgs(corpIds, keyword, roleName, limit, offset).toArray());
            rows.forEach(BusinessDataServiceImpl::decorateAssigneeRow);
            return rows;
        } catch (Exception e) {
            // 吞异常返回空列表会伪装成“该组织没有施工员”，派工员只会看到空白且无法排查；
            // 保留完整堆栈并 fail-closed，让前端展示可重试的错误提示。
            // 不记录 keyword：可能是手机号，避免 PII 落日志
            log.error("加载工作流派工可选员工失败 corpIds={}", corpIds, e);
            throw BusinessException.of("加载派工可选员工失败，请稍后重试；如持续失败请联系管理员");
        }
    }

    private static String buildAssigneeSql(Set<Long> corpIds, String keyword, String roleName) {
        StringBuilder sql = new StringBuilder(
                "SELECT u.id AS userId, uc.id AS userOrgId, "
                        + "COALESCE(NULLIF(u.real_name,''), NULLIF(u.nickname,''), u.username) AS userName, "
                        + "COALESCE(NULLIF(u.phone,''), u.username) AS phone, "
                        // TINYINT(1) 会被 Connector/J 映射为 Boolean，显式 CAST 保证 0/1 契约
                        + "CAST(u.user_type AS SIGNED) AS userType, uc.corp_id AS corpId, "
                        + "c.name AS corpName, "
                        + "GROUP_CONCAT(DISTINCT cr.role_name ORDER BY cr.id SEPARATOR '、') AS roleNames, "
                        + "(SELECT COUNT(1) FROM ai_platform.sys_user_wechat w "
                        + " WHERE w.user_id = u.id AND w.status = 1 AND w.delete_flag = 0) AS wechatBound "
                        + "FROM ai_platform.sys_user u "
                        + "JOIN ai_platform.sys_user_corp uc ON uc.user_id = u.id "
                        + " AND uc.status = 1 AND uc.delete_flag = 0 "
                        + "LEFT JOIN ai_platform.sys_corp c ON c.id = uc.corp_id AND c.delete_flag = 0 "
                        + "LEFT JOIN ai_platform.sys_user_corp_role ur ON ur.user_id = u.id "
                        + "LEFT JOIN ai_platform.sys_corp_user_role cr ON cr.id = ur.corp_role_id "
                        + " AND cr.corp_id = uc.corp_id AND cr.delete_flag = 0 "
                        + "WHERE u.status = 1 AND u.delete_flag = 0 ");
        if (corpIds != null && !corpIds.isEmpty()) {
            sql.append(" AND uc.corp_id IN (")
                    .append(String.join(",", Collections.nCopies(corpIds.size(), "?")))
                    .append(")");
        }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (u.phone LIKE ? OR u.username LIKE ? OR u.real_name LIKE ? "
                    + "OR u.nickname LIKE ?)");
        }
        sql.append(" GROUP BY u.id, uc.id, u.real_name, u.nickname, u.username, u.phone, "
                + "u.user_type, uc.corp_id, c.name");
        if (roleName != null && !roleName.isEmpty()) {
            // 施工工单只允许选施工员；内部/外部不限，按企业角色精确过滤
            sql.append(" HAVING SUM(CASE WHEN cr.role_name = ? THEN 1 ELSE 0 END) > 0");
        }
        // userName 可能重名，必须再按 u.id/uc.id 排序，否则 OFFSET 分页会跨页重复或漏人
        sql.append(" ORDER BY CASE WHEN SUM(CASE WHEN cr.role_name = '施工员' THEN 1 ELSE 0 END) > 0 "
                + " THEN 0 ELSE 1 END, userName, u.id, uc.id LIMIT ? OFFSET ?");
        return sql.toString();
    }

    private static List<Object> buildAssigneeArgs(Set<Long> corpIds, String keyword,
                                                  String roleName, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        if (corpIds != null && !corpIds.isEmpty()) {
            args.addAll(corpIds);
        }
        if (keyword != null && !keyword.isEmpty()) {
            String like = "%" + keyword + "%";
            args.addAll(List.of(like, like, like, like));
        }
        if (roleName != null && !roleName.isEmpty()) {
            args.add(roleName);
        }
        args.add(limit);
        args.add(offset);
        return args;
    }

    /** 手机号脱敏；roles/workerRole/wechatBound/userType 转成前端友好的类型。 */
    private static void decorateAssigneeRow(Map<String, Object> row) {
        String masked = com.aip.common.util.MaskUtil.maskPhone(strValue(row.get("phone")));
        // phone 保留兼容旧客户端；新字段 phoneMasked 与 WorkerVO 契约一致
        row.put("phone", masked);
        row.put("phoneMasked", masked);
        // 前端统一读取 userName；name 保留给历史调用方，避免升级期人员姓名空白
        Object userName = row.get("userName");
        row.put("name", userName);
        List<String> roles = splitRoles(row.get("roleNames"));
        row.put("roles", roles);
        row.put("workerRole", roles.contains("施工员"));
        row.put("wechatBound", intValue(row.get("wechatBound")) > 0);
        row.put("userType", intValue(row.get("userType")));
        row.put("corpName", strValue(row.get("corpName")));
    }

    /**
     * MySQL TINYINT(1) 经 Connector/J 会映射为 Boolean，直接强转 Number 会抛
     * ClassCastException（历史上导致派工候选列表整体为空）；这里统一收敛为 int。
     */
    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> splitRoles(Object raw) {
        if (raw == null) return List.of();
        return Arrays.stream(String.valueOf(raw).split("、"))
                .map(String::trim).filter(text -> !text.isEmpty()).toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 订单凭证附件（收据图片/支付凭证/合同附件），按上传顺序返回。 */
    private List<OpsBusinessAttachment> loadAttachments(Long orderId) {
        if (orderId == null) return List.of();
        return attachmentMapper.selectList(new LambdaQueryWrapper<OpsBusinessAttachment>()
                .eq(OpsBusinessAttachment::getOrderId, orderId)
                .eq(OpsBusinessAttachment::getDeleteFlag, 0)
                .orderByAsc(OpsBusinessAttachment::getId));
    }

    private static List<String> splitUrlCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .toList();
    }

    /** 订单键：主表 order_id 优先；历史无 order_id 行按订单编号/业务组号/收据号+客户回退。 */
    private static String orderKey(OpsBusinessData row) {
        if (row.getOrderId() != null) return "D|" + row.getOrderId();
        if (hasText(row.getOrderNo())) return "O|" + row.getOrderNo().trim();
        if (hasText(row.getBusinessGroupNo())) return "G|" + row.getBusinessGroupNo().trim();
        if (hasText(row.getReceiptNo())) {
            return "R|" + row.getReceiptNo().trim() + "|"
                    + Objects.toString(row.getCustomer(), "");
        }
        return "I|" + row.getId();
    }

    private static boolean containsAgent(OpsBusinessData row, String needle) {
        String agent = row.getAgent();
        String phone = row.getAgentPhone();
        return (agent != null && agent.toLowerCase(Locale.ROOT).contains(needle))
                || (phone != null && phone.toLowerCase(Locale.ROOT).contains(needle));
    }

    private Map<String, Object> buildOrderView(List<OpsBusinessData> rows, OpsBusinessOrder master) {
        rows.sort(Comparator.comparing(OpsBusinessData::getId));
        OpsBusinessData first = rows.get(0);
        Map<String, List<OpsBusinessData>> productGroups = new LinkedHashMap<>();
        for (OpsBusinessData row : rows) {
            // 退货分录按退货单单独成组，保证正/负分录并列展示且金额各计一次
            String key = (isReturnEntry(row) ? "RET|" + row.getSourceReturnId() + "|" : "")
                    + Objects.toString(row.getCategory(), "") + "|"
                    + Objects.toString(row.getBrand(), "") + "|"
                    + Objects.toString(row.getModel(), "") + "|"
                    + priceKey(row.getSalePrice());
            productGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> products = new ArrayList<>();
        BigDecimal computedAmount = BigDecimal.ZERO;
        BigDecimal computedCommission = BigDecimal.ZERO;
        for (List<OpsBusinessData> productRows : productGroups.values()) {
            Map<String, Object> product = buildProductView(productRows);
            products.add(product);
            computedAmount = computedAmount.add((BigDecimal) product.get("receiptAmount"));
            computedCommission = computedCommission.add((BigDecimal) product.get("commissionAmount"));
        }
        LinkedHashSet<String> agentNames = new LinkedHashSet<>();
        LinkedHashSet<String> agentPhones = new LinkedHashSet<>();
        for (OpsBusinessData row : rows) {
            if (hasText(row.getAgent())) agentNames.add(row.getAgent().trim());
            if (hasText(row.getAgentPhone())) agentPhones.add(row.getAgentPhone().trim());
        }
        int checkedCount = (int) rows.stream()
                .filter(r -> r.getFinanceCheckTime() != null).count();
        BigDecimal totalAmount = computedAmount;
        BigDecimal totalCommission = computedCommission;
        String calcStatus = master != null && hasText(master.getCalcStatus())
                ? master.getCalcStatus() : computeOrderStatus(rows);
        boolean hasReturn = rows.stream().anyMatch(BusinessDataServiceImpl::isReturnEntry);
        boolean financeChecked = checkedCount > 0 && checkedCount == rows.size();
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", master != null ? master.getId() : first.getOrderId());
        order.put("corpId", master != null ? master.getCorpId() : first.getCorpId());
        order.put("orderKey", orderKey(first));
        order.put("orderNo", pick(master != null ? master.getOrderNo() : null,
                rows, OpsBusinessData::getOrderNo));
        order.put("batchNo", pick(master != null ? master.getBatchNo() : null,
                rows, OpsBusinessData::getBatchNo));
        order.put("receiptNo", pick(master != null ? master.getReceiptNo() : null,
                rows, OpsBusinessData::getReceiptNo));
        order.put("customer", pick(master != null ? master.getCustomer() : null,
                rows, OpsBusinessData::getCustomer));
        order.put("customerPhone", pick(master != null ? master.getCustomerPhone() : null,
                rows, OpsBusinessData::getCustomerPhone));
        order.put("communityName", pick(master != null ? master.getCommunityName() : null,
                rows, OpsBusinessData::getCommunityName));
        order.put("detailedAddress", pick(master != null ? master.getDetailedAddress() : null,
                rows, OpsBusinessData::getDetailedAddress));
        order.put("company", pick(master != null ? master.getCompany() : null,
                rows, OpsBusinessData::getCompany));
        order.put("agents", new ArrayList<>(agentNames));
        order.put("agentNames", String.join("、", agentNames));
        order.put("agentPhones", String.join("、", agentPhones));
        order.put("receiptDate", pick(master != null ? master.getReceiptDate() : null,
                rows, OpsBusinessData::getReceiptDate));
        order.put("financeReceiptDate", pickNonNull(
                master != null ? master.getFinanceReceiptDate() : null,
                rows, OpsBusinessData::getFinanceReceiptDate));
        order.put("createTime", firstNonNull(rows, OpsBusinessData::getCreateTime));
        order.put("fundDynamicRows", pickNonNull(
                master != null ? master.getFundDynamicRows() : null,
                rows, OpsBusinessData::getFundDynamicRows));
        order.put("isReceived", pick(master != null ? master.getIsReceived() : null,
                rows, OpsBusinessData::getIsReceived));
        String paymentMethod = master != null && hasText(master.getPaymentMethod())
                ? master.getPaymentMethod() : firstText(rows, OpsBusinessData::getPaymentMethod);
        order.put("paymentMethod", paymentMethod);
        order.put("paymentMethodName", com.aip.ops.util.PaymentMethod.labelOf(paymentMethod));
        // 收款方式（与 paymentMethod 的二维码/现金/转账是两个维度）
        String paymentType = master != null && hasText(master.getPaymentType())
                ? master.getPaymentType() : "FULL";
        order.put("paymentType", paymentType);
        order.put("paymentTypeName", "DEPOSIT".equals(paymentType) ? "定金支付" : "全款支付");
        order.put("isFullPayment", master != null && master.getIsFullPayment() != null
                ? master.getIsFullPayment() : 1);
        order.put("depositAmount", master != null && master.getDepositAmount() != null
                ? master.getDepositAmount().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        order.put("balanceAmount", master != null && master.getBalanceAmount() != null
                ? master.getBalanceAmount().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        order.put("balanceReceivedAmount", master != null ? master.getBalanceReceivedAmount() : null);
        order.put("balancePaidAt", master != null ? master.getBalancePaidAt() : null);
        order.put("balanceVoucherUrl", master != null ? master.getBalanceVoucherUrl() : null);
        order.put("balanceRemark", master != null ? master.getBalanceRemark() : null);
        order.put("balancePaymentMethod", master != null ? master.getBalancePaymentMethod() : null);
        order.put("balancePaymentMethodName", master == null ? null
                : com.aip.ops.util.PaymentMethod.labelOf(master.getBalancePaymentMethod()));
        order.put("balanceReceiptDate", master != null ? master.getBalanceReceiptDate() : null);
        order.put("balanceReceiptNo", master != null ? master.getBalanceReceiptNo() : null);
        order.put("depositReceiptDate", master != null ? master.getReceiptDate() : null);
        order.put("voucherUrl", pick(master != null ? master.getVoucherUrl() : null,
                rows, OpsBusinessData::getVoucherUrl));
        order.put("receiptImageUrl", master != null ? master.getReceiptImageUrl() : null);
        order.put("paymentVoucherUrls", splitUrlCsv(
                master != null ? master.getPaymentVoucherUrls() : null));
        order.put("contractAttachmentUrls", splitUrlCsv(
                master != null ? master.getContractAttachmentUrls() : null));
        order.put("calcStatus", calcStatus);
        // 业务状态与计算状态完全解耦：业务状态只看主表工作流字段
        String bizStatus = master != null && hasText(master.getBizStatus())
                ? master.getBizStatus() : "0";
        order.put("bizStatus", bizStatus);
        order.put("bizStatusName", bizStatusService.label(bizStatus));
        order.put("draftFlag", master != null && Integer.valueOf(1).equals(master.getDraftFlag())
                ? 1 : 0);
        order.put("wfInstanceId", master != null ? master.getWfInstanceId() : null);
        order.put("wfNodeOrder", master != null ? master.getWfNodeOrder() : null);
        order.put("wfNodeName", master != null ? master.getWfNodeName() : null);
        order.put("financeCheckConclusion",
                master != null ? master.getFinanceCheckConclusion() : null);
        order.put("financeCheckRemark",
                master != null ? master.getFinanceCheckRemark() : null);
        // 订单备注：手工录入选填，导入单沿用子表备注；主表为空时取子表首行兜底
        order.put("remark", master != null && master.getRemark() != null
                ? master.getRemark()
                : (rows.isEmpty() ? null : rows.get(0).getRemark()));
        order.put("financeChecked", financeChecked);
        order.put("financeCheckedCount", checkedCount);
        order.put("financeCheckedTotal", rows.size());
        order.put("financeCheckTime", pickNonNull(
                master != null ? master.getFinanceCheckTime() : null,
                rows, OpsBusinessData::getFinanceCheckTime));
        order.put("financeCheckUserName", pick(
                master != null ? master.getFinanceCheckUserName() : null,
                rows, OpsBusinessData::getFinanceCheckUserName));
        order.put("hasUnmatchedMember",
                rows.stream().anyMatch(r -> r.getPlatformMemberId() == null));
        order.put("totalAmount", totalAmount.setScale(2, RoundingMode.HALF_UP));
        order.put("totalCommission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        BigDecimal tradeInDeduction = products.stream()
                .map(product -> (BigDecimal) product.get("deductionAmount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean hasTradeIn = products.stream().anyMatch(product -> "是".equals(product.get("isTradeIn")));
        order.put("hasTradeIn", hasTradeIn);
        order.put("tradeInDeductionAmount", tradeInDeduction.setScale(2, RoundingMode.HALF_UP));
        order.put("payableAmount", totalAmount.subtract(tradeInDeduction)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        order.put("tradeInItems", new ArrayList<>());
        order.put("tradeInSummary", "");
        order.put("tradeInPurchaseStatus", master != null ? master.getTradeInPurchaseStatus() : null);
        order.put("tradeInPurchaseOrderId", master != null ? master.getTradeInPurchaseOrderId() : null);
        order.put("tradeInPurchaseBillNo", master != null ? master.getTradeInPurchaseBillNo() : null);
        order.put("tradeInPurchaseError", master != null ? master.getTradeInPurchaseError() : null);
        order.put("productCount", products.size());
        order.put("products", products);
        order.put("hasReturn", hasReturn);
        order.put("category", products.isEmpty() ? null : products.get(0).get("category"));
        // 兼容财务核对弹窗/卡片的单产品字段：取首个产品；多产品以 productCount/totalAmount 提示
        if (!products.isEmpty()) {
            Map<String, Object> p0 = products.get(0);
            order.put("brand", p0.get("brand"));
            order.put("model", p0.get("model"));
            order.put("salePrice", p0.get("salePrice"));
            order.put("saleQuantity", p0.get("saleQuantity"));
            order.put("receiptAmount", p0.get("receiptAmount"));
            order.put("commissionAmount", p0.get("commissionAmount"));
            order.put("commissionRate", p0.get("commissionRate"));
            order.put("isSpecial", p0.get("isSpecial"));
            order.put("retailFloorPrice", p0.get("retailFloorPrice"));
            order.put("groupLowestPrice", p0.get("groupLowestPrice"));
        }
        order.put("agent", String.join("、", agentNames));
        order.put("agentPhone", String.join("、", agentPhones));
        order.put("id", first.getId());
        order.put("representativeId", first.getId());
        order.put("rowIds", rows.stream().map(OpsBusinessData::getId).toList());
        order.put("_maxId", rows.stream().mapToLong(OpsBusinessData::getId).max().orElse(0L));
        return order;
    }

    /** 批量挂载旧品明细：订单级 tradeInItems + 按 productKey 挂到商品行。 */
    private void attachTradeInItems(List<Map<String, Object>> orders) {
        if (orders == null || orders.isEmpty()) return;
        Set<Long> orderIds = orders.stream()
                .map(order -> order.get("orderId"))
                .filter(value -> value instanceof Number)
                .map(value -> ((Number) value).longValue())
                .collect(Collectors.toSet());
        if (orderIds.isEmpty()) return;
        List<OpsBusinessOrderTradeIn> rows = tradeInMapper.selectList(
                new LambdaQueryWrapper<OpsBusinessOrderTradeIn>()
                        .in(OpsBusinessOrderTradeIn::getOrderId, orderIds)
                        .eq(OpsBusinessOrderTradeIn::getDeleteFlag, 0)
                        .orderByAsc(OpsBusinessOrderTradeIn::getId));
        Map<Long, List<OpsBusinessOrderTradeIn>> byOrder = rows.stream()
                .collect(Collectors.groupingBy(OpsBusinessOrderTradeIn::getOrderId,
                        LinkedHashMap::new, Collectors.toList()));
        for (Map<String, Object> order : orders) {
            Long orderId = order.get("orderId") instanceof Number n ? n.longValue() : null;
            List<OpsBusinessOrderTradeIn> tradeIns = orderId == null
                    ? List.of() : byOrder.getOrDefault(orderId, List.of());
            List<Map<String, Object>> items = tradeIns.stream()
                    .map(this::tradeInItemView).toList();
            order.put("tradeInItems", items);
            order.put("tradeInSummary", tradeInSummary(items));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> products = (List<Map<String, Object>>) order.get("products");
            if (products == null) continue;
            Map<String, List<Map<String, Object>>> byProduct = items.stream()
                    .filter(item -> item.get("productKey") != null)
                    .collect(Collectors.groupingBy(item -> String.valueOf(item.get("productKey")),
                            LinkedHashMap::new, Collectors.toList()));
            for (Map<String, Object> product : products) {
                product.put("tradeInItems", byProduct.getOrDefault(
                        String.valueOf(product.get("productKey")), List.of()));
            }
        }
    }

    private Map<String, Object> tradeInItemView(OpsBusinessOrderTradeIn row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getId());
        item.put("productKey", row.getProductKey());
        item.put("oldProductId", row.getOldProductId());
        item.put("materialNumber", row.getOldMaterialNumber());
        item.put("productName", row.getOldProductName());
        item.put("brand", row.getBrand());
        item.put("model", row.getModel());
        item.put("category", row.getCategory());
        item.put("quantity", row.getQuantity());
        item.put("unit", row.getUnit());
        item.put("remark", row.getRemark());
        item.put("isManual", Integer.valueOf(1).equals(row.getIsManual()));
        return item;
    }

    private static String tradeInSummary(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < items.size() && i < 5; i++) {
            Map<String, Object> item = items.get(i);
            parts.add(strValue(item.get("productName")) + " ×" + item.get("quantity"));
        }
        if (items.size() > 5) parts.add("等" + items.size() + "行");
        return String.join("、", parts);
    }

    private static String strValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 订单详情实时补充 SCM 采购单状态：旧品入库完成（inboundStatus=ALL）时
     * 施工完成提醒显示“已入库”；SCM 不可达显示“状态待同步”，不阻断详情。
     */
    private void enrichTradeInStatus(Map<String, Object> view) {
        if (!Boolean.TRUE.equals(view.get("hasTradeIn"))) return;
        if (!(view.get("tradeInPurchaseOrderId") instanceof Number pid)) return;
        Long corpId = view.get("corpId") instanceof Number c ? c.longValue() : null;
        ScmPurchaseOrderClient.TradeInPurchaseStatus status =
                scmPurchaseOrderClient.status(corpId, pid.longValue());
        if (status == null) {
            view.put("tradeInInboundStatusName", "状态待同步");
            return;
        }
        view.put("tradeInInboundStatus", status.inboundStatus());
        view.put("tradeInInboundStatusName", switch (String.valueOf(status.inboundStatus())) {
            case "ALL" -> "旧品已入库";
            case "PARTIAL" -> "旧品部分入库";
            default -> "旧品未入库";
        });
        view.put("tradeInAuditStatus", status.auditStatus());
        view.put("tradeInApprovalStatus", status.approvalStatus());
        view.put("tradeInAutoApprovedAt", status.autoApprovedAt());
        view.put("tradeInAutoApproveTrigger", status.autoApproveTrigger());
        view.put("tradeInAutoApproveError", status.autoApproveError());
        view.put("tradeInPurchaseSyncStatus", status.syncStatus());
        view.put("tradeInPurchasePushError", status.pushError());
        view.put("tradeInPurchaseJdyBillno", status.jdyBillno());
    }

    private static String pick(String masterValue, List<OpsBusinessData> rows,
                               Function<OpsBusinessData, String> getter) {
        return hasText(masterValue) ? masterValue : firstText(rows, getter);
    }

    /** 价格归一化：10.00 与 10 视为同一价格（与主表合计/导入归组口径一致）。 */
    private static String priceKey(BigDecimal price) {
        return price == null ? "" : price.stripTrailingZeros().toPlainString();
    }

    private static <T> T pickNonNull(T masterValue, List<OpsBusinessData> rows,
                                     Function<OpsBusinessData, T> getter) {
        return masterValue != null ? masterValue : firstNonNull(rows, getter);
    }

    private Map<String, Object> buildProductView(List<OpsBusinessData> rows) {
        rows.sort(Comparator.comparing(OpsBusinessData::getId));
        OpsBusinessData first = rows.get(0);
        boolean isReturn = isReturnEntry(first);
        // 多经办人行共享同一数量/金额：正数取 MAX、退货负数取 MIN，避免重复计数
        Comparator<BigDecimal> amountOrder = isReturn
                ? Comparator.reverseOrder() : Comparator.naturalOrder();
        BigDecimal amount = rows.stream().map(OpsBusinessData::getReceiptAmount)
                .filter(Objects::nonNull).max(amountOrder)
                .orElse(BigDecimal.ZERO);
        BigDecimal commission = rows.stream().map(OpsBusinessData::getCommissionAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deduction = rows.stream().map(OpsBusinessData::getDeductionAmount)
                .filter(Objects::nonNull).max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        BigDecimal payable = amount.subtract(deduction).max(BigDecimal.ZERO);
        boolean tradeIn = rows.stream().anyMatch(row -> "是".equals(row.getIsTradeIn()));
        BigDecimal rate = rows.stream().map(OpsBusinessData::getCommissionRate)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        Integer quantity = rows.stream().map(OpsBusinessData::getSaleQuantity)
                .filter(Objects::nonNull)
                .min(isReturn ? Comparator.naturalOrder() : Comparator.reverseOrder())
                .orElse(null);
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("productKey", (isReturn ? "RET|" + first.getSourceReturnId() + "|" : "")
                + Objects.toString(first.getCategory(), "") + "|"
                + Objects.toString(first.getBrand(), "") + "|"
                + Objects.toString(first.getModel(), "") + "|"
                + priceKey(first.getSalePrice()));
        product.put("brand", first.getBrand());
        product.put("category", first.getCategory());
        product.put("categoryName", KEY_TO_CN.getOrDefault(
                Objects.toString(first.getCategory(), ""), first.getCategory()));
        product.put("model", first.getModel());
        product.put("salePrice", first.getSalePrice());
        product.put("saleQuantity", quantity);
        product.put("receiptAmount", amount.setScale(2, RoundingMode.HALF_UP));
        product.put("deductionAmount", deduction.setScale(2, RoundingMode.HALF_UP));
        product.put("payableAmount", payable.setScale(2, RoundingMode.HALF_UP));
        product.put("isTradeIn", tradeIn ? "是" : "否");
        product.put("tradeInItems", new ArrayList<>());
        product.put("commissionAmount", commission.setScale(2, RoundingMode.HALF_UP));
        product.put("commissionRate", rate.setScale(2, RoundingMode.HALF_UP));
        product.put("isSpecial", first.getIsSpecial());
        product.put("standardSalePrice",
                firstNonNull(rows, OpsBusinessData::getStandardSalePrice));
        product.put("specialFileId", firstNonNull(rows, OpsBusinessData::getSpecialFileId));
        product.put("specialFileUrl", firstText(rows, OpsBusinessData::getSpecialFileUrl));
        product.put("specialFileName", firstText(rows, OpsBusinessData::getSpecialFileName));
        product.put("retailFloorPrice",
                firstNonNull(rows, OpsBusinessData::getRetailFloorPrice));
        product.put("groupLowestPrice",
                firstNonNull(rows, OpsBusinessData::getGroupLowestPrice));
        // 退货分录标记：负数数量 + SCM 退货单号，前端据此红色高亮并跳转
        product.put("isReturnEntry", isReturn);
        product.put("sourceReturnId", first.getSourceReturnId());
        product.put("sourceReturnNo", first.getSourceReturnNo());
        product.put("rowIds", rows.stream().map(OpsBusinessData::getId).toList());
        return product;
    }

    /** 退货分录：数量为负（金额/提成随之为负）。 */
    private static boolean isReturnEntry(OpsBusinessData row) {
        return row != null && row.getSaleQuantity() != null && row.getSaleQuantity() < 0;
    }

    private static String firstText(List<OpsBusinessData> rows,
                                    Function<OpsBusinessData, String> getter) {
        for (OpsBusinessData row : rows) {
            String value = getter.apply(row);
            if (hasText(value)) return value.trim();
        }
        return null;
    }

    private static <T> T firstNonNull(List<OpsBusinessData> rows,
                                      Function<OpsBusinessData, T> getter) {
        for (OpsBusinessData row : rows) {
            T value = getter.apply(row);
            if (value != null) return value;
        }
        return null;
    }

    private static String computeOrderStatus(List<OpsBusinessData> rows) {
        Set<String> statuses = rows.stream().map(OpsBusinessData::getCalcStatus)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (statuses.contains("已上报集团")) return "已上报集团";
        if (statuses.contains("未计算")) return "未计算";
        if (statuses.contains("已计算")) return "已计算";
        if (statuses.contains("经办人已核对")) return "经办人已核对";
        return rows.isEmpty() ? null : rows.get(0).getCalcStatus();
    }

    /** Round per-category totals to 2 decimals for display consistency. */
    private static void normalizeCategoryStats(List<Map<String, Object>> categoryStats) {
        for (Map<String, Object> row : categoryStats) {
            if (row.get("totalSale") instanceof BigDecimal b) row.put("totalSale", b.setScale(2, RoundingMode.HALF_UP));
            if (row.get("totalCommission") instanceof BigDecimal b) row.put("totalCommission", b.setScale(2, RoundingMode.HALF_UP));
        }
    }

    @Override
    public Map<String, Object> rematchMembers(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of("total", 0, "matched", 0, "unmatched", 0,
                    "updated", 0, "skippedReported", 0);
        }
        // 数据范围：仅可见组织内的行（超管无限制），写回同样按 corp 限定
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (noVisibleCorp(visibleIds)) {
            return Map.of("total", 0, "matched", 0, "unmatched", 0,
                    "updated", 0, "skippedReported", 0);
        }
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
        qw.in(OpsBusinessData::getId, ids);
        if (visibleIds != null && !visibleIds.isEmpty()) {
            qw.in(OpsBusinessData::getCorpId, visibleIds);
        }
        List<OpsBusinessData> rows = mapper.selectList(qw);

        int matched = 0, unmatched = 0, updated = 0, skippedReported = 0;
        // 按行 corp 分组解析燃气公司范围（同一导入批次的行 corp 一致，分组查询避免逐行跨库）
        Map<Long, List<OpsBusinessData>> byCorp = rows.stream()
                .collect(Collectors.groupingBy(OpsBusinessData::getCorpId));
        for (Map.Entry<Long, List<OpsBusinessData>> e : byCorp.entrySet()) {
            List<OpsBusinessData> group = e.getValue();
            String gasCompanyId = memberMatcher.resolveGasCompanyId(e.getKey());
            List<String> phones = group.stream()
                    .map(OpsBusinessData::getCustomerPhone)
                    .filter(ph -> ph != null && GasMemberMatcher.PHONE_PATTERN.matcher(ph).matches())
                    .distinct().collect(Collectors.toList());
            Map<String, Long> byPhone = memberMatcher.memberIdByPhone(phones, gasCompanyId);
            List<String> names = group.stream()
                    .map(OpsBusinessData::getCustomer)
                    .filter(c -> c != null && !c.isBlank())
                    .map(String::trim).distinct().collect(Collectors.toList());
            Map<String, Long> byName = names.isEmpty()
                    ? Map.of() : memberMatcher.memberIdByName(names, gasCompanyId);
            for (OpsBusinessData row : group) {
                if (REPORTED_STATUS.equals(row.getCalcStatus())) { skippedReported++; continue; }
                Long memberId = GasMemberMatcher.pickMember(
                        row.getCustomerPhone(), row.getCustomer(), byPhone, byName);
                if (memberId != null) matched++; else unmatched++;
                if (!Objects.equals(memberId, row.getPlatformMemberId())) {
                    mapper.update(null, new LambdaUpdateWrapper<OpsBusinessData>()
                            .eq(OpsBusinessData::getId, row.getId())
                            .eq(OpsBusinessData::getCorpId, row.getCorpId())
                            .set(OpsBusinessData::getPlatformMemberId, memberId));
                    syncMasterMember(row.getOrderId(), memberId);
                    updated++;
                }
            }
        }
        return Map.of("total", rows.size(), "matched", matched, "unmatched", unmatched,
                "updated", updated, "skippedReported", skippedReported);
    }

    @Override
    public Map<String, Object> rematchMembersByBatch(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            return Map.of("total", 0, "matchedExisting", 0, "created", 0,
                    "updated", 0, "skippedReported", 0, "createdList", List.of());
        }
        // 数据范围：仅可见组织内的行（超管无限制）；只处理未关联行（幂等可重跑）
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (noVisibleCorp(visibleIds)) {
            return Map.of("total", 0, "matchedExisting", 0, "created", 0,
                    "updated", 0, "skippedReported", 0, "createdList", List.of());
        }
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<OpsBusinessData>()
                .eq(OpsBusinessData::getBatchNo, batchNo)
                .isNull(OpsBusinessData::getPlatformMemberId);
        if (visibleIds != null && !visibleIds.isEmpty()) {
            qw.in(OpsBusinessData::getCorpId, visibleIds);
        }
        List<OpsBusinessData> rows = mapper.selectList(qw);

        int matchedExisting = 0, createdRows = 0, updated = 0, skippedReported = 0;
        List<String> createdList = new ArrayList<>();
        // 全局手机号匹配（uk_phone 唯一，跨组织）
        List<String> phones = rows.stream()
                .map(OpsBusinessData::getCustomerPhone)
                .filter(ph -> ph != null && !ph.isBlank())
                .distinct().collect(Collectors.toList());
        Map<String, Long> byPhone = memberMatcher.memberIdByPhoneGlobal(phones);
        // 按行 corp 分组：公司+姓名 匹配与创建均按组织归属
        Map<Long, List<OpsBusinessData>> byCorp = rows.stream()
                .collect(Collectors.groupingBy(OpsBusinessData::getCorpId));
        Map<String, Long> phoneToMember = new HashMap<>(byPhone);
        Map<String, Boolean> phoneCreated = new HashMap<>();
        for (Map.Entry<Long, List<OpsBusinessData>> e : byCorp.entrySet()) {
            Long corpId = e.getKey();
            List<OpsBusinessData> group = e.getValue();
            List<String> names = group.stream()
                    .map(OpsBusinessData::getCustomer)
                    .filter(c -> c != null && !c.isBlank())
                    .map(String::trim).distinct().collect(Collectors.toList());
            Map<String, Long> byName = memberMatcher.memberIdByNameInCorp(names, corpId);
            for (OpsBusinessData row : group) {
                if (REPORTED_STATUS.equals(row.getCalcStatus())) { skippedReported++; continue; }
                String phone = row.getCustomerPhone();
                String name = row.getCustomer() != null ? row.getCustomer().trim() : "";
                Long memberId = null;
                boolean created = false;
                if (phone != null && !phone.isBlank()) {
                    memberId = phoneToMember.get(phone);
                    created = Boolean.TRUE.equals(phoneCreated.get(phone));
                    if (memberId == null) {
                        memberId = byName.get(name);
                        if (memberId == null) {
                            memberId = memberMatcher.createMember(phone, name, corpId);
                            created = true;
                            createdList.add(name + " " + phone);
                        }
                        phoneToMember.put(phone, memberId);
                        phoneCreated.put(phone, created);
                    }
                } else {
                    memberId = byName.get(name);   // 无手机行仅按公司+姓名匹配（不创建）
                }
                if (memberId == null) continue;    // 无手机且姓名未命中：保持 NULL
                if (created) createdRows++; else matchedExisting++;
                if (!Objects.equals(memberId, row.getPlatformMemberId())) {
                    mapper.update(null, new LambdaUpdateWrapper<OpsBusinessData>()
                            .eq(OpsBusinessData::getId, row.getId())
                            .eq(OpsBusinessData::getCorpId, row.getCorpId())
                            .set(OpsBusinessData::getPlatformMemberId, memberId));
                    syncMasterMember(row.getOrderId(), memberId);
                    updated++;
                }
            }
        }
        return Map.of("total", rows.size(), "matchedExisting", matchedExisting,
                "created", createdList.size(), "createdRows", createdRows,
                "updated", updated, "skippedReported", skippedReported,
                "createdList", createdList);
    }

    @Override
    public List<String> getBatches() {
        if (noVisibleCorp(dataScopeService.getVisibleCorpIds())) return List.of();
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<OpsBusinessData>()
                .select(OpsBusinessData::getBatchNo)
                .groupBy(OpsBusinessData::getBatchNo);
        applyCorpScope(qw);
        qw.orderByDesc(OpsBusinessData::getBatchNo);
        List<OpsBusinessData> all = mapper.selectList(qw);
        Set<String> set = new LinkedHashSet<>();
        for (OpsBusinessData d : all) if (d.getBatchNo() != null) set.add(d.getBatchNo());
        return new ArrayList<>(set);
    }

    @Override
    public void runAsyncCheck(SysImportTask task) {
        Long taskId = task.getId();
        try {
            withSecurityContext(task);
            importTaskService.updateStatus(taskId, "PROCESSING", "CHECK", "正在解析校验文件...");
            Map<String, Object> result = importHandler.check(
                    Path.of(task.getFilePath()), task.getCorpId());
            String json = JSON_MAPPER.writeValueAsString(result);
            int totalRows = result.get("totalRows") instanceof Number n ? n.intValue() : 0;
            importTaskService.markDone(taskId, totalRows, 0, json);
        } catch (Exception e) {
            log.error("异步导入校验失败 taskId={}", taskId, e);
            importTaskService.markFailed(taskId, "CHECK", e.getMessage(), 0, 0);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Override
    public void runAsyncConfirm(SysImportTask task, List<ModelConfirmDecision> decisions) {
        Long taskId = task.getId();
        try {
            withSecurityContext(task);
            importTaskService.updateStatus(taskId, "PROCESSING", "ROW_CHECK", "正在导入数据...");
            // 事务边界在 importHandler.confirm（单事务原子入库）；本方法不持事务，
            // 保证任务状态更新（markDone/markFailed）不受业务回滚影响、独立提交
            ImportResult r = importHandler.confirm(
                    Path.of(task.getFilePath()), task.getCorpId(), decisions, null);
            String json = JSON_MAPPER.writeValueAsString(mapImportResult(r));
            importTaskService.markDone(taskId, r.getTotalRows(),
                    "success".equals(r.getStatus()) ? r.getCount() : 0, json);
        } catch (Exception e) {
            log.error("异步导入失败 taskId={}", taskId, e);
            importTaskService.markFailed(taskId, "ROW_CHECK", e.getMessage(), 0, 0);
        } finally {
            SecurityContextHolder.clearContext();
            // 两阶段结束，暂存文件不再需要（重试=重新上传校验，防目录无限膨胀）
            if (task.getFilePath() != null) {
                try { Files.deleteIfExists(Path.of(task.getFilePath())); } catch (Exception ignored) {}
            }
        }
    }

    /** 异步工作线程无请求级 SecurityContext —— 从任务记录重建，
     *  保证租户过滤（TenantLineHandler）与操作归属与请求线程一致 */
    private void withSecurityContext(SysImportTask task) {
        LoginUser lu = new LoginUser();
        lu.setUserId(task.getUserId());
        lu.setCorpId(task.getCorpId() != null ? task.getCorpId() : 0L);
        lu.setUsername(task.getUserName());
        lu.setAdmin(false);
        lu.setPermissions(List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(lu, null, lu.getAuthorities()));
    }

    /** ImportResult → 前端响应格式（与历史 /import/submit 一致；success 附会员匹配/新建统计与清单） */
    private Map<String, Object> mapImportResult(ImportResult result) {
        if ("success".equals(result.getStatus())) {
            return Map.of("status", "success", "count", result.getCount(),
                    "orderCount", result.getOrderCount(),
                    "matchedCount", result.getMatchedCount(),
                    "unmatchedCount", result.getUnmatchedCount(),
                    "createdCount", result.getCreatedCount(),
                    "createdList", result.getCreatedList(),
                    "defaultQtyCount", result.getDefaultQtyCount());
        }
        return Map.of("status", "fail", "totalErrors", result.getFailRows(),
                "errors", result.getErrors());
    }

    /** Load valid category names + name↔key maps from platform dict. Falls back to defaults on failure. */
    private Set<String> loadValidCategories() {
        if (categoryNameCache != null) return categoryNameCache;
        synchronized (this) {
            if (categoryNameCache != null) return categoryNameCache;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = platformInternalClient.get(
                        "/api/dict/data?typeCode=PRODUCT_CATEGORY", null);
                Object data = resp.get("data");
                if (data instanceof List) {
                    Set<String> set = new HashSet<>();
                    Map<String, String> cn2key = new HashMap<>();
                    Map<String, String> key2cn = new HashMap<>();
                    for (Object item : (List<?>) data) {
                        if (item instanceof Map) {
                            Object val = ((Map<?, ?>) item).get("itemValue");
                            Object key = ((Map<?, ?>) item).get("itemKey");
                            if (val != null) {
                                String cn = val.toString();
                                set.add(cn);
                                if (key != null) {
                                    String k = key.toString();
                                    cn2key.put(cn, k);
                                    key2cn.put(k, cn);
                                }
                            }
                        }
                    }
                    if (!set.isEmpty()) {
                        categoryNameCache = set;
                        CN_TO_KEY = Collections.unmodifiableMap(cn2key);
                        KEY_TO_CN = Collections.unmodifiableMap(key2cn);
                        log.info("Loaded {} categories from platform dict", set.size());
                        return set;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load PRODUCT_CATEGORY dict, using defaults: {}", e.getMessage());
            }
            categoryNameCache = DEFAULT_CATEGORIES;
            return categoryNameCache;
        }
    }

    @Override
    public List<String> getUncalcBatches() {
        if (noVisibleCorp(dataScopeService.getVisibleCorpIds())) return List.of();
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<OpsBusinessData>()
                .select(OpsBusinessData::getBatchNo)
                .eq(OpsBusinessData::getCalcStatus, "未计算")
                .groupBy(OpsBusinessData::getBatchNo)
                .orderByDesc(OpsBusinessData::getBatchNo);
        applyCorpScope(qw);
        List<OpsBusinessData> all = mapper.selectList(qw);
        return all.stream().map(OpsBusinessData::getBatchNo).filter(Objects::nonNull).distinct().toList();
    }

    @Override
    @Transactional
    public Map<String, Object> calcCommission(String batchNo) {
        Set<Long> visible = dataScopeService.getVisibleCorpIds();
        if (noVisibleCorp(visible)) {
            return Map.of("total", 0, "commissioned", 0, "zero", 0, "splitGroups", 0);
        }
        // 锁定守卫：已上报集团的批次禁止重新计算（否则 CASE-WHEN UPDATE 会覆盖锁定状态）
        if (batchNo != null && !batchNo.isBlank()) {
            long reported = mapper.selectCount(new LambdaQueryWrapper<OpsBusinessData>()
                    .eq(OpsBusinessData::getBatchNo, batchNo)
                    .eq(OpsBusinessData::getCalcStatus, REPORTED_STATUS));
            if (reported > 0) {
                throw BusinessException.of("该批次包含" + reported + "条已上报集团数据，禁止重新计算");
            }
        }
        LambdaQueryWrapper<OpsBusinessData> uncalcQw = new LambdaQueryWrapper<OpsBusinessData>()
                .eq(OpsBusinessData::getCalcStatus, "未计算");
        if (batchNo != null && !batchNo.isBlank()) {
            uncalcQw.eq(OpsBusinessData::getBatchNo, batchNo);
        }
        applyCorpScope(uncalcQw);
        List<OpsBusinessData> uncalcs = mapper.selectList(uncalcQw);
        return calculateCommission(uncalcs, visible);
    }

    @Override
    @Transactional
    public Map<String, Object> recalculateOrder(Long orderId) {
        if (orderId == null) {
            return Map.of("total", 0, "commissioned", 0, "zero", 0, "splitGroups", 0);
        }
        OpsBusinessOrder order = orderService.getById(orderId);
        if (order == null) throw BusinessException.notFound("订单不存在");
        if (REPORTED_STATUS.equals(order.getCalcStatus())) {
            throw BusinessException.of("该订单已上报集团，禁止重新计算");
        }
        List<OpsBusinessData> uncalcs = orderMapper.selectItems(orderId).stream()
                .filter(r -> "未计算".equals(r.getCalcStatus())).toList();
        if (uncalcs.isEmpty()) {
            orderService.syncStatus(orderId);
            return Map.of("total", 0, "commissioned", 0, "zero", 0, "splitGroups", 0);
        }
        return calculateCommission(uncalcs, dataScopeService.getVisibleCorpIds());
    }

    /** 提成计算引擎：对传入的“未计算”行匹配规则、均分、批量回写，并同步主表状态/合计。 */
    private Map<String, Object> calculateCommission(List<OpsBusinessData> uncalcs, Set<Long> visibleIds) {

        // ── Build parent chain BEFORE querying rules ──
        // We need the full ancestor set so the rule query can fetch parent/ancestor
        // rules alongside the currently-visible corps' rules. Without this,
        // cascadeParentRules() would find parentMap entries but no parent rule data
        // in ruleMapByCorp, silently yielding zero commission for city partners.
        Map<Long, Long> parentMap = new HashMap<>();
        Set<Long> ruleCorpIds = new HashSet<>();
        if (visibleIds != null && !visibleIds.isEmpty()) {
            parentMap = buildParentChainMap(visibleIds);
            ruleCorpIds.addAll(visibleIds);
            // Walk the parent chain to collect ALL ancestor corp IDs
            for (Long child : visibleIds) {
                Long p = parentMap.get(child);
                while (p != null && p != 0L) {
                    ruleCorpIds.add(p);
                    // resolve next ancestor if known; stop at root
                    Long next = parentMap.get(p);
                    if (next == null && p != 0L) {
                        // parent-of-parent not in map yet — resolve it now
                        Long gp = resolveParentId(p);
                        if (gp != null) parentMap.put(p, gp);
                        next = gp;
                    }
                    p = next;
                }
            }
        }
        // Always include platform-level (corpId=0) as fallback
        ruleCorpIds.add(0L);

        // Load active rules filtered by the expanded corp ID set (visible + ancestors + platform)
        LambdaQueryWrapper<OpsPersonalCommissionRule> ruleQw =
                new LambdaQueryWrapper<OpsPersonalCommissionRule>()
                        .eq(OpsPersonalCommissionRule::getStatus, 1);
        if (visibleIds != null) {
            ruleQw.in(OpsPersonalCommissionRule::getCorpId, ruleCorpIds);
        }
        // (super-admin: visibleIds=null → no corp filter → all rules)
        List<OpsPersonalCommissionRule> rules = ruleMapper.selectList(ruleQw);
        if (rules.isEmpty()) {
            log.warn("No active personal commission rules found for corp scope. "
                    + "All commission will be zero.");
        }
        // Group rules by (corpId, category) for per-tenant matching
        Map<Long, Map<String, OpsPersonalCommissionRule>> ruleMapByCorp = new HashMap<>();
        for (OpsPersonalCommissionRule r : rules) {
            Long cid = r.getCorpId() != null ? r.getCorpId() : 0L;
            ruleMapByCorp.computeIfAbsent(cid, k -> new HashMap<>())
                    .putIfAbsent(r.getCategory(), r);
        }

        // ── Cascade parent rules to child corps (org hierarchy inheritance) ──
        // Child corps inherit parent operator's rules; child's own rules take priority.
        if (visibleIds != null && !visibleIds.isEmpty()) {
            cascadeParentRules(ruleMapByCorp, parentMap);
        }

        // For super admin (visibleIds=null): merge all corp rules into a
        // platform-level fallback (corpId=0) so records from any org can
        // match when their own corp has no rules configured.
        if (visibleIds == null) {
            Map<String, OpsPersonalCommissionRule> allRules = new HashMap<>();
            for (Map<String, OpsPersonalCommissionRule> corpRules : ruleMapByCorp.values()) {
                for (Map.Entry<String, OpsPersonalCommissionRule> e : corpRules.entrySet()) {
                    allRules.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            ruleMapByCorp.put(0L, allRules);
            log.info("Super admin: merged {} rules from all corps as fallback", allRules.size());
        }

        if (uncalcs.isEmpty()) {
            return Map.of("total", 0, "commissioned", 0, "zero", 0, "splitGroups", 0);
        }

        // ── Phase 1: Compute per-row commission (in-memory, per-corp rule matching) ──
        Map<Long, BigDecimal[]> results = new LinkedHashMap<>();
        for (OpsBusinessData d : uncalcs) {
            if (isSpecial(d)) {
                // 特批行：提成 = 销售价 × 提成比例 / 100，跳过个人提成规则匹配
                BigDecimal rate = d.getCommissionRate() != null
                        ? d.getCommissionRate() : BigDecimal.ZERO;
                BigDecimal amt = d.getSalePrice() == null ? BigDecimal.ZERO
                        : d.getSalePrice().multiply(rate)
                                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
                results.put(d.getId(), new BigDecimal[]{rate, amt});
                continue;
            }
            Long dCorpId = d.getCorpId() != null ? d.getCorpId() : 0L;
            Map<String, OpsPersonalCommissionRule> corpRuleMap = ruleMapByCorp.getOrDefault(dCorpId,
                    ruleMapByCorp.getOrDefault(0L, Collections.emptyMap()));
            OpsPersonalCommissionRule rule = corpRuleMap.get(d.getCategory());
            BigDecimal[] cr = calcCommissionAmount(d.getSalePrice(),
                    d.getRetailFloorPrice(), d.getGroupLowestPrice(), rule);
            results.put(d.getId(), new BigDecimal[]{cr[0], cr[1]});
        }

        // ── Phase 2: Split positive rows by receipt×customer×category×model group ──
        int splitGroups = splitCommissionByReceiptGroup(uncalcs, results);
        // ── Phase 2.5: 退货分录按原销售分录提成金额比例冲减（负值）──
        applyReturnCommission(uncalcs, results);

        // Count commissioned / zero AFTER splitting (results may have changed)
        int commissioned = 0, zero = 0;
        for (BigDecimal[] cr : results.values()) {
            if (cr[1].signum() != 0) commissioned++; else zero++;
        }

        // ── Phase 3: Batch SQL update (preserves existing CASE WHEN pattern) ──
        java.sql.Connection conn = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try (var stmt = conn.createStatement()) {
            int batchSize = 500;
            for (int i = 0; i < uncalcs.size(); i += batchSize) {
                int end = Math.min(i + batchSize, uncalcs.size());
                StringBuilder rateSql = new StringBuilder(
                        "UPDATE ops_business_order_item SET commission_rate = CASE ");
                StringBuilder amtSql  = new StringBuilder(
                        "UPDATE ops_business_order_item SET commission_amount = CASE ");
                StringBuilder ids = new StringBuilder();
                for (int j = i; j < end; j++) {
                    Long id = uncalcs.get(j).getId();
                    BigDecimal[] cr = results.get(id);
                    rateSql.append("WHEN id=").append(id)
                            .append(" THEN ").append(cr[0]).append(" ");
                    amtSql.append("WHEN id=").append(id)
                            .append(" THEN ").append(cr[1]).append(" ");
                    ids.append(id).append(",");
                }
                ids.setLength(ids.length() - 1);
                rateSql.append("END, calc_status='已计算' WHERE id IN (")
                        .append(ids).append(")");
                amtSql.append("END WHERE id IN (").append(ids).append(")");
                stmt.addBatch(rateSql.toString());
                stmt.addBatch(amtSql.toString());
                stmt.executeBatch();
            }
        } catch (Exception e) {
            log.error("批量计算提成失败", e);
            throw new RuntimeException("批量计算提成失败", e);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, dataSource);
        }

        // ── Phase 4: Verify receipt group consistency ──
        verifyReceiptGroupConsistency(uncalcs, results);

        // 子表提成/状态已更新 → 同步订单头合计与状态
        syncOrderTotals(uncalcs);

        log.info("提成计算完成: {}条有提成, {}条0提成, {}个分组均分, 共{}条",
                commissioned, zero, splitGroups, uncalcs.size());
        return Map.of("total", uncalcs.size(), "commissioned", commissioned,
                "zero", zero, "splitGroups", splitGroups);
    }

    /**
     * 退货分录提成：按原销售分录**剩余提成**等比例冲减。
     *
     * <p>口径：冲减额 = -（原提成 + 已冲减负数）× 本次退货数量 ÷ 剩余数量。
     * 用「当前提成」而非规则重算，历史退货已缩放的提成与新退货可连续正确冲减，
     * 不会重复扣减；剩余提成为 0（未配置规则/已全额冲减）时负提成为 0。</p>
     */
    private void applyReturnCommission(List<OpsBusinessData> uncalcs,
                                       Map<Long, BigDecimal[]> results) {
        Map<String, List<OpsBusinessData>> groups = collectReturnGroups(uncalcs);
        if (groups.isEmpty()) return;
        Map<Long, List<OpsBusinessData>> orderCache = new HashMap<>();
        // 按分录 id 升序处理：先算早期退货的冲减额，后续退货才能用「剩余提成」正确计算
        List<List<OpsBusinessData>> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingLong(BusinessDataServiceImpl::minId));
        for (List<OpsBusinessData> group : ordered) {
            applyReturnGroup(group, orderCache, results);
        }
    }

    private static Map<String, List<OpsBusinessData>> collectReturnGroups(
            List<OpsBusinessData> uncalcs) {
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData d : uncalcs) {
            if (!isReturnEntry(d)) continue;
            groups.computeIfAbsent(returnGroupKey(d), k -> new ArrayList<>()).add(d);
        }
        return groups;
    }

    /** 单个退货单×产品组的负提成计算：按剩余数量/剩余提成比例冲减并分摊到经办人。 */
    private void applyReturnGroup(List<OpsBusinessData> group,
                                  Map<Long, List<OpsBusinessData>> orderCache,
                                  Map<Long, BigDecimal[]> results) {
        OpsBusinessData first = group.get(0);
        long groupMinId = minId(group);
        List<OpsBusinessData> all = orderCache.computeIfAbsent(first.getOrderId(),
                id -> id == null ? List.of() : orderMapper.selectItems(id));
        List<OpsBusinessData> positives = all.stream()
                .filter(r -> !isReturnEntry(r) && sameProduct(r, first)).toList();
        int q = positives.stream().mapToInt(BusinessDataServiceImpl::positiveQty)
                .max().orElse(0);
        int r = Math.abs(positiveQty(first));
        // 仅统计本次退货之前的退货；同一退货单多经办人行数量只计一次（MAX）
        int returnedBefore = returnedQtyBefore(all, first, groupMinId);
        // 历史异常数据可能已超退；分母至少取本次退货量，避免冲减额超过原提成
        int remaining = Math.max(q - returnedBefore, r);
        if (r <= 0 || q <= 0) {
            zeroReturnCommission(group, results);
            return;
        }
        Map<String, BigDecimal> posByAgent = new HashMap<>();
        Map<String, BigDecimal> rateByAgent = new HashMap<>();
        BigDecimal posTotal = BigDecimal.ZERO;
        for (OpsBusinessData p : positives) {
            BigDecimal amt = commissionOf(p, results);
            posByAgent.merge(agentKeyOrEmpty(p), amt, BigDecimal::add);
            BigDecimal[] pcr = results.get(p.getId());
            BigDecimal pRate = pcr != null && pcr[0] != null ? pcr[0] : p.getCommissionRate();
            if (pRate != null) {
                rateByAgent.merge(agentKeyOrEmpty(p), pRate, BigDecimal::add);
            }
            posTotal = posTotal.add(amt);
        }
        BigDecimal remainingCommission = posTotal.add(
                priorReturnCommission(all, first, groupMinId, results));
        BigDecimal reversalTotal = returnReversalTotal(remainingCommission, r, remaining);
        distributeReturn(group, posByAgent, rateByAgent, posTotal, reversalTotal, results);
    }

    /** 本次退货之前已冲减的负提成（多经办人逐行相加，金额本身按人拆分）。 */
    private static BigDecimal priorReturnCommission(List<OpsBusinessData> all,
                                                    OpsBusinessData ref, long beforeId,
                                                    Map<Long, BigDecimal[]> results) {
        return all.stream()
                .filter(x -> isReturnEntry(x) && sameProduct(x, ref)
                        && x.getId() != null && x.getId() < beforeId)
                .map(x -> commissionOf(x, results))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 冲减总额 = -「剩余提成（原提成 + 已冲减负数）」× 本次退货数量 / 剩余数量。
     * 原提成为 0（未配置规则/历史全额退货）时冲减 0，避免产生无依据的负提成。
     */
    private static BigDecimal returnReversalTotal(BigDecimal remainingCommission,
                                                   int r, int remaining) {
        if (remainingCommission.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return remainingCommission.negate().multiply(BigDecimal.valueOf(r))
                .divide(BigDecimal.valueOf(remaining), 2, RoundingMode.HALF_UP);
    }

    /** 按经办人原提成占比分摊负提成；无原提成时组内均分，末行吸收舍入差。 */
    private static void distributeReturn(List<OpsBusinessData> group,
                                         Map<String, BigDecimal> posByAgent,
                                         Map<String, BigDecimal> rateByAgent,
                                         BigDecimal posTotal, BigDecimal reversalTotal,
                                         Map<Long, BigDecimal[]> results) {
        int n = group.size();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            OpsBusinessData d = group.get(i);
            String agent = agentKeyOrEmpty(d);
            BigDecimal amt;
            if (posTotal.signum() != 0) {
                BigDecimal share = posByAgent.getOrDefault(agent, BigDecimal.ZERO);
                amt = i == n - 1
                        ? reversalTotal.subtract(allocated)
                        : reversalTotal.multiply(share).divide(posTotal, 2, RoundingMode.HALF_UP);
            } else {
                amt = i == n - 1
                        ? reversalTotal.subtract(allocated)
                        : reversalTotal.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            }
            allocated = allocated.add(amt);
            BigDecimal[] cr = results.get(d.getId());
            BigDecimal rate = rateByAgent.get(agent);
            if (rate == null) {
                rate = cr != null && cr[0] != null
                        ? cr[0].divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            }
            results.put(d.getId(), new BigDecimal[]{rate, amt});
        }
    }

    private static void zeroReturnCommission(List<OpsBusinessData> group,
                                             Map<Long, BigDecimal[]> results) {
        for (OpsBusinessData d : group) {
            BigDecimal[] cr = results.get(d.getId());
            BigDecimal rate = cr != null && cr[0] != null ? cr[0] : BigDecimal.ZERO;
            results.put(d.getId(), new BigDecimal[]{rate, BigDecimal.ZERO});
        }
    }

    private static BigDecimal commissionOf(OpsBusinessData row, Map<Long, BigDecimal[]> results) {
        BigDecimal[] cr = results.get(row.getId());
        if (cr != null && cr[1] != null) return cr[1];
        return row.getCommissionAmount() != null ? row.getCommissionAmount() : BigDecimal.ZERO;
    }

    private static int positiveQty(OpsBusinessData d) {
        return d.getSaleQuantity() != null ? d.getSaleQuantity() : 1;
    }

    /** 本次退货之前的累计退货数量：同一退货单多经办人行只计一次（MAX）。 */
    private static int returnedQtyBefore(List<OpsBusinessData> all, OpsBusinessData ref,
                                         long beforeId) {
        Map<String, Integer> perBill = new HashMap<>();
        for (OpsBusinessData x : all) {
            if (!isReturnEntry(x) || !sameProduct(x, ref)) continue;
            if (x.getId() == null || x.getId() >= beforeId) continue;
            String bill = Objects.toString(x.getSourceReturnId(), "0");
            perBill.merge(bill, Math.abs(positiveQty(x)), Math::max);
        }
        return perBill.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static long minId(List<OpsBusinessData> rows) {
        return rows.stream().map(OpsBusinessData::getId)
                .filter(Objects::nonNull).mapToLong(Long::longValue).min().orElse(0L);
    }

    private static String agentKeyOrEmpty(OpsBusinessData d) {
        String key = agentKey(d);
        return key != null ? key : "";
    }

    private static String returnGroupKey(OpsBusinessData d) {
        return "T|" + Objects.toString(d.getSourceReturnId(), "0") + "|"
                + Objects.toString(d.getOrderId(), "0") + "|"
                + Objects.toString(d.getCategory(), "") + "|"
                + Objects.toString(d.getBrand(), "") + "|"
                + Objects.toString(d.getModel(), "") + "|"
                + priceKey(d.getSalePrice());
    }

    private static boolean sameProduct(OpsBusinessData a, OpsBusinessData b) {
        return Objects.equals(a.getCategory(), b.getCategory())
                && Objects.equals(a.getBrand(), b.getBrand())
                && Objects.equals(a.getModel(), b.getModel())
                && sameAmount(a.getSalePrice(), b.getSalePrice());
    }

    /** 同步受影响订单头的 total_commission / calc_status。 */
    private void syncOrderTotals(List<OpsBusinessData> rows) {
        Set<Long> orderIds = rows.stream().map(OpsBusinessData::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        for (Long orderId : orderIds) {
            orderService.refreshTotals(orderId);
            orderService.syncStatus(orderId);
        }
    }

    /**
     * Split commission evenly within receipt×customer×category×model groups (N > 1).
     * Groups are formed by (receiptNo, customer, category, model) so that:
     * <ul>
     *   <li>Same receipt + same customer + same category + same model
     *       + different agents → split evenly (multi-agent collaboration)</li>
     *   <li>Different category or different model → independent calculation,
     *       each row keeps its own commission</li>
     * </ul>
     * Rule inheritance across org hierarchy is handled by
     * {@link #cascadeParentRules} at rule-load time, not by grouping.
     * The last row in each group absorbs rounding remainder so the
     * sum of adjusted amounts matches the original total exactly.
     *
     * @return number of receipt×customer×category×model groups that were split
     */
    private int splitCommissionByReceiptGroup(List<OpsBusinessData> records,
                                               Map<Long, BigDecimal[]> results) {
        // Build receipt/group × customer × category × brand × model groups
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData d : records) {
            String key = commissionGroupKey(d);
            if (key != null) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            }
        }

        int splitGroups = 0;
        for (Map.Entry<String, List<OpsBusinessData>> entry : groups.entrySet()) {
            List<OpsBusinessData> group = entry.getValue();
            List<OpsBusinessData> splittable = splitCandidates(group);
            if (splittable.size() <= 1) continue;
            splitGroups++;

            // Within a group (business group no / receipt + customer + category +
            // brand + model), all rows represent the same product sold to the same
            // customer, so every row should match the same commission rule and
            // yield the same amount. We pick the first row's result for splitting.
            BigDecimal[] firstResult = results.get(splittable.get(0).getId());
            BigDecimal totalAmount = (firstResult != null && firstResult[1] != null)
                    ? firstResult[1] : BigDecimal.ZERO;
            BigDecimal originalRate = (firstResult != null && firstResult[0] != null)
                    ? firstResult[0] : BigDecimal.ZERO;

            int n = splittable.size();
            // Rate per row = rule rate ÷ N (spec §2.4)
            BigDecimal perRowRate = originalRate.divide(
                    BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            // Amount per row = totalAmount ÷ N, last row absorbs rounding
            BigDecimal baseAmount = totalAmount.divide(
                    BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            BigDecimal used = baseAmount.multiply(BigDecimal.valueOf(n - 1));
            BigDecimal remainder = totalAmount.subtract(used);

            log.info("Receipt group {}: N={}, totalAmount={}, baseAmount={}, "
                    + "remainder={}, originalRate={}, perRowRate={}",
                    entry.getKey(), n, totalAmount, baseAmount, remainder,
                    originalRate, perRowRate);

            for (int i = 0; i < n; i++) {
                OpsBusinessData d = splittable.get(i);
                BigDecimal finalAmount = (i < n - 1) ? baseAmount : remainder;
                results.put(d.getId(), new BigDecimal[]{perRowRate, finalAmount});
            }
        }
        return splitGroups;
    }

    /**
     * 提成/校验分组键：优先按订单编号（同一次提交共享），其次历史业务组号，
     * 再次收据号；品类/品牌/型号作为产品维度。品牌纳入键，避免不同品牌同型号文本被误合并。
     */
    private static String commissionGroupKey(OpsBusinessData d) {
        // 退货分录不参与正向均分：按退货单+产品由 applyReturnCommission 单独冲减
        if (isReturnEntry(d)) return null;
        String product = "|" + Objects.toString(d.getCategory(), "")
                + "|" + Objects.toString(d.getBrand(), "")
                + "|" + Objects.toString(d.getModel(), "");
        String customer = Objects.toString(d.getCustomer(), "");
        // 新表行优先按订单主键分组，避免跨月同日同号被合并
        if (d.getOrderId() != null) {
            return "D|" + d.getOrderId() + product;
        }
        String orderNo = d.getOrderNo();
        if (orderNo != null && !orderNo.isEmpty()) {
            return "O|" + orderNo + "|" + customer + product;
        }
        String groupNo = d.getBusinessGroupNo();
        if (groupNo != null && !groupNo.isEmpty()) {
            return "G|" + groupNo + "|" + customer + product;
        }
        String receiptNo = d.getReceiptNo();
        if (receiptNo != null && !receiptNo.isEmpty()) {
            return "R|" + receiptNo + "|" + customer + product;
        }
        return null;
    }

    /**
     * 组内参与均分的行：
     * <ul>
     *   <li>仅当组内存在多个不同经办人时才均分——同一经办人重复录入同一型号
     *       （同收据号/同组号）属于两条独立明细，不能被误当作多经办人协作均分；</li>
     *   <li>非特批行按现有口径全部参与；整组均为特批行时全部参与；</li>
     *   <li>单价不一致（或特批组提成比例不一致）说明不是同一产品行/数据异常，
     *       拒绝均分并保留各自提成，避免丢弃他人提成。</li>
     * </ul>
     */
    private List<OpsBusinessData> splitCandidates(List<OpsBusinessData> group) {
        long agentCount = group.stream()
                .map(BusinessDataServiceImpl::agentKey)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        if (agentCount <= 1) return List.of();
        boolean allSpecial = group.stream().allMatch(this::isSpecial);
        List<OpsBusinessData> candidates = allSpecial ? group
                : group.stream().filter(d -> !isSpecial(d)).toList();
        if (candidates.size() <= 1) return List.of();
        BigDecimal basePrice = candidates.get(0).getSalePrice();
        if (candidates.stream().anyMatch(d -> !sameAmount(d.getSalePrice(), basePrice))) {
            return List.of();
        }
        if (allSpecial) {
            BigDecimal baseRate = candidates.get(0).getCommissionRate();
            if (candidates.stream().anyMatch(
                    d -> !sameAmount(d.getCommissionRate(), baseRate))) {
                return List.of();
            }
        }
        return candidates;
    }

    /** 经办人标识：手机号优先，空串视为缺失并回退姓名。 */
    private static String agentKey(OpsBusinessData d) {
        String phone = d.getAgentPhone();
        if (phone != null && !phone.isEmpty()) return phone;
        String name = d.getAgent();
        return name != null && !name.isEmpty() ? name : null;
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == b;
        return a.compareTo(b) == 0;
    }

    /**
     * Verify that no receipt group row has a negative or null amount
     * after splitting. Throws on data inconsistency to trigger rollback.
     */
    private void verifyReceiptGroupConsistency(List<OpsBusinessData> records,
                                                Map<Long, BigDecimal[]> results) {
        Map<String, List<OpsBusinessData>> groups = new LinkedHashMap<>();
        for (OpsBusinessData d : records) {
            String key = commissionGroupKey(d);
            if (key != null) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            }
        }

        for (Map.Entry<String, List<OpsBusinessData>> entry : groups.entrySet()) {
            List<OpsBusinessData> group = entry.getValue();
            if (group.size() <= 1) continue;

            BigDecimal groupSum = BigDecimal.ZERO;
            for (OpsBusinessData d : group) {
                BigDecimal[] cr = results.get(d.getId());
                // 负数仅允许出现在退货分录；正向组内出现负提成视为数据异常
                boolean invalid = cr == null || cr[1] == null
                        || (!isReturnEntry(d) && cr[1].compareTo(BigDecimal.ZERO) < 0);
                if (invalid) {
                    String msg = "Receipt group " + entry.getKey()
                            + " row id=" + d.getId()
                            + " has invalid amount after split: "
                            + (cr != null ? cr[1] : null);
                    log.error(msg);
                    throw new RuntimeException("提成均分校验失败：" + msg);
                }
                groupSum = groupSum.add(cr[1]);
            }
            log.debug("Receipt group {}: split total = {}", entry.getKey(), groupSum);
        }
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 是否特批行：特批行提成直接按提成比例计算，不参与规则匹配与均分。 */
    private boolean isSpecial(OpsBusinessData d) {
        return "是".equals(d.getIsSpecial());
    }

    private BigDecimal[] calcCommissionAmount(BigDecimal salePrice, BigDecimal floorPrice,
                                               BigDecimal groupPrice, OpsPersonalCommissionRule rule) {
        BigDecimal zero = BigDecimal.ZERO;
        if (salePrice == null || rule == null) return new BigDecimal[]{zero, zero};

        BigDecimal rate = rule.getCommissionRate() != null ? rule.getCommissionRate() : zero;
        if ("阶梯".equals(rule.getCommissionType())) {
            if (floorPrice == null || groupPrice == null) {
                BigDecimal amt = salePrice.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                return new BigDecimal[]{rate, amt};
            }
            if (salePrice.compareTo(floorPrice) >= 0) {
                BigDecimal amt = salePrice.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                return new BigDecimal[]{rate, amt};
            }
            if (salePrice.compareTo(groupPrice) >= 0) {
                BigDecimal midRate = new BigDecimal("5.00");
                BigDecimal amt = salePrice.multiply(midRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                return new BigDecimal[]{midRate, amt};
            }
            return new BigDecimal[]{zero, zero};
        }
        // 固定金额：提成直接取规则的 commission_amount，不按销售额计算（rate 记 0）
        if ("固定金额".equals(rule.getCommissionType())) {
            BigDecimal fixed = rule.getCommissionAmount() != null ? rule.getCommissionAmount() : zero;
            return new BigDecimal[]{zero, fixed};
        }
        BigDecimal amt = salePrice.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        return new BigDecimal[]{rate, amt};
    }

    @Override
    public void exportExcel(String company, String batchNo, String agent, String category,
                            String brand, String model, String receiptDateStart, String receiptDateEnd,
                            String bizStatus, Path outFile) {
        try {
            if (noVisibleCorp(dataScopeService.getVisibleCorpIds())) {
                log.warn("导出被拒绝：当前用户无可见组织 scope");
                return;
            }
            LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<>();
            if (company != null && !company.isEmpty()) qw.eq(OpsBusinessData::getCompany, company);
            if (batchNo != null && !batchNo.isEmpty()) qw.eq(OpsBusinessData::getBatchNo, batchNo);
            if (agent != null && !agent.isEmpty()) qw.eq(OpsBusinessData::getAgent, agent);
            if (category != null && !category.isEmpty()) qw.eq(OpsBusinessData::getCategory, category);
            if (brand != null && !brand.isEmpty()) qw.eq(OpsBusinessData::getBrand, brand);
            if (model != null && !model.isEmpty()) qw.eq(OpsBusinessData::getModel, model);
            if (receiptDateStart != null && !receiptDateStart.isEmpty()) qw.ge(OpsBusinessData::getReceiptDate, receiptDateStart);
            if (receiptDateEnd != null && !receiptDateEnd.isEmpty()) qw.le(OpsBusinessData::getReceiptDate, receiptDateEnd);
            applyCorpScope(qw);
            List<OpsBusinessData> all = mapper.selectList(qw);
            // 业务状态筛选（订单级）：先载主表状态，再过滤行
            Map<Long, String> bizStatusMap = new HashMap<>();
            Set<Long> orderIds = all.stream().map(OpsBusinessData::getOrderId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (!orderIds.isEmpty()) {
                for (OpsBusinessOrder order : orderMapper.selectBatchIds(orderIds)) {
                    bizStatusMap.put(order.getId(), order.getBizStatus());
                }
            }
            if (bizStatus != null && !bizStatus.isBlank()) {
                all.removeIf(row -> !bizStatus.trim().equals(
                        bizStatusMap.getOrDefault(row.getOrderId(), "0")));
            }
            sortByReceiptNo(all);
            // 型号关键字行（如武山）兜底填充 零售底价/集团最低价（仅导出文件）
            fillExportPrices(all, productMapper.selectList(null));
            Path tsvFile = Files.createTempFile("export_", ".tsv");
            StringBuilder sb = new StringBuilder(EXPORT_HEADER);
            for (OpsBusinessData d : all) appendExportRow(sb, d, bizStatusMap);
            Files.writeString(tsvFile, sb.toString());
            Files.createDirectories(outFile.toAbsolutePath().getParent());
            // Convert TSV → XLSX using Python
            new ProcessBuilder("python3", "-c",
                "import openpyxl,sys\n"
              + "wb=openpyxl.Workbook()\n"
              + "ws=wb.active\n"
              + "lines=open('" + tsvFile.toAbsolutePath() + "').read().strip().split('\\n')\n"
              + "for line in lines:\n  ws.append(line.split('\\t'))\n"
              + "wb.save('" + outFile.toAbsolutePath() + "')\n"
            ).redirectErrorStream(true).start().waitFor();
            Files.deleteIfExists(tsvFile);
        } catch (Exception e) { log.error("导出失败", e); }
    }

    private static final String EXPORT_HEADER =
            "批次\t公司\t客户\t客户手机\t小区\t详细地址\t部门\t品牌\t品类\t型号\t销售单价\t销售数量\t收款金额\t零售底价\t集团最低价\t提成比例\t提成金额\t业务状态\t计算状态\t经办人\t经办人手机号\t销售日期\t市场收款日期\t财务收款日期\t资金动态表横排数\t订单编号/收据号\t备注\t支付方式\t是否特批\t特批文件\t退货标识\n";

    /** 单行 TSV 输出（与 EXPORT_HEADER 列序一致）；收款金额=库中 receipt_amount（空则 单价×数量 兜底） */
    private void appendExportRow(StringBuilder sb, OpsBusinessData d, Map<Long, String> bizStatusMap) {
        String bizStatus = d.getOrderId() == null ? "0"
                : bizStatusMap.getOrDefault(d.getOrderId(), "0");
        String no = fmt(d.getOrderNo());
        String receipt = fmt(d.getReceiptNo());
        String orderReceipt = no.isEmpty() ? receipt
                : (receipt.isEmpty() ? no : no + "（" + receipt + "）");
        sb.append(fmt(d.getBatchNo())).append('\t')
          .append(fmt(d.getCompany())).append('\t').append(fmt(d.getCustomer())).append('\t')
          .append(fmt(d.getCustomerPhone())).append('\t')
          .append(fmt(d.getCommunityName())).append('\t')
          .append(fmt(d.getDetailedAddress())).append('\t')
          .append(fmt(d.getDepartment())).append('\t')
          .append(fmt(d.getBrand())).append('\t').append(getCategoryDisplayName(d.getCategory())).append('\t')
          .append(fmt(d.getModel())).append('\t')
          .append(fmt(d.getSalePrice())).append('\t')
          .append(fmt(d.getSaleQuantity())).append('\t')
          .append(fmt(d.getReceiptAmount() != null ? d.getReceiptAmount()
                  : d.getSalePrice() != null
                      ? d.getSalePrice().multiply(java.math.BigDecimal.valueOf(
                          d.getSaleQuantity() != null ? d.getSaleQuantity() : 1))
                      : null)).append('\t')
          .append(fmt(d.getRetailFloorPrice())).append('\t').append(fmt(d.getGroupLowestPrice())).append('\t')
          .append(fmt(d.getCommissionRate())).append('\t').append(fmt(d.getCommissionAmount())).append('\t')
          .append(fmt(bizStatusService.label(bizStatus))).append('\t')
          .append(fmt(d.getCalcStatus())).append('\t').append(fmt(d.getAgent())).append('\t')
          .append(fmt(d.getAgentPhone())).append('\t')
          .append(fmt(d.getSaleDate())).append('\t').append(fmt(d.getReceiptDate())).append('\t')
          .append(fmt(d.getFinanceReceiptDate())).append('\t').append(fmt(d.getFundDynamicRows())).append('\t')
          .append(fmt(orderReceipt)).append('\t')
          .append(fmt(d.getRemark())).append('\t')
          .append(fmt(com.aip.ops.util.PaymentMethod.labelOf(d.getPaymentMethod()))).append('\t')
          .append(fmt(d.getIsSpecial())).append('\t')
          .append(fmt(d.getSpecialFileName())).append('\t')
          .append(isReturnEntry(d) ? "是" : "否").append('\n');
    }

    /** TSV 单值清洗：自由文本（地址/备注等）中的制表符/换行会把导出 XLSX 的行列撕开。 */
    private String fmt(Object o) {
        if (o == null) return "";
        return String.valueOf(o).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    // ── 提成上报集团 & 品类提成汇总 ──

    @Override
    @Transactional
    public int reportToGroup(String company, String batchNo, String agent, String receiptNo, String category) {
        // 显式 delete_flag=0：MyBatis-Plus 的 update 不会自动过滤逻辑删除
        LambdaUpdateWrapper<OpsBusinessData> uw = new LambdaUpdateWrapper<>();
        uw.set(OpsBusinessData::getCalcStatus, REPORTED_STATUS)
          .eq(OpsBusinessData::getDeleteFlag, 0)
          .ne(OpsBusinessData::getCalcStatus, REPORTED_STATUS);
        if (company != null && !company.isBlank()) uw.eq(OpsBusinessData::getCompany, company.trim());
        if (batchNo != null && !batchNo.isBlank()) uw.eq(OpsBusinessData::getBatchNo, batchNo.trim());
        if (agent != null && !agent.isBlank()) uw.eq(OpsBusinessData::getAgent, agent.trim());
        if (receiptNo != null && !receiptNo.isBlank()) uw.eq(OpsBusinessData::getReceiptNo, receiptNo.trim());
        if (category != null && !category.isBlank()) uw.eq(OpsBusinessData::getCategory, category.trim());
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (noVisibleCorp(visibleIds)) {
            throw BusinessException.forbidden("无权操作该数据");
        }
        if (visibleIds != null && !visibleIds.isEmpty()) uw.in(OpsBusinessData::getCorpId, visibleIds);

        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<OpsBusinessData>()
                .select(OpsBusinessData::getOrderId)
                .eq(OpsBusinessData::getDeleteFlag, 0)
                .ne(OpsBusinessData::getCalcStatus, REPORTED_STATUS);
        if (company != null && !company.isBlank()) qw.eq(OpsBusinessData::getCompany, company.trim());
        if (batchNo != null && !batchNo.isBlank()) qw.eq(OpsBusinessData::getBatchNo, batchNo.trim());
        if (agent != null && !agent.isBlank()) qw.eq(OpsBusinessData::getAgent, agent.trim());
        if (receiptNo != null && !receiptNo.isBlank()) qw.eq(OpsBusinessData::getReceiptNo, receiptNo.trim());
        if (category != null && !category.isBlank()) qw.eq(OpsBusinessData::getCategory, category.trim());
        if (visibleIds != null && !visibleIds.isEmpty()) qw.in(OpsBusinessData::getCorpId, visibleIds);
        Set<Long> affectedOrders = mapper.selectList(qw).stream()
                .map(OpsBusinessData::getOrderId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int updated = mapper.update(null, uw);
        // 订单级上报：命中筛选条件的订单，其余产品/经办人行一并上报，
        // 避免“部分上报”把整单锁死却仍有未上报明细无法重算/退货
        if (!affectedOrders.isEmpty()) {
            LambdaUpdateWrapper<OpsBusinessData> expand = new LambdaUpdateWrapper<>();
            expand.in(OpsBusinessData::getOrderId, affectedOrders)
                    .eq(OpsBusinessData::getDeleteFlag, 0)
                    .and(w -> w.isNull(OpsBusinessData::getCalcStatus)
                            .or().ne(OpsBusinessData::getCalcStatus, REPORTED_STATUS))
                    .set(OpsBusinessData::getCalcStatus, REPORTED_STATUS);
            if (visibleIds != null && !visibleIds.isEmpty()) {
                expand.in(OpsBusinessData::getCorpId, visibleIds);
            }
            updated += mapper.update(null, expand);
        }
        syncOrderStatus(affectedOrders);
        return updated;
    }

    @Override
    public void exportCommissionSummary(String company, String batchNo, String agent, String receiptNo,
                                        String category, HttpServletResponse response) {
        try {
            // 先确保品类字典已加载：KEY_TO_CN 需含平台全量字典（如 气密性检测→qimi），
            // 否则汇总 Excel 品类列会显示原始 item_key 而非中文 item_value
            loadValidCategories();
            Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
            List<Long> corpIds = (visibleIds == null || visibleIds.isEmpty()) ? null : new ArrayList<>(visibleIds);
            List<Map<String, Object>> allRows = mapper.categoryCompanyStats(
                    company, batchNo, agent, receiptNo, category, corpIds);
            List<Map<String, Object>> monthRows = mapper.categoryCompanyStatsByMonth(
                    company, batchNo, agent, receiptNo, category, corpIds);
            String zipFile = buildCommissionSummaryZip(allRows, monthRows);
            String fileName = "品类提成汇总_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".zip";
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=\"=?UTF-8?B?"
                    + Base64.getEncoder().encodeToString(fileName.getBytes(StandardCharsets.UTF_8))
                    + "?=\"; filename*=UTF-8''" + URLEncoder.encode(fileName, "UTF-8"));
            Files.copy(Path.of(zipFile), response.getOutputStream());
            response.getOutputStream().flush();
            Files.deleteIfExists(Path.of(zipFile));
        } catch (Exception e) {
            log.error("品类提成汇总导出失败", e);
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":500,\"msg\":\"汇总导出失败，请稍后重试\"}");
            } catch (Exception ignored) {}
        }
    }

    /** 双文件 ZIP：提成金额.xlsx + 销售金额.xlsx。每个文件 sheet 序 = 「全部汇总」+ 逐月
     *  （数据起始月 → 末尾月，按月提成/销售金额各品类汇总）。两文件共用同一品类×公司轴
     *  （取自全部汇总行），保证各 sheet 列对齐。返回 zip 临时文件路径。 */
    private String buildCommissionSummaryZip(List<Map<String, Object>> allRows,
                                             List<Map<String, Object>> monthRows) throws Exception {
        List<String> categoryKeys = collectCategoryKeys(allRows);
        List<String> companies = collectCompanies(allRows);
        Map<String, BigDecimal[]> cellAll = buildCellMap(allRows);
        List<String> months = collectMonths(monthRows);

        // cell 数组索引：0=销售额，1=提成金额（见 buildCellMap），按文件名各取对应值
        String commFile = writeSummaryXlsx(
                buildMonthlySheets(categoryKeys, companies, cellAll, monthRows, months, 1));
        String saleFile = writeSummaryXlsx(
                buildMonthlySheets(categoryKeys, companies, cellAll, monthRows, months, 0));

        String dateTag = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path zipPath = Files.createTempFile("commission_summary_", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            addZipEntry(zos, "提成金额汇总_" + dateTag + ".xlsx", commFile);
            addZipEntry(zos, "销售金额汇总_" + dateTag + ".xlsx", saleFile);
        } finally {
            Files.deleteIfExists(Path.of(commFile));
            Files.deleteIfExists(Path.of(saleFile));
        }
        return zipPath.toAbsolutePath().toString();
    }

    /** 数据中出现的销售月份（YYYY-MM），升序 = 起始月 → 末尾月。 */
    private List<String> collectMonths(List<Map<String, Object>> monthRows) {
        return monthRows.stream()
                .map(r -> String.valueOf(r.get("saleMonth")))
                .filter(m -> m != null && !m.isBlank() && !"null".equals(m))
                .distinct().sorted().toList();
    }

    /** 组装单个文件的 sheet 列表：全部汇总 + 逐月（月矩阵轴与全部汇总一致，缺值补 0）。 */
    private List<Map<String, Object>> buildMonthlySheets(List<String> categoryKeys, List<String> companies,
                                                         Map<String, BigDecimal[]> cellAll,
                                                         List<Map<String, Object>> monthRows,
                                                         List<String> months, int metricIdx) {
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(buildSummarySheet("全部汇总", categoryKeys, companies, cellAll, metricIdx));
        for (String m : months) {
            sheets.add(buildSummarySheet(m, categoryKeys, companies, buildCellMap(monthRows, m), metricIdx));
        }
        return sheets;
    }

    private void addZipEntry(ZipOutputStream zos, String entryName, String filePath) throws Exception {
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(Path.of(filePath), zos);
        zos.closeEntry();
    }

    /** 品类行轴：CATEGORY_ORDER 固定顺序（户内美装三品类相邻）；未在映射中的品类追加在末尾。 */
    private List<String> collectCategoryKeys(List<Map<String, Object>> rows) {
        List<String> categoryKeys = new ArrayList<>();
        for (String k : CATEGORY_ORDER) {
            if (KEY_TO_CN.containsKey(k)) categoryKeys.add(k);
        }
        Set<String> presentCategories = new HashSet<>();
        for (Map<String, Object> r : rows) {
            Object c = r.get("category");
            if (c != null) presentCategories.add(String.valueOf(c));
        }
        for (String c : presentCategories) {
            if (!categoryKeys.contains(c)) categoryKeys.add(c);
        }
        return categoryKeys;
    }

    /** 公司列轴：按固定顺序，其余按名称排序追加（列头 = 组织简称 + "公司"）。 */
    private List<String> collectCompanies(List<Map<String, Object>> rows) {
        Set<String> presentCompanies = new HashSet<>();
        for (Map<String, Object> r : rows) {
            Object c = r.get("company");
            if (c != null) presentCompanies.add(String.valueOf(c));
        }
        List<String> companies = new ArrayList<>();
        for (String c : COMPANY_ORDER) if (presentCompanies.contains(c)) companies.add(c);
        List<String> extras = new ArrayList<>();
        for (String c : presentCompanies) if (!companies.contains(c)) extras.add(c);
        extras.sort(String::compareTo);
        companies.addAll(extras);
        return companies;
    }

    /** 单元格定位：category + "::" + company → {销售额, 提成金额}。 */
    private Map<String, BigDecimal[]> buildCellMap(List<Map<String, Object>> rows) {
        Map<String, BigDecimal[]> cell = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String key = String.valueOf(r.get("category")) + "::" + String.valueOf(r.get("company"));
            cell.put(key, new BigDecimal[]{
                    toDecimal(r.get("totalSale")), toDecimal(r.get("totalCommission"))});
        }
        return cell;
    }

    /** 单元格定位（按月过滤）：只取 saleMonth 等于指定月份的汇总行。 */
    private Map<String, BigDecimal[]> buildCellMap(List<Map<String, Object>> rows, String month) {
        Map<String, BigDecimal[]> cell = new HashMap<>();
        for (Map<String, Object> r : rows) {
            if (!month.equals(String.valueOf(r.get("saleMonth")))) continue;
            String key = String.valueOf(r.get("category")) + "::" + String.valueOf(r.get("company"));
            cell.put(key, new BigDecimal[]{
                    toDecimal(r.get("totalSale")), toDecimal(r.get("totalCommission"))});
        }
        return cell;
    }

    /** 组装单个 sheet 矩阵：表头 + 品类数据行 + 合计行（含行列合计）。 */
    private Map<String, Object> buildSummarySheet(String title, List<String> categoryKeys,
                                                  List<String> companies,
                                                  Map<String, BigDecimal[]> cell, int metricIdx) {
        List<Object> header = new ArrayList<>();
        header.add("序号"); header.add("品类");
        for (String c : companies) header.add(c + "公司");
        header.add("合计");
        List<List<Object>> dataRows = new ArrayList<>();
        List<Object> totalRow = new ArrayList<>();
        totalRow.add("合计"); totalRow.add("");
        BigDecimal[] colTotals = new BigDecimal[companies.size()];
        java.util.Arrays.fill(colTotals, BigDecimal.ZERO);
        BigDecimal grand = BigDecimal.ZERO;
        BigDecimal[] zero = {BigDecimal.ZERO, BigDecimal.ZERO};
        for (int i = 0; i < categoryKeys.size(); i++) {
            List<Object> row = new ArrayList<>();
            row.add(String.valueOf(i + 1)); row.add(getCategoryDisplayName(categoryKeys.get(i)));
            BigDecimal rowSum = BigDecimal.ZERO;
            for (int j = 0; j < companies.size(); j++) {
                BigDecimal v = cell.getOrDefault(categoryKeys.get(i) + "::" + companies.get(j), zero)[metricIdx];
                row.add(v.setScale(2, RoundingMode.HALF_UP));
                rowSum = rowSum.add(v);
                colTotals[j] = colTotals[j].add(v);
            }
            row.add(rowSum.setScale(2, RoundingMode.HALF_UP));
            grand = grand.add(rowSum);
            dataRows.add(row);
        }
        for (int j = 0; j < companies.size(); j++) totalRow.add(colTotals[j].setScale(2, RoundingMode.HALF_UP));
        totalRow.add(grand.setScale(2, RoundingMode.HALF_UP));
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("title", title);
        sheet.put("header", header);
        sheet.put("rows", dataRows);
        sheet.put("total", totalRow);
        return sheet;
    }

    /** JSON 数据 → python openpyxl 生成 xlsx（临时文件方式，避免 -c 转义问题）。返回 xlsx 路径。 */
    private String writeSummaryXlsx(List<Map<String, Object>> sheets) throws Exception {
        Path jsonFile = Files.createTempFile("commission_summary_", ".json");
        Path pyFile = Files.createTempFile("commission_summary_", ".py");
        Path xlsxFile = Path.of(jsonFile.toString().replace(".json", ".xlsx"));
        Files.writeString(jsonFile, JSON_MAPPER.writeValueAsString(sheets));
        Files.writeString(pyFile, SUMMARY_PYTHON_SCRIPT
                .replace("__JSON__", jsonFile.toAbsolutePath().toString())
                .replace("__XLSX__", xlsxFile.toAbsolutePath().toString()));
        int exit = new ProcessBuilder("python3", pyFile.toAbsolutePath().toString())
                .redirectErrorStream(true).start().waitFor();
        Files.deleteIfExists(jsonFile); Files.deleteIfExists(pyFile);
        if (exit != 0) throw new IllegalStateException("python3 openpyxl 执行失败, exit=" + exit);
        return xlsxFile.toAbsolutePath().toString();
    }

    private static BigDecimal toDecimal(Object o) {
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        if (o == null) return BigDecimal.ZERO;
        try { return new BigDecimal(String.valueOf(o)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** 汇总 Excel 生成脚本：双 sheet 矩阵，表头/合计加粗、细边框、数字格式 #,##0.00，合计行合并 A:B */
    private static final String SUMMARY_PYTHON_SCRIPT = """
            import json, openpyxl
            from openpyxl.styles import Font, Alignment, Border, Side
            sheets = json.load(open('__JSON__', encoding='utf-8'))
            wb = openpyxl.Workbook()
            wb.remove(wb.active)
            thin = Side(style='thin', color='999999')
            border = Border(left=thin, right=thin, top=thin, bottom=thin)
            bold = Font(bold=True)
            for s in sheets:
                ws = wb.create_sheet(title=s['title'])
                ws.append(s['header'])
                for r in s['rows']:
                    ws.append(r)
                ws.append(s['total'])
                for row in ws.iter_rows(min_row=1, max_row=ws.max_row, min_col=1, max_col=len(s['header'])):
                    for cell in row:
                        cell.border = border
                        cell.alignment = Alignment(horizontal='center', vertical='center')
                        if isinstance(cell.value, float):
                            cell.number_format = '#,##0.00'
                for cell in ws[1]:
                    cell.font = bold
                last = ws.max_row
                for cell in ws[last]:
                    cell.font = bold
                ws.merge_cells(start_row=last, start_column=1, end_row=last, end_column=2)
                ws.column_dimensions['A'].width = 6
                ws.column_dimensions['B'].width = 20
                for col in range(3, len(s['header']) + 1):
                    ws.column_dimensions[openpyxl.utils.get_column_letter(col)].width = 14
            wb.save('__XLSX__')
            """;

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (noVisibleCorp(visibleIds)) {
            throw BusinessException.forbidden("无权删除该数据");
        }
        // 锁定守卫：已上报集团的数据禁止删除（所有组织一致）；与删除同口径做 corp 范围限定，
        // 防止他组织不可见的已上报行误阻断本组织的删除请求
        LambdaQueryWrapper<OpsBusinessData> guardQw = new LambdaQueryWrapper<OpsBusinessData>()
                .in(OpsBusinessData::getId, ids)
                .eq(OpsBusinessData::getCalcStatus, REPORTED_STATUS);
        if (visibleIds != null && !visibleIds.isEmpty()) {
            guardQw.in(OpsBusinessData::getCorpId, visibleIds);
        }
        long reported = mapper.selectCount(guardQw);
        if (reported > 0) {
            throw BusinessException.of("所选数据包含" + reported + "条已上报集团记录，已上报数据禁止删除");
        }
        LambdaQueryWrapper<OpsBusinessData> itemsQw = new LambdaQueryWrapper<OpsBusinessData>()
                .select(OpsBusinessData::getId, OpsBusinessData::getOrderId)
                .in(OpsBusinessData::getId, ids);
        if (visibleIds != null && !visibleIds.isEmpty()) {
            itemsQw.in(OpsBusinessData::getCorpId, visibleIds);
        }
        List<OpsBusinessData> items = mapper.selectList(itemsQw);
        Set<Long> orderIds = items.stream().map(OpsBusinessData::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        lockAndAssertDeletable(orderIds);
        // physical DELETE bypassing @TableLogic, but enforce corp_id scope
        String in = ids.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse("");
        StringBuilder sql = new StringBuilder("DELETE FROM ops_business_order_item WHERE id IN (").append(in).append(")");
        if (visibleIds != null && !visibleIds.isEmpty()) {
            String corpIn = visibleIds.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse("");
            sql.append(" AND corp_id IN (").append(corpIn).append(")");
        }
        java.sql.Connection conn = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try (var stmt = conn.createStatement()) {
            stmt.executeUpdate(sql.toString());
        } catch (Exception e) {
            throw new RuntimeException("批量删除失败", e);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, dataSource);
        }
        // 主表随剩余明细重算；已无明细的订单头删除
        for (Long orderId : orderIds) {
            orderService.refreshTotals(orderId);
            orderService.syncStatus(orderId);
            orderService.refreshAgentSummary(orderId);
        }
        orderService.deleteEmptyOrders(orderIds);
    }

    /**
     * 删除前锁定订单明细并二次校验：与 SCM 退货反写统一按订单 id 升序加锁，
     * 串行化「删除 vs 新增退货分录」，防止首次守卫后并发插入已上报/退货行。
     */
    private void lockAndAssertDeletable(Set<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return;
        for (Long orderId : orderIds.stream().sorted().toList()) {
            jdbcTemplate.queryForList(
                    "SELECT id FROM ops_business_order_item WHERE order_id = ? "
                            + "AND delete_flag = 0 ORDER BY id FOR UPDATE",
                    Long.class, orderId);
        }
        Long reported = mapper.selectCount(new LambdaQueryWrapper<OpsBusinessData>()
                .in(OpsBusinessData::getOrderId, orderIds)
                .eq(OpsBusinessData::getCalcStatus, REPORTED_STATUS)
                .eq(OpsBusinessData::getDeleteFlag, 0));
        if (reported != null && reported > 0) {
            throw BusinessException.of("所选订单包含已上报集团数据，禁止删除");
        }
        // 退货分录由 SCM 反写，删除订单前必须先在供应链管理反审核，避免账实不一致
        Long returnRows = mapper.selectCount(new LambdaQueryWrapper<OpsBusinessData>()
                .in(OpsBusinessData::getOrderId, orderIds)
                .lt(OpsBusinessData::getSaleQuantity, 0)
                .eq(OpsBusinessData::getDeleteFlag, 0));
        if (returnRows != null && returnRows > 0) {
            throw BusinessException.of("所选订单包含退货分录，请先在供应链管理反审核退货");
        }
    }

    @Override
    @Transactional
    public int batchCheck(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) throw BusinessException.of("批次号不能为空");
        // 控制器的 isOperatorOrSuperAdmin 是组织级判定；SQL 必须再按当前用户可见 corp 收紧，
        // 否则运营商组织内任意用户可传他组织批次号（跨租户改状态）。
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (noVisibleCorp(visibleIds)) {
            throw BusinessException.forbidden("无权核对该批次");
        }
        Set<Long> affectedOrders = collectOrderIds(batchNo, null, null, visibleIds);
        StringBuilder sql = new StringBuilder(
                "UPDATE ops_business_order_item SET calc_status = '经办人已核对' "
                        + "WHERE batch_no = ? AND calc_status = '已计算' AND delete_flag = 0");
        if (visibleIds != null) {
            if (visibleIds.isEmpty()) throw BusinessException.forbidden("无权核对该批次");
            String corpCsv = visibleIds.stream().map(String::valueOf)
                    .collect(Collectors.joining(","));
            sql.append(" AND corp_id IN (").append(corpCsv).append(")");
        }
        java.sql.Connection conn = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try (var ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, batchNo);
            int updated = ps.executeUpdate();
            syncOrderStatus(affectedOrders);
            return updated;
        } catch (Exception e) {
            throw new RuntimeException("核对失败", e);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    @Override
    @Transactional
    public int batchCheckOwned(String batchNo, Long corpId, String agentPhone) {
        if (batchNo == null || batchNo.isBlank()) throw BusinessException.of("批次号不能为空");
        if (corpId == null || corpId <= 0 || agentPhone == null || agentPhone.isBlank()) {
            throw BusinessException.forbidden("无权核对该批次");
        }
        Set<Long> affectedOrders = collectOrderIds(batchNo, corpId, agentPhone, null);
        // 员工自助核对只允许写本人 + 本组织的数据，避免任意批次号跨租户改状态
        String sql = "UPDATE ops_business_order_item SET calc_status = '经办人已核对' "
                + "WHERE batch_no = ? AND corp_id = ? AND agent_phone = ? "
                + "AND calc_status = '已计算' AND delete_flag = 0";
        java.sql.Connection conn = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, batchNo);
            ps.setLong(2, corpId);
            ps.setString(3, agentPhone);
            int updated = ps.executeUpdate();
            syncOrderStatus(affectedOrders);
            return updated;
        } catch (Exception e) {
            throw new RuntimeException("核对失败", e);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    /** 待变更状态的明细所属订单（用于更新后同步主表状态）。 */
    private Set<Long> collectOrderIds(String batchNo, Long corpId, String agentPhone,
                                      Set<Long> visibleIds) {
        LambdaQueryWrapper<OpsBusinessData> qw = new LambdaQueryWrapper<OpsBusinessData>()
                .select(OpsBusinessData::getOrderId)
                .eq(OpsBusinessData::getBatchNo, batchNo)
                .eq(OpsBusinessData::getCalcStatus, "已计算");
        if (corpId != null) qw.eq(OpsBusinessData::getCorpId, corpId);
        if (agentPhone != null) qw.eq(OpsBusinessData::getAgentPhone, agentPhone);
        if (visibleIds != null && !visibleIds.isEmpty()) qw.in(OpsBusinessData::getCorpId, visibleIds);
        return mapper.selectList(qw).stream().map(OpsBusinessData::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private void syncOrderStatus(Set<Long> orderIds) {
        for (Long orderId : orderIds) orderService.syncStatus(orderId);
    }

    /** 空可见组织集合 = 无任何数据权限（区别于超管的 null），不得退化为“全量”。 */
    private static boolean noVisibleCorp(Set<Long> visibleIds) {
        return visibleIds != null && visibleIds.isEmpty();
    }

    /** 子表会员回写后同步主表 platform_member_id（订单级字段以主表为准）。 */
    private void syncMasterMember(Long orderId, Long memberId) {
        if (orderId == null) return;
        jdbcTemplate.update("UPDATE ops_business_order SET platform_member_id = ? WHERE id = ?",
                memberId, orderId);
    }

    /**
     * Append corp_id scope filter to query wrapper.
     * null visibleIds (super admin) → no filter added.
     */
    private void applyCorpScope(LambdaQueryWrapper<OpsBusinessData> qw) {
        Set<Long> visibleIds = dataScopeService.getVisibleCorpIds();
        if (visibleIds != null && !visibleIds.isEmpty()) {
            qw.in(OpsBusinessData::getCorpId, visibleIds);
        }
    }

    // ── Org hierarchy rule inheritance ──

    /**
     * Build a corp→parentCorpId map for all corps in the given set.
     * Queries platform internal API; failures are logged and result in empty parent
     * (no inheritance) — safe fallback.
     */
    private Map<Long, Long> buildParentChainMap(Set<Long> corpIds) {
        Map<Long, Long> parentMap = new HashMap<>();
        for (Long id : corpIds) {
            if (id == 0L) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = platformInternalClient.get(
                        "/api/internal/corp/" + id, null);
                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                if (data != null && data.get("parentCorpId") instanceof Number) {
                    parentMap.put(id, ((Number) data.get("parentCorpId")).longValue());
                }
            } catch (Exception e) {
                log.debug("Failed to resolve parent for corpId={}, using self-only rules: {}", id, e.getMessage());
            }
        }
        return parentMap;
    }

    /**
     * Resolve the parent corp ID for a single corp.
     * Calls platform internal API; returns null on failure — safe fallback.
     */
    private Long resolveParentId(Long corpId) {
        if (corpId == null || corpId == 0L) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/internal/corp/" + corpId, null);
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data != null && data.get("parentCorpId") instanceof Number) {
                return ((Number) data.get("parentCorpId")).longValue();
            }
        } catch (Exception e) {
            log.debug("Failed to resolve parent for corpId={}: {}", corpId, e.getMessage());
        }
        return null;
    }

    /**
     * Cascade parent rules down to child corps, plus platform-level rules (corpId=0)
     * to every corp. Uses putIfAbsent throughout — corp-specific rules always
     * take priority over inherited ones.
     * <p>Priority chain: own corp → parent → grandparent → corpId=0 (platform fallback)
     */
    private void cascadeParentRules(
            Map<Long, Map<String, OpsPersonalCommissionRule>> ruleMapByCorp,
            Map<Long, Long> parentMap) {
        // Collect all corp IDs that need rules (ensure entries exist for children
        // that have no rules of their own)
        Set<Long> allCorpIds = new HashSet<>(ruleMapByCorp.keySet());
        for (Long child : parentMap.keySet()) {
            allCorpIds.add(child);
        }
        for (Long corpId : allCorpIds) {
            ruleMapByCorp.computeIfAbsent(corpId, k -> new HashMap<>());
        }

        // Step 1: Cascade parent rules down the org hierarchy
        for (Map.Entry<Long, Map<String, OpsPersonalCommissionRule>> entry : ruleMapByCorp.entrySet()) {
            Long corpId = entry.getKey();
            Map<String, OpsPersonalCommissionRule> childRules = entry.getValue();

            // Walk up the parent chain
            Long parentId = parentMap.get(corpId);
            while (parentId != null && parentId != 0L) {
                Map<String, OpsPersonalCommissionRule> parentRules = ruleMapByCorp.get(parentId);
                if (parentRules != null) {
                    for (Map.Entry<String, OpsPersonalCommissionRule> pr : parentRules.entrySet()) {
                        childRules.putIfAbsent(pr.getKey(), pr.getValue());
                    }
                }
                parentId = parentMap.get(parentId); // walk up further
            }
        }

        // Step 2: Cascade platform-level rules (corpId=0) to ALL corps as last-resort fallback
        Map<String, OpsPersonalCommissionRule> platformRules = ruleMapByCorp.get(0L);
        if (platformRules != null && !platformRules.isEmpty()) {
            for (Map.Entry<Long, Map<String, OpsPersonalCommissionRule>> entry : ruleMapByCorp.entrySet()) {
                if (entry.getKey() == 0L) continue;
                for (Map.Entry<String, OpsPersonalCommissionRule> pr : platformRules.entrySet()) {
                    entry.getValue().putIfAbsent(pr.getKey(), pr.getValue());
                }
            }
            log.info("Platform-level rules (corpId=0) cascaded to {} corps as fallback",
                    ruleMapByCorp.size() - 1);
        }

        int inherited = 0;
        for (Map.Entry<Long, Map<String, OpsPersonalCommissionRule>> entry : ruleMapByCorp.entrySet()) {
            if (parentMap.containsKey(entry.getKey()) && !entry.getValue().isEmpty()) {
                inherited++;
            }
        }
        log.info("Rule inheritance cascaded: {} child corps received parent rules", inherited);
    }

    /**
     * Parse Excel to tab-separated text via Python/openpyxl.
     * Newlines inside cells are replaced with spaces to prevent the
     * one-row-per-line output contract from being broken.
     */
    private String parseExcelWithPython(Path file) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "-c",
                "import openpyxl\n"
                        + "wb = openpyxl.load_workbook('" + file.toAbsolutePath() + "')\n"
                        + "ws = wb.active\n"
                        + "fills = {}\n"
                        + "for mg in ws.merged_cells.ranges:\n"
                        + "    val = ws.cell(mg.min_row, mg.min_col).value\n"
                        + "    for r in range(mg.min_row, mg.max_row + 1):\n"
                        + "        for c in range(mg.min_col, mg.max_col + 1):\n"
                        + "            fills[(r, c)] = val\n"
                        + "for row_idx, row in enumerate(ws.iter_rows(values_only=True), 1):\n"
                        + "    vals = []\n"
                        + "    for col_idx, v in enumerate(row, 1):\n"
                        + "        if v is None and (row_idx, col_idx) in fills:\n"
                        + "            v = fills[(row_idx, col_idx)]\n"
                        + "        vals.append(str(v).replace('\\n',' ').replace('\\r',' ') if v is not None else '')\n"
                        + "    print('\\t'.join(vals))\n");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), "UTF-8");
        p.waitFor();
        return out;
    }

}
