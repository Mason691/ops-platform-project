package com.aip.ops.service;

import com.aip.ops.dto.UpwardPurchaseDtos;
import com.aip.ops.entity.OpsBusinessData;
import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.entity.OpsUpwardPurchase;
import com.aip.ops.mapper.OpsBusinessDataMapper;
import com.aip.ops.mapper.OpsUpwardPurchaseMapper;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 向上采购申请：部长勾选保存、财务核对生成待发起记录、H5 列表与发起状态。
 *
 * <p>库存仅作快照参考，不参与任何校验；财务核对后按「订单+商品组」生成记录，
 * 未勾选/退回的商品只失效 PENDING 记录，不覆盖已发起的采购单。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpwardPurchaseService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REQUESTED = "REQUESTED";
    public static final String STATUS_HANDLED = "HANDLED";
    public static final String STATUS_INVALID = "INVALID";
    private static final Set<String> VALID_STATUSES =
            Set.of(STATUS_PENDING, STATUS_REQUESTED, STATUS_HANDLED, STATUS_INVALID);
    private static final String SELECTION_NODE = "MINISTER_REVIEW";
    private static final int MAX_OTHER_STOCKS = 50;

    private final OpsUpwardPurchaseMapper purchaseMapper;
    private final OpsBusinessDataMapper itemMapper;
    private final BusinessDataService businessDataService;
    private final ObjectMapper objectMapper;

    /** 解析部长审核 formData 中的 upwardPurchaseItems；缺失/为空表示旧客户端，不改动。 */
    public List<UpwardPurchaseDtos.SelectionItem> parseSelections(Map<String, Object> formData) {
        Object raw = formData == null ? null : formData.get("upwardPurchaseItems");
        if (!(raw instanceof List<?> list)) return List.of();
        List<UpwardPurchaseDtos.SelectionItem> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            List<Long> ids = parseIds(map.get("rowIds"));
            if (ids.isEmpty()) continue;
            result.add(new UpwardPurchaseDtos.SelectionItem(
                    str(map.get("productKey")), ids, bool(map.get("checked"))));
        }
        return result;
    }

    /** 部长审核保存：先整单清零，再按勾选商品组置 1；明细已变化时阻断审批。 */
    @Transactional
    public int saveMinisterSelection(OpsBusinessOrder order,
                                     List<UpwardPurchaseDtos.SelectionItem> selections,
                                     Long operatorId, String operatorName) {
        if (order == null || selections == null || selections.isEmpty()) return 0;
        Map<Long, OpsBusinessData> orderRows = itemMapper.selectList(
                        new LambdaQueryWrapper<OpsBusinessData>()
                                .eq(OpsBusinessData::getOrderId, order.getId())
                                .eq(OpsBusinessData::getDeleteFlag, 0))
                .stream().collect(Collectors.toMap(OpsBusinessData::getId, row -> row,
                        (a, b) -> a));
        Map<Long, Boolean> checked = new LinkedHashMap<>();
        for (UpwardPurchaseDtos.SelectionItem item : selections) {
            for (Long id : item.rowIds()) {
                if (id == null) continue;
                OpsBusinessData row = orderRows.get(id);
                if (row == null) {
                    throw BusinessException.of("商品明细已变更，请刷新后重试");
                }
                // 退货负数行不参与向上采购，防止旧客户端/构造请求写入
                if (item.checked() && row.getSaleQuantity() != null
                        && row.getSaleQuantity() <= 0) {
                    continue;
                }
                checked.put(id, item.checked());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        updateUpwardFlag(order.getId(), null, 0, operatorId, operatorName, now);
        Set<Long> selected = checked.entrySet().stream().filter(Map.Entry::getValue)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (!selected.isEmpty()) {
            updateUpwardFlag(order.getId(), selected, 1, operatorId, operatorName, now);
        }
        log.info("部长向上采购勾选 orderId={}, selectedRows={}", order.getId(), selected.size());
        return selected.size();
    }

    /**
     * 财务核对通过：按勾选商品组生成/刷新待发起记录；未勾选的 PENDING 记录置为失效。
     * 已发起/已处理的记录不覆盖，避免丢失已存在的采购单。
     */
    @Transactional
    public int createPending(OpsBusinessOrder order, Map<String, Object> formData,
                             Long operatorId, String operatorName) {
        if (order == null) return 0;
        List<Map<String, Object>> products = businessDataService.listProductsForOrder(order.getId());
        Map<String, Object> snapshots = asMap(formData == null ? null : formData.get("stockSnapshots"));
        Map<Long, OpsUpwardPurchase> byItem = existingByItem(order);
        Set<Long> selected = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> product : products) {
            if (Boolean.TRUE.equals(product.get("isReturnEntry"))
                    || !Boolean.TRUE.equals(product.get("isUpwardPurchase"))) {
                continue;
            }
            List<Long> ids = parseIds(product.get("rowIds"));
            if (ids.isEmpty()) continue;
            Long itemId = ids.get(0);
            selected.add(itemId);
            OpsUpwardPurchase record = byItem.get(itemId);
            if (record != null && (STATUS_REQUESTED.equals(record.getStatus())
                    || STATUS_HANDLED.equals(record.getStatus()))) {
                continue;
            }
            savePendingRecord(order, product, ids, record,
                    asMap(snapshots.get(str(product.get("productKey")))),
                    now, operatorId, operatorName);
        }
        int invalidated = invalidateUnselected(order, selected, now, operatorId, operatorName);
        log.info("财务核对生成向上采购申请 orderId={}, selected={}, invalidated={}",
                order.getId(), selected.size(), invalidated);
        return selected.size();
    }

    /** 财务驳回/暂缓或订单退回：只失效待发起记录，保留已发起/已处理。 */
    @Transactional
    public int invalidatePending(Long orderId, Long corpId, String reason) {
        if (orderId == null || corpId == null) return 0;
        return purchaseMapper.update(null, new LambdaUpdateWrapper<OpsUpwardPurchase>()
                .eq(OpsUpwardPurchase::getOrderId, orderId)
                .eq(OpsUpwardPurchase::getCorpId, corpId)
                .eq(OpsUpwardPurchase::getStatus, STATUS_PENDING)
                .set(OpsUpwardPurchase::getStatus, STATUS_INVALID)
                .set(OpsUpwardPurchase::getInvalidReason, truncate(reason, 500))
                .set(OpsUpwardPurchase::getModifyTime, LocalDateTime.now()));
    }

    /**
     * 订单完成：已发起的记录置为已处理；未发起的置为失效。
     * 派工出库不关闭记录，员工仍可在完工前补发起采购申请。
     */
    @Transactional
    public int closeAfterCompletion(Long orderId, Long corpId) {
        if (orderId == null || corpId == null) return 0;
        LocalDateTime now = LocalDateTime.now();
        int requested = purchaseMapper.update(null, new LambdaUpdateWrapper<OpsUpwardPurchase>()
                .eq(OpsUpwardPurchase::getOrderId, orderId)
                .eq(OpsUpwardPurchase::getCorpId, corpId)
                .eq(OpsUpwardPurchase::getStatus, STATUS_REQUESTED)
                .set(OpsUpwardPurchase::getStatus, STATUS_HANDLED)
                .set(OpsUpwardPurchase::getHandledTime, now)
                .set(OpsUpwardPurchase::getInvalidReason, "订单已完成")
                .set(OpsUpwardPurchase::getModifyTime, now));
        int pending = purchaseMapper.update(null, new LambdaUpdateWrapper<OpsUpwardPurchase>()
                .eq(OpsUpwardPurchase::getOrderId, orderId)
                .eq(OpsUpwardPurchase::getCorpId, corpId)
                .eq(OpsUpwardPurchase::getStatus, STATUS_PENDING)
                .set(OpsUpwardPurchase::getStatus, STATUS_INVALID)
                .set(OpsUpwardPurchase::getInvalidReason, "订单已完成，未发起采购申请")
                .set(OpsUpwardPurchase::getModifyTime, now));
        return requested + pending;
    }

    /** H5 发起采购申请成功/SCM 已存在后回写：同订单全部待发起记录置为已发起。 */
    @Transactional
    public int markRequested(Long corpId, UpwardPurchaseDtos.MarkRequestedRequest request) {
        if (request == null || request.orderId() == null
                || request.billNo() == null || request.billNo().isBlank()) {
            throw BusinessException.of("采购申请参数不完整");
        }
        LocalDateTime now = LocalDateTime.now();
        return purchaseMapper.update(null, new LambdaUpdateWrapper<OpsUpwardPurchase>()
                .eq(OpsUpwardPurchase::getCorpId, corpId)
                .eq(OpsUpwardPurchase::getOrderId, request.orderId())
                .eq(OpsUpwardPurchase::getStatus, STATUS_PENDING)
                .set(OpsUpwardPurchase::getStatus, STATUS_REQUESTED)
                .set(OpsUpwardPurchase::getRequestBillNo, truncate(request.billNo(), 64))
                .set(OpsUpwardPurchase::getRequestTime, now)
                .set(OpsUpwardPurchase::getModifyTime, now));
    }

    public UpwardPurchaseDtos.PageResult page(Long corpId, String status,
                                              int pageNum, int pageSize) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        LambdaQueryWrapper<OpsUpwardPurchase> wrapper = new LambdaQueryWrapper<OpsUpwardPurchase>()
                .eq(OpsUpwardPurchase::getCorpId, corpId)
                .orderByDesc(OpsUpwardPurchase::getFinanceCheckTime)
                .orderByDesc(OpsUpwardPurchase::getId);
        if (status != null && !status.isBlank()) {
            if (!VALID_STATUSES.contains(status)) {
                throw BusinessException.of("申请状态不合法");
            }
            wrapper.eq(OpsUpwardPurchase::getStatus, status);
        }
        Page<OpsUpwardPurchase> page = purchaseMapper.selectPage(
                new Page<>(safePage, safeSize), wrapper);
        List<UpwardPurchaseDtos.RecordView> records = page.getRecords().stream()
                .map(this::toView).toList();
        return new UpwardPurchaseDtos.PageResult(records, page.getTotal(),
                pendingCount(corpId), page.getCurrent(), page.getSize());
    }

    public long pendingCount(Long corpId) {
        return purchaseMapper.selectCount(new LambdaQueryWrapper<OpsUpwardPurchase>()
                .eq(OpsUpwardPurchase::getCorpId, corpId)
                .eq(OpsUpwardPurchase::getStatus, STATUS_PENDING));
    }

    /** 该订单全部待发起记录/明细行；分页列表发起时必须用全量，避免漏项。 */
    public UpwardPurchaseDtos.PendingItems pendingItems(Long corpId, Long orderId) {
        if (corpId == null || orderId == null) return new UpwardPurchaseDtos.PendingItems(
                List.of(), List.of());
        List<OpsUpwardPurchase> records = purchaseMapper.selectList(
                new LambdaQueryWrapper<OpsUpwardPurchase>()
                        .eq(OpsUpwardPurchase::getCorpId, corpId)
                        .eq(OpsUpwardPurchase::getOrderId, orderId)
                        .eq(OpsUpwardPurchase::getStatus, STATUS_PENDING));
        List<Long> itemIds = records.stream()
                .flatMap(record -> parseIds(record.getItemIds()).stream())
                .distinct().toList();
        List<Long> recordIds = records.stream().map(OpsUpwardPurchase::getId).toList();
        return new UpwardPurchaseDtos.PendingItems(itemIds, recordIds);
    }

    // ═══ 内部实现 ═══

    private void updateUpwardFlag(Long orderId, Set<Long> ids, int value,
                                  Long operatorId, String operatorName, LocalDateTime now) {
        LambdaUpdateWrapper<OpsBusinessData> update = new LambdaUpdateWrapper<OpsBusinessData>()
                .eq(OpsBusinessData::getOrderId, orderId)
                .eq(OpsBusinessData::getDeleteFlag, 0)
                .set(OpsBusinessData::getIsUpwardPurchase, value)
                .set(OpsBusinessData::getModifyTime, now);
        if (ids != null) update.in(OpsBusinessData::getId, ids);
        if (operatorId != null) update.set(OpsBusinessData::getModifyUser, operatorId);
        if (operatorName != null && !operatorName.isBlank()) {
            update.set(OpsBusinessData::getModifyUserName, operatorName);
        }
        itemMapper.update(null, update);
    }

    private Map<Long, OpsUpwardPurchase> existingByItem(OpsBusinessOrder order) {
        return purchaseMapper.selectList(new LambdaQueryWrapper<OpsUpwardPurchase>()
                        .eq(OpsUpwardPurchase::getOrderId, order.getId())
                        .eq(OpsUpwardPurchase::getCorpId, order.getCorpId()))
                .stream().collect(Collectors.toMap(OpsUpwardPurchase::getItemId, r -> r,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private void savePendingRecord(OpsBusinessOrder order, Map<String, Object> product,
                                   List<Long> ids, OpsUpwardPurchase record,
                                   Map<String, Object> snapshot, LocalDateTime now,
                                   Long operatorId, String operatorName) {
        boolean insert = record == null;
        if (insert) {
            record = new OpsUpwardPurchase();
            record.setCorpId(order.getCorpId());
            record.setOrderId(order.getId());
            record.setItemId(ids.get(0));
            record.setSelectionNode(SELECTION_NODE);
            record.setStatus(STATUS_PENDING);
            record.setDeleteFlag(0);
            record.setCreateTime(now);
            record.setCreateUser(operatorId);
            record.setCreateUserName(operatorName);
            record.setUniqueId(UUID.randomUUID().toString().replace("-", ""));
        }
        record.setOrderNo(truncate(order.getOrderNo(), 50));
        record.setReceiptNo(truncate(order.getReceiptNo(), 50));
        record.setItemIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        record.setProductKey(truncate(str(product.get("productKey")), 500));
        record.setBatchNo(truncate(order.getBatchNo(), 50));
        record.setCustomerName(truncate(order.getCustomer(), 100));
        record.setCategory(truncate(str(product.get("category")), 50));
        record.setBrand(truncate(str(product.get("brand")), 100));
        record.setModel(truncate(str(product.get("model")), 200));
        record.setQuantity(number(product.get("saleQuantity")));
        record.setSalePrice(number(product.get("salePrice")));
        StockSnapshot stocks = stockSnapshot(snapshot);
        record.setLocalStock(stocks.localStock());
        record.setUnit(stocks.unit());
        record.setOtherStockJson(stocks.otherJson());
        record.setFinanceCheckTime(now);
        record.setStatus(STATUS_PENDING);
        record.setInvalidReason("");
        record.setModifyTime(now);
        record.setModifyUser(operatorId);
        record.setModifyUserName(operatorName);
        if (insert) purchaseMapper.insert(record); else purchaseMapper.updateById(record);
    }

    private int invalidateUnselected(OpsBusinessOrder order, Set<Long> selected,
                                     LocalDateTime now, Long operatorId, String operatorName) {
        LambdaUpdateWrapper<OpsUpwardPurchase> update = new LambdaUpdateWrapper<OpsUpwardPurchase>()
                .eq(OpsUpwardPurchase::getOrderId, order.getId())
                .eq(OpsUpwardPurchase::getCorpId, order.getCorpId())
                .eq(OpsUpwardPurchase::getStatus, STATUS_PENDING)
                .set(OpsUpwardPurchase::getStatus, STATUS_INVALID)
                .set(OpsUpwardPurchase::getInvalidReason, "财务核对未勾选或商品已取消")
                .set(OpsUpwardPurchase::getModifyTime, now);
        if (!selected.isEmpty()) update.notIn(OpsUpwardPurchase::getItemId, selected);
        if (operatorId != null) update.set(OpsUpwardPurchase::getModifyUser, operatorId);
        if (operatorName != null && !operatorName.isBlank()) {
            update.set(OpsUpwardPurchase::getModifyUserName, operatorName);
        }
        return purchaseMapper.update(null, update);
    }

    private UpwardPurchaseDtos.RecordView toView(OpsUpwardPurchase record) {
        return new UpwardPurchaseDtos.RecordView(
                record.getId(), record.getOrderId(), record.getOrderNo(), record.getReceiptNo(),
                record.getBatchNo(), record.getCustomerName(), record.getCategory(),
                record.getBrand(), record.getModel(), record.getQuantity(), record.getSalePrice(),
                record.getUnit(), record.getLocalStock(), parseOtherStocks(record.getOtherStockJson()),
                record.getProductKey(), parseIds(record.getItemIds()), record.getStatus(),
                statusName(record.getStatus()), record.getRequestBillNo(),
                record.getInvalidReason(), record.getSelectionNode(),
                record.getFinanceCheckTime());
    }

    private StockSnapshot stockSnapshot(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return new StockSnapshot(null, "", null);
        List<UpwardPurchaseDtos.OtherStock> others = new ArrayList<>();
        if (raw.get("others") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map) || others.size() >= MAX_OTHER_STOCKS) continue;
                others.add(new UpwardPurchaseDtos.OtherStock(longValue(map.get("corpId")),
                        truncate(str(map.get("corpName")), 100), number(map.get("stock"))));
            }
        }
        return new StockSnapshot(number(raw.get("localStock")),
                truncate(str(raw.get("unit")), 20), writeJson(others));
    }

    private List<UpwardPurchaseDtos.OtherStock> parseOtherStocks(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<UpwardPurchaseDtos.OtherStock>>() { });
        } catch (Exception e) {
            log.warn("向上采购库存快照解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("向上采购库存快照序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    private static List<Long> parseIds(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(UpwardPurchaseService::longValue)
                    .filter(id -> id != null && id > 0).distinct().toList();
        }
        if (value instanceof Number n) {
            return n.longValue() > 0 ? List.of(n.longValue()) : List.of();
        }
        if (value instanceof String text) {
            List<Long> ids = new ArrayList<>();
            for (String part : text.split(",")) {
                Long id = longValue(part);
                if (id != null && id > 0) ids.add(id);
            }
            return ids.stream().distinct().toList();
        }
        return List.of();
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() == 1;
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private static Long longValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal b) return b;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String statusName(String status) {
        return switch (status == null ? "" : status) {
            case STATUS_PENDING -> "待发起";
            case STATUS_REQUESTED -> "已发起";
            case STATUS_HANDLED -> "已处理";
            case STATUS_INVALID -> "已失效";
            default -> status == null ? "" : status;
        };
    }

    private record StockSnapshot(BigDecimal localStock, String unit, String otherJson) {
    }
}
