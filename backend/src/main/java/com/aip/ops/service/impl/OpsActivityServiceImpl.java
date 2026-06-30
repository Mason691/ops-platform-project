package com.aip.ops.service.impl;

import com.aip.ops.client.ProductBatchClient;
import com.aip.ops.entity.OpsActivity;
import com.aip.ops.entity.OpsActivityProduct;
import com.aip.ops.entity.OpsLiveRoom;
import com.aip.ops.mapper.OpsActivityMapper;
import com.aip.ops.mapper.OpsActivityProductMapper;
import com.aip.ops.mapper.OpsLiveRoomMapper;
import com.aip.ops.service.OpsActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 活动管理 Service 实现。
 *
 * 核心变更（ADR-005 合规）：
 * - 活动关联商品不再通过 OpsActivityProductMapper JOIN mall_db.mall_product 跨库查询
 * - 改为通过 ProductBatchClient 批量获取商品名称/封面/价格
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsActivityServiceImpl implements OpsActivityService {

    private final OpsActivityMapper activityMapper;
    private final OpsActivityProductMapper activityProductMapper;
    private final OpsLiveRoomMapper liveRoomMapper;
    private final ProductBatchClient productBatchClient;

    @Override
    public Map<String, Object> adminActivityPage(Long operatorId, int pageNum, int pageSize,
                                                  String type, String category, Integer status) {
        int offset = (pageNum - 1) * pageSize;
        List<OpsActivity> records = activityMapper.selectActivityPage(
                offset, pageSize, type, category, status);
        long total = activityMapper.countActivityPage(type, category, status);

        // 为 live_reserve 类型活动批量填充关联的直播室信息
        List<Long> liveRoomIds = records.stream()
                .filter(a -> "live_reserve".equals(a.getType()) && a.getLiveRoomId() != null)
                .map(OpsActivity::getLiveRoomId)
                .distinct()
                .collect(Collectors.toList());
        if (!liveRoomIds.isEmpty()) {
            List<OpsLiveRoom> liveRooms = liveRoomMapper.selectBatchIds(liveRoomIds);
            Map<Long, OpsLiveRoom> lrMap = liveRooms.stream()
                    .collect(Collectors.toMap(OpsLiveRoom::getId, lr -> lr, (a, b) -> a));
            for (OpsActivity a : records) {
                if (a.getLiveRoomId() != null) {
                    a.setLiveRoomInfo(lrMap.get(a.getLiveRoomId()));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("size", pageSize);
        result.put("current", pageNum);
        return result;
    }

    @Override
    public Map<String, Object> activityDetail(Long id) {
        OpsActivity a = activityMapper.selectById(id);
        if (a == null) return null;

        // 查询活动关联商品（仅本地字段 + 小程序配置，不含 mall_product JOIN）
        List<Map<String, Object>> rawProducts = activityProductMapper.selectProductsByActivityId(id);

        // 批量获取商品详情（名称、封面、价格）
        List<Map<String, Object>> enrichedProducts = enrichActivityProducts(rawProducts);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity", a);
        result.put("products", enrichedProducts);
        if (a.getLiveRoomId() != null) {
            OpsLiveRoom lr = liveRoomMapper.selectById(a.getLiveRoomId());
            if (lr != null) {
                result.put("liveRoom", lr);

                // 计算前端展示用的 liveStatus
                Map<String, Object> liveStatus = new LinkedHashMap<>();
                String displayStatus;
                if (lr.getStatus() != null && lr.getStatus() == 1) {
                    displayStatus = "live";
                } else if (lr.getStatus() != null && lr.getStatus() == 2) {
                    displayStatus = "ended";
                } else {
                    // status=0（预告），但如果已过预计开播时间，容错视为直播中
                    if (lr.getStartTime() != null && LocalDateTime.now().isAfter(lr.getStartTime())) {
                        displayStatus = "live";
                    } else {
                        displayStatus = "upcoming";
                    }
                }
                liveStatus.put("displayStatus", displayStatus);
                liveStatus.put("viewerCount", lr.getViewerCount() != null ? lr.getViewerCount() : 0);
                liveStatus.put("hasReplay", lr.getReplayUrl() != null && !lr.getReplayUrl().isBlank());
                if ("upcoming".equals(displayStatus) && lr.getStartTime() != null) {
                    long diffMs = Duration.between(LocalDateTime.now(), lr.getStartTime()).toMillis();
                    liveStatus.put("countdownMs", Math.max(0, diffMs));
                }
                result.put("liveStatus", liveStatus);
            }
        }
        return result;
    }

    @Override
    @Transactional
    public Long createActivity(OpsActivity activity, List<Long> productIds) {
        activityMapper.insert(activity);
        saveActivityProducts(activity.getId(), productIds);
        log.info("活动创建成功: id={}, title={}, type={}, productCount={}",
                activity.getId(), activity.getTitle(), activity.getType(),
                productIds != null ? productIds.size() : 0);
        return activity.getId();
    }

    @Override
    @Transactional
    public void updateActivity(Long id, OpsActivity activity, List<Long> productIds) {
        activity.setId(id);
        activityMapper.updateById(activity);
        saveActivityProducts(id, productIds);
        log.info("活动更新成功: id={}, title={}, productCount={}",
                id, activity.getTitle(),
                productIds != null ? productIds.size() : 0);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        OpsActivity a = new OpsActivity();
        a.setId(id);
        a.setStatus(status);
        activityMapper.updateById(a);
    }

    @Override
    @Transactional
    public void deleteActivity(Long id) {
        activityMapper.deleteById(id);
        activityProductMapper.deleteByActivityId(id);
        log.info("活动已删除: id={}", id);
    }

    // ═══ 私有方法 ═══

    /**
     * 批量获取商品详情（名称、封面、价格），替代跨库 JOIN mall_db.mall_product。
     */
    private List<Map<String, Object>> enrichActivityProducts(List<Map<String, Object>> rawProducts) {
        if (rawProducts == null || rawProducts.isEmpty()) return Collections.emptyList();

        // 收集 mall_product_id
        List<Long> mallProductIds = rawProducts.stream()
                .map(p -> {
                    Object mpId = p.get("mall_product_id");
                    return mpId != null ? Long.valueOf(mpId.toString()) : null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Map<String, Object>> productDetails = productBatchClient.batchQuery(mallProductIds);

        // 组装结果
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> raw : rawProducts) {
            Map<String, Object> enriched = new LinkedHashMap<>(raw);
            Object mpIdObj = raw.get("mall_product_id");
            if (mpIdObj != null) {
                Long mpId = Long.valueOf(mpIdObj.toString());
                Map<String, Object> detail = productDetails.get(mpId);
                if (detail != null) {
                    enriched.put("name", detail.get("name"));
                    enriched.put("cover_image", detail.get("coverImage"));
                    enriched.put("points_price", detail.get("pointsPrice"));
                    // cash_price = salePrice - points（简化计算）
                    Object salePrice = detail.get("salePrice");
                    Object pointsPrice = detail.get("pointsPrice");
                    enriched.put("cash_price", salePrice != null ? salePrice : 0);
                }
            }
            result.add(enriched);
        }
        return result;
    }

    /**
     * 保存活动-商品关联（先删后增，使用列表索引作为排序值）。
     */
    private void saveActivityProducts(Long activityId, List<Long> productIds) {
        activityProductMapper.deleteByActivityId(activityId);
        if (productIds == null || productIds.isEmpty()) return;
        for (int i = 0; i < productIds.size(); i++) {
            OpsActivityProduct ap = new OpsActivityProduct();
            ap.setActivityId(activityId);
            ap.setProductId(productIds.get(i));
            ap.setSort(i);
            activityProductMapper.insert(ap);
        }
    }
}
