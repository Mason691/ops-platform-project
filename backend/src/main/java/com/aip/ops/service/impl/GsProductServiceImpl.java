package com.aip.ops.service.impl;

import com.aip.ops.client.ProductBatchClient;
import com.aip.ops.entity.GsProduct;
import com.aip.ops.entity.MerchantMiniappConfig;
import com.aip.ops.entity.OperatorMerchant;
import com.aip.ops.entity.ProductCategory;
import com.aip.ops.mapper.*;
import com.aip.ops.service.IGsProductService;
import com.aip.ops.service.PlatformMerchantService;
import com.aip.ops.util.ImageUrlUtil;
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
 * 核心变更（ADR-005 合规）：
 * - 不再跨库 JOIN mall_product 查询
 * - 改为：查 ops_product → 收集 mall_product_id → ProductBatchClient 批量获取商品详情
 * - 销售商归属校验改为通过 ProductBatchClient 获取 corpId 后在 Service 层完成
 */
@Service
@RequiredArgsConstructor
public class GsProductServiceImpl implements IGsProductService {

    private final GsProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final OperatorMerchantMapper omMapper;
    private final PlatformMerchantService platformMerchantService;
    private final MerchantMiniappConfigMapper miniappConfigMapper;
    private final ProductBatchClient productBatchClient;
    private final ImageUrlUtil imageUrlUtil;

    // ═══ 管理员商品管理 ═══

    @Override
    public Map<String, Object> adminProductPage(Long operatorId, int pageNum, int pageSize,
                                                 Integer shelfStatus, Long categoryId, Long merchantId, String keyword) {
        // 1. 获取当前运营商绑定的所有销售商 ID
        Set<Long> boundMerchantIds = getBoundMerchantIds(operatorId);

        // 2. 未绑定任何销售商 → 直接返回空
        if (boundMerchantIds.isEmpty()) {
            return emptyPage(pageSize, pageNum);
        }

        // 3. 若指定 merchantId 筛选，校验其是否在绑定列表中
        if (merchantId != null && !boundMerchantIds.contains(merchantId)) {
            return emptyPage(pageSize, pageNum);
        }
        Set<Long> targetMerchantIds = (merchantId != null)
                ? Collections.singleton(merchantId) : boundMerchantIds;

        // 4. 查询 ops_product 列表（仅含本地字段，无 mall_product JOIN）
        int offset = (pageNum - 1) * pageSize;
        List<Map<String, Object>> mappings = productMapper.selectProductList(
                operatorId, offset, pageSize, shelfStatus, categoryId);
        long total = productMapper.countProductList(operatorId, shelfStatus, categoryId);

        if (mappings.isEmpty()) {
            return emptyPage(pageSize, pageNum);
        }

        // 5. 收集 mall_product_id，批量获取商品详情
        List<Long> mallProductIds = mappings.stream()
                .map(m -> Long.valueOf(m.get("mallProductId").toString()))
                .distinct().collect(Collectors.toList());
        Map<Long, Map<String, Object>> productMap = productBatchClient.batchQuery(mallProductIds);

        // 6. 按销售商和关键词过滤（替代原 SQL 中的 JOIN 过滤）
        List<Map<String, Object>> filtered = mappings.stream()
                .filter(m -> {
                    Long mpId = Long.valueOf(m.get("mallProductId").toString());
                    Map<String, Object> prod = productMap.get(mpId);
                    if (prod == null) return false;
                    // 销售商过滤
                    Object corpIdObj = prod.get("corpId");
                    if (corpIdObj == null) return false;
                    Long corpId = Long.valueOf(corpIdObj.toString());
                    if (!targetMerchantIds.contains(corpId)) return false;
                    // 关键词过滤
                    if (keyword != null && !keyword.isEmpty()) {
                        String name = (String) prod.get("name");
                        if (name == null || !name.contains(keyword)) return false;
                    }
                    return true;
                }).collect(Collectors.toList());

        // 7. 加载销售商名称和小程序配置（使用过滤后的商家 ID）
        Map<Long, String> merchantNameMap = platformMerchantService.getCorpNames(targetMerchantIds);
        Map<Long, Map<String, Object>> miniappConfigMap = loadMiniappConfigs(targetMerchantIds);

        // 8. 富化每条记录
        List<Map<String, Object>> enriched = filtered.stream().map(rec -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rec.get("mappingId"));
            Long mpId = Long.valueOf(rec.get("mallProductId").toString());
            Map<String, Object> prod = productMap.get(mpId);
            m.put("productId", mpId);
            m.put("name", prod != null ? prod.get("name") : "");
            m.put("coverImage", prod != null ? imageUrlUtil.normalizeProductImage((String) prod.get("coverImage")) : null);
            m.put("images", prod != null ? prod.get("images") : null);
            m.put("videoUrl", prod != null ? prod.get("videoUrl") : null);
            m.put("description", prod != null ? prod.get("description") : null);
            m.put("pointsPrice", prod != null ? prod.get("pointsPrice") : BigDecimal.ZERO);
            // 销售价 + 积分兑换配置
            BigDecimal salePrice = prod != null && prod.get("salePrice") != null
                    ? new BigDecimal(prod.get("salePrice").toString()) : BigDecimal.ZERO;
            m.put("salePrice", salePrice);
            int pts = rec.get("points") != null ? Integer.parseInt(rec.get("points").toString()) : 0;
            BigDecimal exc = rec.get("exchangeAmount") != null
                    ? new BigDecimal(rec.get("exchangeAmount").toString()) : BigDecimal.ZERO;
            m.put("points", pts);
            m.put("exchangeAmount", exc);
            BigDecimal cash = pts > 0 ? salePrice.subtract(exc) : salePrice;
            m.put("cashPrice", cash.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : cash);
            m.put("maxPointsDeduct", prod != null ? prod.get("maxPointsDeduct") : BigDecimal.ZERO);
            m.put("clickCount", prod != null ? prod.get("clickCount") : 0L);
            m.put("status", prod != null ? prod.get("status") : null);
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
            // 所属销售商
            Long mid = prod != null && prod.get("corpId") != null
                    ? Long.valueOf(prod.get("corpId").toString()) : null;
            m.put("merchantId", mid);
            m.put("merchantName", mid != null ? merchantNameMap.getOrDefault(mid, "未知销售商") : "-");
            // 小程序跳转配置
            if (mid != null && miniappConfigMap.containsKey(mid)) {
                Map<String, Object> cfg = new LinkedHashMap<>(miniappConfigMap.get(mid));
                if (cfg.containsKey("path")) {
                    cfg.put("path", cfg.get("path").toString().replace("{productId}", mpId.toString()));
                }
                m.put("miniappConfig", cfg);
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
        // 1. 查 ops_product（仅本地字段）
        Map<String, Object> mapping = productMapper.selectProductByMappingId(mappingId);
        if (mapping == null) return null;

        // 2. 批量获取商品详情（单条）
        Long mpId = Long.valueOf(mapping.get("mallProductId").toString());
        Map<Long, Map<String, Object>> productMap = productBatchClient.batchQuery(Collections.singletonList(mpId));
        Map<String, Object> prod = productMap.get(mpId);

        // 3. 组装结果
        Map<String, Object> detail = new LinkedHashMap<>(mapping);
        detail.put("productId", mpId);
        if (prod != null) {
            detail.put("name", prod.get("name"));
            detail.put("coverImage", prod.get("coverImage"));
            detail.put("images", prod.get("images"));
            detail.put("videoUrl", prod.get("videoUrl"));
            detail.put("description", prod.get("description"));
            detail.put("salePrice", prod.get("salePrice"));
            detail.put("pointsPrice", prod.get("pointsPrice"));
            detail.put("maxPointsDeduct", prod.get("maxPointsDeduct"));
            detail.put("clickCount", prod.get("clickCount"));
            detail.put("status", prod.get("status"));
            detail.put("merchantId", prod.get("corpId"));
            // 小程序跳转配置
            Object midObj = prod.get("corpId");
            if (midObj != null) {
                Long mid = Long.valueOf(midObj.toString());
                Map<Long, Map<String, Object>> configs = loadMiniappConfigs(Collections.singleton(mid));
                if (configs.containsKey(mid)) {
                    Map<String, Object> cfg = new LinkedHashMap<>(configs.get(mid));
                    if (cfg.containsKey("path")) {
                        cfg.put("path", cfg.get("path").toString().replace("{productId}", mpId.toString()));
                    }
                    detail.put("miniappConfig", cfg);
                }
            }
        }
        return detail;
    }

    @Override
    public void updateShelfStatus(Long mappingId, Integer shelfStatus) {
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

        // 解析目标销售商 ID 列表
        List<Long> targetCorpIds = resolveTargetMerchantIds(effectiveCorpId);

        // 查询 ops_product 上架商品（仅本地字段）
        List<Map<String, Object>> mappings = productMapper.selectPublishedProducts(offset, pageSize);
        long total = productMapper.countPublishedProducts();

        if (mappings.isEmpty()) {
            return emptyPage(pageSize, pageNum);
        }

        // 批量获取商品详情
        List<Long> mallProductIds = mappings.stream()
                .map(m -> Long.valueOf(m.get("mallProductId").toString()))
                .distinct().collect(Collectors.toList());
        Map<Long, Map<String, Object>> productMap = productBatchClient.batchQuery(mallProductIds);

        // 按销售商和关键词过滤
        List<Map<String, Object>> filtered = mappings.stream()
                .filter(m -> {
                    Long mpId = Long.valueOf(m.get("mallProductId").toString());
                    Map<String, Object> prod = productMap.get(mpId);
                    if (prod == null) return false;
                    // 销售商过滤
                    Object midObj = prod.get("corpId");
                    if (midObj == null) return false;
                    Long mid = Long.valueOf(midObj.toString());
                    if (!targetCorpIds.contains(mid)) return false;
                    // 关键词过滤
                    if (keyword != null && !keyword.isEmpty()) {
                        String name = (String) prod.get("name");
                        return name != null && name.contains(keyword);
                    }
                    // 状态过滤
                    Object statusObj = prod.get("status");
                    return statusObj != null && Integer.valueOf(statusObj.toString()) == 1;
                }).collect(Collectors.toList());

        // 收集唯一销售商ID
        Set<Long> merchantIds = filtered.stream()
                .map(m -> {
                    Long mpId = Long.valueOf(m.get("mallProductId").toString());
                    Map<String, Object> prod = productMap.get(mpId);
                    return prod != null && prod.get("corpId") != null
                            ? Long.valueOf(prod.get("corpId").toString()) : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> merchantNameMap = platformMerchantService.getCorpNames(merchantIds);
        Map<Long, Map<String, Object>> miniappConfigMap = loadMiniappConfigs(merchantIds);

        // 组装结果
        List<Map<String, Object>> mapped = filtered.stream().map(rec -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rec.get("mappingId"));
            Long mpId = Long.valueOf(rec.get("mallProductId").toString());
            Map<String, Object> prod = productMap.get(mpId);
            m.put("productId", mpId);
            m.put("name", prod != null ? prod.get("name") : "");
            m.put("coverImage", prod != null ? imageUrlUtil.normalizeProductImage((String) prod.get("coverImage")) : null);
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
            m.put("pointsPrice", prod != null ? prod.get("pointsPrice") : BigDecimal.ZERO);
            BigDecimal sp = prod != null && prod.get("salePrice") != null
                    ? new BigDecimal(prod.get("salePrice").toString()) : BigDecimal.ZERO;
            m.put("salePrice", sp);
            int pv = rec.get("points") != null ? Integer.parseInt(rec.get("points").toString()) : 0;
            BigDecimal ev = rec.get("exchangeAmount") != null
                    ? new BigDecimal(rec.get("exchangeAmount").toString()) : BigDecimal.ZERO;
            m.put("points", pv);
            m.put("exchangeAmount", ev);
            BigDecimal cp = pv > 0 ? sp.subtract(ev) : sp;
            m.put("cashPrice", cp.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : cp);
            m.put("maxPointsDeduct", prod != null ? prod.get("maxPointsDeduct") : BigDecimal.ZERO);
            m.put("description", prod != null ? prod.get("description") : null);
            m.put("clickCount", prod != null ? prod.get("clickCount") : 0L);
            // 所属销售商
            Long mid = prod != null && prod.get("corpId") != null
                    ? Long.valueOf(prod.get("corpId").toString()) : null;
            if (mid != null) {
                m.put("merchantId", mid);
                m.put("merchantName", merchantNameMap.getOrDefault(mid, "未知销售商"));
                // 小程序跳转配置
                if (miniappConfigMap.containsKey(mid)) {
                    Map<String, Object> cfg = new LinkedHashMap<>(miniappConfigMap.get(mid));
                    if (cfg.containsKey("path")) {
                        cfg.put("path", cfg.get("path").toString().replace("{productId}", mpId.toString()));
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

    private List<Long> resolveTargetMerchantIds(Long corpId) {
        if (corpId == 0L || corpId == null) {
            List<Long> all = omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                    .eq(OperatorMerchant::getStatus, 1))
                    .stream().map(OperatorMerchant::getMerchantId)
                    .distinct().collect(Collectors.toList());
            return all.isEmpty() ? Collections.singletonList(0L) : all;
        }
        Set<Long> bound = getBoundMerchantIds(corpId);
        if (!bound.isEmpty()) {
            return new ArrayList<>(bound);
        }
        return Collections.singletonList(corpId);
    }

    private Set<Long> getBoundMerchantIds(Long operatorId) {
        return omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());
    }

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
     * 校验待操作的商品是否属于当前运营商绑定的销售商。
     * 通过 ProductBatchClient 获取商品 corpId 后在 Service 层校验，
     * 替代原跨库 JOIN mall_product 的方式。
     */
    private void assertProductsBelongToOperator(List<Long> mappingIds, Long operatorId) {
        Set<Long> boundMerchantIds = getBoundMerchantIds(operatorId);

        if (boundMerchantIds.isEmpty()) {
            throw new IllegalArgumentException("当前运营商未绑定任何销售商，无法操作商品");
        }

        // 查询映射是否存在
        List<Long> existIds = productMapper.selectIdsExist(mappingIds);
        if (existIds.size() != mappingIds.size()) {
            Set<Long> existSet = new HashSet<>(existIds);
            List<Long> missing = mappingIds.stream()
                    .filter(id -> !existSet.contains(id))
                    .collect(Collectors.toList());
            throw new IllegalArgumentException("以下商品映射不存在: " + missing);
        }

        // 获取 mall_product_id → 批量查询产品详情 → 校验 corpId
        List<GsProduct> mappings = productMapper.selectBatchIds(mappingIds);
        List<Long> mallProductIds = mappings.stream()
                .map(GsProduct::getMallProductId).distinct().collect(Collectors.toList());
        Map<Long, Map<String, Object>> productMap = productBatchClient.batchQuery(mallProductIds);

        List<Long> unauthorized = new ArrayList<>();
        for (GsProduct m : mappings) {
            Map<String, Object> prod = productMap.get(m.getMallProductId());
            if (prod == null || prod.get("corpId") == null) {
                unauthorized.add(m.getId());
                continue;
            }
            Long corpId = Long.valueOf(prod.get("corpId").toString());
            if (!boundMerchantIds.contains(corpId)) {
                unauthorized.add(m.getId());
            }
        }
        if (!unauthorized.isEmpty()) {
            throw new IllegalArgumentException("无权操作以下商品（不属于绑定的销售商）: " + unauthorized);
        }
    }

    private Map<String, Object> emptyPage(int pageSize, int pageNum) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("records", Collections.emptyList());
        empty.put("total", 0L);
        empty.put("size", pageSize);
        empty.put("current", pageNum);
        return empty;
    }
}
