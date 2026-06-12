package com.aip.ops.service.impl;

import com.aip.ops.entity.GsProduct;
import com.aip.ops.entity.MerchantMiniappConfig;
import com.aip.ops.entity.OperatorMerchant;
import com.aip.ops.entity.ProductCategory;
import com.aip.ops.mapper.*;
import com.aip.ops.service.IGsProductService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品展示管理 Service 实现。
 *
 * 核心逻辑：
 * 1. 管理员看到的商品 = ops_product JOIN mall_product，按 operator_id 过滤，
 *    且仅显示已绑定销售商（biz_operator_merchant）的商品
 * 2. 用户端看到的商品 = ops_product JOIN mall_product，按 shelf_status=1 过滤
 * 3. 上下架/分类操作前校验商品是否属于当前运营商绑定的销售商
 */
@Service
@RequiredArgsConstructor
public class GsProductServiceImpl implements IGsProductService {

    private final GsProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final OperatorMerchantMapper omMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantMiniappConfigMapper miniappConfigMapper;

    // ═══ 管理员商品管理 ═══

    @Override
    public Map<String, Object> adminProductPage(Long operatorId, int pageNum, int pageSize,
                                                 Integer shelfStatus, Long categoryId, Long merchantId, String keyword) {
        // 1. 获取当前运营商绑定的所有销售商 ID
        Set<Long> boundMerchantIds = getBoundMerchantIds(operatorId);

        // 2. 未绑定任何销售商 → 直接返回空列表
        if (boundMerchantIds.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("records", Collections.emptyList());
            empty.put("total", 0L);
            empty.put("size", pageSize);
            empty.put("current", pageNum);
            return empty;
        }

        // 3. 若前端指定了 merchantId 筛选，校验其是否在绑定列表中
        List<Long> corpIds;
        if (merchantId != null) {
            if (!boundMerchantIds.contains(merchantId)) {
                // 请求的销售商不在绑定列表中 → 返回空
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("records", Collections.emptyList());
                empty.put("total", 0L);
                empty.put("size", pageSize);
                empty.put("current", pageNum);
                return empty;
            }
            corpIds = Collections.singletonList(merchantId);
        } else {
            corpIds = new ArrayList<>(boundMerchantIds);
        }
        int offset = (pageNum - 1) * pageSize;

        // 4. 按绑定销售商过滤商品
        List<Map<String, Object>> records = productMapper.selectProductList(
                operatorId, corpIds, offset, pageSize, shelfStatus, categoryId, keyword);
        long total = productMapper.countProductList(operatorId, corpIds, shelfStatus, categoryId, keyword);

        // 5. 批量加载销售商名称（id → name）和小程序跳转配置
        Map<Long, String> merchantNameMap = loadMerchantNames(boundMerchantIds);
        Map<Long, Map<String, Object>> miniappConfigMap = loadMiniappConfigs(boundMerchantIds);

        // 6. 富化每条记录
        List<Map<String, Object>> enriched = records.stream().map(rec -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rec.get("mappingId"));
            m.put("productId", rec.get("productId"));
            m.put("name", rec.get("name"));
            m.put("coverImage", rec.get("coverImage"));
            m.put("images", rec.get("images"));
            m.put("videoUrl", rec.get("videoUrl"));
            m.put("description", rec.get("description"));
            m.put("pointsPrice", rec.get("pointsPrice"));
            // 销售价 + 积分兑换配置
            Object salePriceObj = rec.get("salePrice");
            BigDecimal salePrice = salePriceObj != null ? new BigDecimal(salePriceObj.toString()) : BigDecimal.ZERO;
            m.put("salePrice", salePrice);
            Object ptsObj = rec.get("points");
            Object excObj = rec.get("exchangeAmount");
            int pts = ptsObj != null ? Integer.parseInt(ptsObj.toString()) : 0;
            BigDecimal exc = excObj != null ? new BigDecimal(excObj.toString()) : BigDecimal.ZERO;
            m.put("points", pts);
            m.put("exchangeAmount", exc);
            BigDecimal cash = pts > 0 ? salePrice.subtract(exc) : salePrice;
            m.put("cashPrice", cash.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : cash);
            m.put("maxPointsDeduct", rec.get("maxPointsDeduct"));
            m.put("clickCount", rec.get("clickCount") != null ? rec.get("clickCount") : 0);
            m.put("status", rec.get("status"));
            m.put("shelfStatus", rec.get("shelfStatus"));
            m.put("categoryId", rec.get("categoryId"));
            // 分类名称
            Object cid = rec.get("categoryId");
            if (cid != null) {
                ProductCategory cat = categoryMapper.selectById(Long.valueOf(cid.toString()));
                m.put("categoryName", cat != null ? cat.getName() : "-");
            } else {
                m.put("categoryName", "-");
            }
            // 所属销售商（从 mall_product.corp_id 解析）
            Object merchantIdObj = rec.get("merchantId");
            String merchantName = "-";
            if (merchantIdObj != null) {
                Long mid = Long.valueOf(merchantIdObj.toString());
                merchantName = merchantNameMap.getOrDefault(mid, "未知销售商");
            }
            m.put("merchantId", merchantIdObj);
            m.put("merchantName", merchantName);
            // 小程序跳转配置（路径模板中 {productId} 替换为 mall_product.id）
            if (merchantIdObj != null) {
                Long mid = Long.valueOf(merchantIdObj.toString());
                if (miniappConfigMap.containsKey(mid)) {
                    Map<String, Object> cfg = new LinkedHashMap<>(miniappConfigMap.get(mid));
                    Object pid = rec.get("productId");
                    if (pid != null && cfg.containsKey("path")) {
                        cfg.put("path", cfg.get("path").toString().replace("{productId}", pid.toString()));
                    }
                    m.put("miniappConfig", cfg);
                }
            }
            m.put("createTime", rec.get("createTime"));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", enriched);
        result.put("total", total);
        result.put("size", pageSize);
        result.put("current", pageNum);
        return result;
    }

    @Override
    public Map<String, Object> productDetailByMappingId(Long mappingId) {
        Map<String, Object> detail = productMapper.selectProductByMappingId(mappingId);
        if (detail != null) {
            Object midObj = detail.get("merchantId");
            if (midObj != null) {
                Long mid = Long.valueOf(midObj.toString());
                Map<Long, Map<String, Object>> configs = loadMiniappConfigs(Collections.singleton(mid));
                if (configs.containsKey(mid)) {
                    Map<String, Object> cfg = new LinkedHashMap<>(configs.get(mid));
                    Object pid = detail.get("productId");
                    if (pid != null && cfg.containsKey("path")) {
                        cfg.put("path", cfg.get("path").toString().replace("{productId}", pid.toString()));
                    }
                    detail.put("miniappConfig", cfg);
                }
            }
        }
        return detail;
    }

    @Override
    public void updateShelfStatus(Long mappingId, Integer shelfStatus) {
        // 单个操作暂不做权限校验（由批量操作统一入口），保留灵活性
        GsProduct p = new GsProduct();
        p.setId(mappingId);
        p.setShelfStatus(shelfStatus);
        productMapper.updateById(p);
    }

    @Override
    @Transactional
    public int batchUpdateShelfStatus(List<Long> ids, Integer shelfStatus, Long operatorId) {
        assertProductsBelongToOperator(ids, operatorId);
        GsProduct p = new GsProduct();
        p.setShelfStatus(shelfStatus);
        int count = 0;
        for (Long id : ids) {
            p.setId(id);
            productMapper.updateById(p);
            count++;
        }
        return count;
    }

    @Override
    @Transactional
    public int batchUpdateCategory(List<Long> ids, Long categoryId, Long operatorId) {
        assertProductsBelongToOperator(ids, operatorId);
        GsProduct p = new GsProduct();
        p.setCategoryId(categoryId);
        int count = 0;
        for (Long id : ids) {
            p.setId(id);
            productMapper.updateById(p);
            count++;
        }
        return count;
    }

    @Override
    @Transactional
    public void updatePointsConfig(Long mappingId, Integer points, BigDecimal exchangeAmount, Long operatorId) {
        assertProductsBelongToOperator(Collections.singletonList(mappingId), operatorId);
        GsProduct p = new GsProduct();
        p.setId(mappingId);
        p.setPoints(points != null ? points : 0);
        p.setExchangeAmount(exchangeAmount != null ? exchangeAmount : BigDecimal.ZERO);
        productMapper.updateById(p);
    }

    @Override
    @Transactional
    public int batchUpdatePointsConfig(List<Long> ids, Integer points, BigDecimal exchangeAmount, Long operatorId) {
        assertProductsBelongToOperator(ids, operatorId);
        GsProduct p = new GsProduct();
        p.setPoints(points != null ? points : 0);
        p.setExchangeAmount(exchangeAmount != null ? exchangeAmount : BigDecimal.ZERO);
        int count = 0;
        for (Long id : ids) {
            p.setId(id);
            productMapper.updateById(p);
            count++;
        }
        return count;
    }

    // ═══ 用户端商品浏览 ═══

    @Override
    public Map<String, Object> publishedProductPage(Long corpId, int pageNum, int pageSize,
                                                     Long categoryId, String keyword) {
        int offset = (pageNum - 1) * pageSize;
        long effectiveCorpId = (corpId != null) ? corpId : 3L;

        // 解析目标销售商 ID 列表：
        // 若传入的 corpId 是运营商（在 biz_operator_merchant 中有绑定记录），
        // 则展示所有已绑定销售商的商品；否则按原始 corpId 过滤（兼容直接指定销售商）
        List<Long> targetCorpIds = resolveTargetMerchantIds(effectiveCorpId);

        List<Map<String, Object>> records = productMapper.selectPublishedProducts(
                targetCorpIds, offset, pageSize, categoryId, keyword);
        long total = productMapper.countPublishedProducts(targetCorpIds, categoryId, keyword);

        // 收集唯一的销售商ID，加载名称和小程序跳转配置
        Set<Long> merchantIds = records.stream()
                .map(r -> r.get("merchantId"))
                .filter(Objects::nonNull)
                .map(mid -> Long.valueOf(mid.toString()))
                .collect(Collectors.toSet());
        Map<Long, String> merchantNameMap = loadMerchantNames(merchantIds);
        Map<Long, Map<String, Object>> miniappConfigMap = loadMiniappConfigs(merchantIds);

        List<Map<String, Object>> mapped = records.stream().map(rec -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rec.get("mappingId"));
            m.put("productId", rec.get("productId"));
            m.put("name", rec.get("name"));
            m.put("coverImage", rec.get("coverImage"));
            // 分类
            Object cidObj = rec.get("categoryId");
            if (cidObj != null) {
                Long cid = Long.valueOf(cidObj.toString());
                m.put("categoryId", cid);
                ProductCategory cat = categoryMapper.selectById(cid);
                m.put("categoryName", cat != null ? cat.getName() : "-");
            } else {
                m.put("categoryId", null);
                m.put("categoryName", "-");
            }
            m.put("pointsPrice", rec.get("pointsPrice"));
            // 销售价 + 积分兑换配置
            Object spObj = rec.get("salePrice");
            BigDecimal sp = spObj != null ? new BigDecimal(spObj.toString()) : BigDecimal.ZERO;
            m.put("salePrice", sp);
            Object pObj = rec.get("points");
            Object eObj = rec.get("exchangeAmount");
            int pv = pObj != null ? Integer.parseInt(pObj.toString()) : 0;
            BigDecimal ev = eObj != null ? new BigDecimal(eObj.toString()) : BigDecimal.ZERO;
            m.put("points", pv);
            m.put("exchangeAmount", ev);
            BigDecimal cp = pv > 0 ? sp.subtract(ev) : sp;
            m.put("cashPrice", cp.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : cp);
            m.put("maxPointsDeduct", rec.get("maxPointsDeduct"));
            m.put("description", rec.get("description"));
            m.put("clickCount", rec.get("clickCount") != null ? rec.get("clickCount") : 0);
            // 所属销售商
            Object midObj = rec.get("merchantId");
            if (midObj != null) {
                Long mid = Long.valueOf(midObj.toString());
                m.put("merchantId", mid);
                m.put("merchantName", merchantNameMap.getOrDefault(mid, "未知销售商"));
            }
            // 小程序跳转配置
            if (midObj != null) {
                Long mid = Long.valueOf(midObj.toString());
                if (miniappConfigMap.containsKey(mid)) {
                    Map<String, Object> cfg = new LinkedHashMap<>(miniappConfigMap.get(mid));
                    Object pid = rec.get("productId");
                    if (pid != null && cfg.containsKey("path")) {
                        cfg.put("path", cfg.get("path").toString().replace("{productId}", pid.toString()));
                    }
                    m.put("miniappConfig", cfg);
                }
            }
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", mapped);
        result.put("total", total);
        result.put("size", pageSize);
        result.put("current", pageNum);
        return result;
    }

    // ═══ 私有辅助方法 ═══

    /**
     * 解析用户端商品查询的目标销售商 ID 列表。
     * 若 corpId 是运营商（在 biz_operator_merchant 中绑定了销售商），
     * 返回所有已绑定销售商 ID；否则返回原始 corpId 作为单元素列表。
     */
    private List<Long> resolveTargetMerchantIds(Long corpId) {
        Set<Long> bound = getBoundMerchantIds(corpId);
        if (!bound.isEmpty()) {
            return new ArrayList<>(bound);
        }
        return Collections.singletonList(corpId);
    }

    /**
     * 获取运营商绑定的所有激活销售商 ID 集合。
     */
    private Set<Long> getBoundMerchantIds(Long operatorId) {
        return omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());
    }

    /**
     * 批量查询小程序跳转配置，返回 merchantId → {appId, path, envVersion} 映射。
     */
    private Map<Long, Map<String, Object>> loadMiniappConfigs(Set<Long> merchantIds) {
        if (merchantIds.isEmpty()) return Collections.emptyMap();
        List<MerchantMiniappConfig> configs = miniappConfigMapper.selectList(
                new LambdaQueryWrapper<MerchantMiniappConfig>()
                        .in(MerchantMiniappConfig::getMerchantId, merchantIds));
        Map<Long, Map<String, Object>> map = new LinkedHashMap<>();
        for (MerchantMiniappConfig cfg : configs) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("appId", cfg.getAppId());
            entry.put("path", cfg.getPathTemplate() != null ? cfg.getPathTemplate() : "");
            entry.put("envVersion", cfg.getEnvVersion() != null ? cfg.getEnvVersion() : "release");
            map.put(cfg.getMerchantId(), entry);
        }
        return map;
    }

    /**
     * 批量查询销售商组织名称，返回 id → name 映射。
     */
    private Map<Long, String> loadMerchantNames(Set<Long> merchantIds) {
        if (merchantIds.isEmpty()) return Collections.emptyMap();
        List<Map<String, Object>> rows = merchantMapper.selectCorpNamesByIds(merchantIds);
        Map<Long, String> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long id = Long.valueOf(row.get("id").toString());
            String name = (String) row.get("name");
            map.put(id, name != null ? name : "未知销售商");
        }
        return map;
    }

    /**
     * 校验待操作的商品是否属于当前运营商绑定的销售商。
     * 通过 ops_product JOIN mall_product，检查 mall_product.corp_id 是否在绑定列表中。
     *
     * @param mappingIds 要操作的商品映射 ID 列表
     * @param operatorId 当前运营商组织 ID
     * @throws IllegalArgumentException 如果有商品不属于绑定的销售商
     */
    private void assertProductsBelongToOperator(List<Long> mappingIds, Long operatorId) {
        Set<Long> boundMerchantIds = getBoundMerchantIds(operatorId);

        if (boundMerchantIds.isEmpty()) {
            throw new IllegalArgumentException("当前运营商未绑定任何销售商，无法操作商品");
        }

        List<Long> authorized = productMapper.selectIdsInMerchants(
                mappingIds, new ArrayList<>(boundMerchantIds));

        if (authorized.size() != mappingIds.size()) {
            Set<Long> authorizedSet = new HashSet<>(authorized);
            List<Long> unauthorized = mappingIds.stream()
                    .filter(id -> !authorizedSet.contains(id))
                    .collect(Collectors.toList());
            throw new IllegalArgumentException("无权操作以下商品（不属于绑定的销售商）: " + unauthorized);
        }
    }
}
