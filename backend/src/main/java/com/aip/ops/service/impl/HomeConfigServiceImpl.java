package com.aip.ops.service.impl;

import com.aip.ops.entity.*;
import com.aip.ops.mapper.*;
import com.aip.ops.service.HomeConfigService;
import com.aip.ops.util.ImageUrlUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 首页配置管理 Service 实现。
 *
 * 核心逻辑：
 * 1. 配置 CRUD 按 operator_id 隔离
 * 2. 可用视频从 ops_activity (type='activity', video_url 非空) 获取
 * 3. 可推荐商品从 ops_product JOIN mall_product 获取，仅限已绑定销售商的上架商品
 * 4. 公开首页数据组装：按 config_type 查询启用配置，富化关联数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeConfigServiceImpl implements HomeConfigService {

    private final HomeConfigMapper homeConfigMapper;
    private final GsActivityMapper activityMapper;
    private final GsProductMapper productMapper;
    private final OperatorMerchantMapper omMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantMiniappConfigMapper miniappConfigMapper;
    private final ImageUrlUtil imageUrlUtil;

    // ═══ CRUD ═══

    @Override
    public Map<String, Object> pageByType(Long operatorId, String configType, int pageNum, int pageSize) {
        LambdaQueryWrapper<OpsHomeConfig> wrapper = new LambdaQueryWrapper<OpsHomeConfig>()
                .eq(OpsHomeConfig::getOperatorId, operatorId);
        if (configType != null && !configType.isEmpty()) {
            wrapper.eq(OpsHomeConfig::getConfigType, configType);
        }
        wrapper.orderByAsc(OpsHomeConfig::getSort)
               .orderByDesc(OpsHomeConfig::getId);

        Page<OpsHomeConfig> page = homeConfigMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);

        // 规范化图片 URL
        for (OpsHomeConfig record : page.getRecords()) {
            if (record.getImageUrl() != null && !record.getImageUrl().isBlank()) {
                record.setImageUrl(imageUrlUtil.normalizeImage(record.getImageUrl()));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        result.put("size", pageSize);
        result.put("current", pageNum);
        return result;
    }

    @Override
    @Transactional
    public Long create(OpsHomeConfig config) {
        // 确保 ID 由数据库自增
        config.setId(null);
        config.setCreateTime(null);
        config.setUpdateTime(null);
        if (config.getSort() == null) config.setSort(0);
        if (config.getStatus() == null) config.setStatus(1);
        homeConfigMapper.insert(config);
        log.info("首页配置已创建: id={}, type={}, operatorId={}", config.getId(), config.getConfigType(), config.getOperatorId());
        return config.getId();
    }

    @Override
    @Transactional
    public void update(Long id, OpsHomeConfig config) {
        config.setId(id);
        config.setOperatorId(null);  // 不允许修改归属运营商
        config.setCreateTime(null);
        config.setUpdateTime(null);
        homeConfigMapper.updateById(config);
        log.info("首页配置已更新: id={}", id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        homeConfigMapper.deleteById(id);
        log.info("首页配置已删除: id={}", id);
    }

    @Override
    @Transactional
    public void batchUpdateSort(List<Map<String, Object>> sortItems) {
        for (Map<String, Object> item : sortItems) {
            Long id = Long.valueOf(item.get("id").toString());
            Integer sort = Integer.valueOf(item.get("sort").toString());
            homeConfigMapper.update(null, new LambdaUpdateWrapper<OpsHomeConfig>()
                    .eq(OpsHomeConfig::getId, id)
                    .set(OpsHomeConfig::getSort, sort));
        }
        log.info("批量更新排序完成: count={}", sortItems.size());
    }

    // ═══ 数据源查询 ═══

    @Override
    public List<Map<String, Object>> getAvailableVideos(Long operatorId) {
        List<GsActivity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<GsActivity>()
                        .eq(GsActivity::getCorpId, operatorId)
                        .eq(GsActivity::getType, "activity")
                        .isNotNull(GsActivity::getVideoUrl)
                        .ne(GsActivity::getVideoUrl, "")
                        .orderByDesc(GsActivity::getId));

        List<Map<String, Object>> result = new ArrayList<>();
        for (GsActivity a : activities) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("title", a.getTitle());
            m.put("videoUrl", imageUrlUtil.normalizeImage(a.getVideoUrl()));
            m.put("videoCoverUrl", imageUrlUtil.normalizeImage(a.getVideoCoverUrl()));
            // 回退封面
            String cover = imageUrlUtil.resolveActivityCover(a.getCoverUrl(), a.getCoverImage());
            m.put("coverImage", cover);
            m.put("startTime", a.getStartTime());
            m.put("endTime", a.getEndTime());
            result.add(m);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getRecommendableProducts(Long operatorId) {
        // 1. 获取绑定的销售商 ID
        Set<Long> boundMerchantIds = getBoundMerchantIds(operatorId);
        if (boundMerchantIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 查询上架商品（shelfStatus=1, mp.status=1 由 Mapper 方法过滤）
        List<Long> corpIds = new ArrayList<>(boundMerchantIds);
        // 查询最多 500 条，足够选择器使用
        List<Map<String, Object>> records = productMapper.selectProductList(
                operatorId, corpIds, 0, 500, 1, null, null);

        // 3. 批量加载销售商名称
        Map<Long, String> merchantNameMap = loadMerchantNames(boundMerchantIds);

        // 4. 简化映射
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> rec : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mappingId", rec.get("mappingId"));
            m.put("productId", rec.get("productId"));
            m.put("name", rec.get("name"));
            m.put("coverImage", imageUrlUtil.normalizeProductImage((String) rec.get("coverImage")));
            m.put("pointsPrice", rec.get("pointsPrice"));
            Object spObj = rec.get("salePrice");
            m.put("salePrice", spObj != null ? spObj : 0);
            // 积分兑换配置
            Object ptsObj = rec.get("points");
            Object excObj = rec.get("exchangeAmount");
            int pts = ptsObj != null ? Integer.parseInt(ptsObj.toString()) : 0;
            BigDecimal exc = excObj != null ? new BigDecimal(excObj.toString()) : BigDecimal.ZERO;
            m.put("points", pts);
            m.put("exchangeAmount", exc);

            // 销售商信息
            Object midObj = rec.get("merchantId");
            if (midObj != null) {
                Long mid = Long.valueOf(midObj.toString());
                m.put("merchantId", mid);
                m.put("merchantName", merchantNameMap.getOrDefault(mid, "未知销售商"));
            }
            result.add(m);
        }
        return result;
    }

    // ═══ 公开首页数据 ═══

    @Override
    public Map<String, Object> getHomeData(Long operatorId) {
        // 1. 查询所有启用配置
        List<OpsHomeConfig> allConfigs = homeConfigMapper.selectList(
                new LambdaQueryWrapper<OpsHomeConfig>()
                        .eq(OpsHomeConfig::getOperatorId, operatorId)
                        .eq(OpsHomeConfig::getStatus, 1)
                        .orderByAsc(OpsHomeConfig::getSort)
                        .orderByDesc(OpsHomeConfig::getId));

        // 2. 按类型分组
        List<OpsHomeConfig> bannerConfigs = new ArrayList<>();
        List<OpsHomeConfig> videoConfigs = new ArrayList<>();
        List<OpsHomeConfig> recommendConfigs = new ArrayList<>();
        for (OpsHomeConfig c : allConfigs) {
            switch (c.getConfigType()) {
                case "banner": bannerConfigs.add(c); break;
                case "video": videoConfigs.add(c); break;
                case "recommend": recommendConfigs.add(c); break;
            }
        }

        // 3. 组装 banners
        List<Map<String, Object>> banners = new ArrayList<>();
        for (OpsHomeConfig c : bannerConfigs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("title", c.getTitle());
            m.put("imageUrl", imageUrlUtil.normalizeImage(c.getImageUrl()));
            m.put("jumpPath", c.getJumpPath());
            m.put("sort", c.getSort());
            banners.add(m);
        }

        // 4. 组装 videos — 预加载活动信息
        Set<Long> activityIds = videoConfigs.stream()
                .map(OpsHomeConfig::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, GsActivity> activityMap = new HashMap<>();
        if (!activityIds.isEmpty()) {
            List<GsActivity> activities = activityMapper.selectBatchIds(activityIds);
            for (GsActivity a : activities) {
                activityMap.put(a.getId(), a);
            }
        }

        List<Map<String, Object>> videos = new ArrayList<>();
        for (OpsHomeConfig c : videoConfigs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("sort", c.getSort());
            m.put("targetId", c.getTargetId());
            GsActivity act = c.getTargetId() != null ? activityMap.get(c.getTargetId()) : null;
            if (act != null) {
                m.put("title", act.getTitle());
                m.put("videoUrl", imageUrlUtil.normalizeImage(act.getVideoUrl()));
                m.put("videoCoverUrl", imageUrlUtil.normalizeImage(act.getVideoCoverUrl()));
                String cover = imageUrlUtil.resolveActivityCover(act.getCoverUrl(), act.getCoverImage());
                m.put("coverImage", cover);
            } else {
                m.put("title", c.getTitle() != null ? c.getTitle() : "精彩视频");
                m.put("videoUrl", null);
                m.put("videoCoverUrl", null);
                m.put("coverImage", null);
            }
            videos.add(m);
        }

        // 5. 组装 recommends — 预加载商品信息和小程序配置
        Set<Long> mappingIds = recommendConfigs.stream()
                .map(OpsHomeConfig::getTargetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Map<String, Object>> productMap = new HashMap<>();
        Map<Long, Map<String, Object>> miniappConfigByMapping = new HashMap<>();
        if (!mappingIds.isEmpty()) {
            for (Long mappingId : mappingIds) {
                Map<String, Object> prod = productMapper.selectProductByMappingId(mappingId);
                if (prod != null) {
                    productMap.put(mappingId, prod);
                    // Load miniapp config for this product's merchant
                    Object midObj = prod.get("merchantId");
                    if (midObj != null) {
                        Long mid = Long.valueOf(midObj.toString());
                        Map<String, Object> cfg = loadMiniappConfigForMerchant(mid);
                        if (cfg != null) {
                            Object pid = prod.get("productId");
                            if (pid != null && cfg.containsKey("path")) {
                                cfg = new LinkedHashMap<>(cfg);
                                cfg.put("path", cfg.get("path").toString().replace("{productId}", pid.toString()));
                            }
                            miniappConfigByMapping.put(mappingId, cfg);
                        }
                    }
                }
            }
        }

        List<Map<String, Object>> recommends = new ArrayList<>();
        for (OpsHomeConfig c : recommendConfigs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("targetId", c.getTargetId());
            m.put("sort", c.getSort());
            Map<String, Object> prod = c.getTargetId() != null ? productMap.get(c.getTargetId()) : null;
            if (prod != null) {
                m.put("name", prod.get("name"));
                m.put("coverImage", imageUrlUtil.normalizeProductImage((String) prod.get("coverImage")));
                m.put("pointsPrice", prod.get("pointsPrice"));
                Object spObj = prod.get("salePrice");
                BigDecimal sp = spObj != null ? new BigDecimal(spObj.toString()) : BigDecimal.ZERO;
                m.put("salePrice", sp);
                // 积分兑换价格
                Object ptsObj = prod.get("points");
                Object excObj = prod.get("exchangeAmount");
                int pts = ptsObj != null ? Integer.parseInt(ptsObj.toString()) : 0;
                BigDecimal exc = excObj != null ? new BigDecimal(excObj.toString()) : BigDecimal.ZERO;
                m.put("points", pts);
                BigDecimal cash = pts > 0 ? sp.subtract(exc) : sp;
                m.put("cashPrice", cash.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : cash);
                // 小程序跳转配置
                if (miniappConfigByMapping.containsKey(c.getTargetId())) {
                    m.put("miniappConfig", miniappConfigByMapping.get(c.getTargetId()));
                }
            } else {
                m.put("name", c.getTitle() != null ? c.getTitle() : "推荐商品");
                m.put("coverImage", null);
                m.put("pointsPrice", 0);
                m.put("salePrice", 0);
                m.put("points", 0);
                m.put("cashPrice", 0);
            }
            recommends.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("banners", banners);
        result.put("videos", videos);
        result.put("recommends", recommends);
        return result;
    }

    // ═══ 私有辅助方法 ═══

    private Set<Long> getBoundMerchantIds(Long operatorId) {
        return omMapper.selectList(new LambdaQueryWrapper<OperatorMerchant>()
                .eq(OperatorMerchant::getOperatorId, operatorId)
                .eq(OperatorMerchant::getStatus, 1))
                .stream().map(OperatorMerchant::getMerchantId).collect(Collectors.toSet());
    }

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

    private Map<String, Object> loadMiniappConfigForMerchant(Long merchantId) {
        List<MerchantMiniappConfig> configs = miniappConfigMapper.selectList(
                new LambdaQueryWrapper<MerchantMiniappConfig>()
                        .eq(MerchantMiniappConfig::getMerchantId, merchantId));
        if (configs.isEmpty()) return null;
        MerchantMiniappConfig cfg = configs.get(0);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("appId", cfg.getAppId());
        entry.put("path", cfg.getPathTemplate() != null ? cfg.getPathTemplate() : "");
        entry.put("envVersion", cfg.getEnvVersion() != null ? cfg.getEnvVersion() : "release");
        return entry;
    }
}
