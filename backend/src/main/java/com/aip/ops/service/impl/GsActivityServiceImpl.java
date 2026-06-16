package com.aip.ops.service.impl;

import com.aip.ops.entity.GsActivity;
import com.aip.ops.entity.OpsActivityProduct;
import com.aip.ops.entity.OpsLiveRoom;
import com.aip.ops.mapper.GsActivityMapper;
import com.aip.ops.mapper.OpsActivityProductMapper;
import com.aip.ops.mapper.OpsLiveRoomMapper;
import com.aip.ops.service.GsActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 活动管理 Service 实现。
 *
 * 统一了原本散落在 PlatformController 中的活动 CRUD 和商品关联逻辑，
 * 供管理员接口（AdminController）和公开接口（PlatformController）共用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GsActivityServiceImpl implements GsActivityService {

    private final GsActivityMapper activityMapper;
    private final OpsActivityProductMapper activityProductMapper;
    private final OpsLiveRoomMapper liveRoomMapper;

    @Override
    public Map<String, Object> adminActivityPage(Long operatorId, int pageNum, int pageSize,
                                                  String type, String category, Integer status) {
        int offset = (pageNum - 1) * pageSize;
        List<GsActivity> records = activityMapper.selectActivityPage(
                offset, pageSize, type, category, status);
        long total = activityMapper.countActivityPage(type, category, status);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("size", pageSize);
        result.put("current", pageNum);
        return result;
    }

    @Override
    public Map<String, Object> activityDetail(Long id) {
        GsActivity a = activityMapper.selectById(id);
        if (a == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activity", a);
        result.put("products", activityProductMapper.selectProductsByActivityId(id));
        if (a.getLiveRoomId() != null) {
            OpsLiveRoom lr = liveRoomMapper.selectById(a.getLiveRoomId());
            result.put("liveRoom", lr);
        }
        return result;
    }

    @Override
    @Transactional
    public Long createActivity(GsActivity activity, List<Long> productIds) {
        activityMapper.insert(activity);
        saveActivityProducts(activity.getId(), productIds);
        log.info("活动创建成功: id={}, title={}, type={}, productCount={}",
                activity.getId(), activity.getTitle(), activity.getType(),
                productIds != null ? productIds.size() : 0);
        return activity.getId();
    }

    @Override
    @Transactional
    public void updateActivity(Long id, GsActivity activity, List<Long> productIds) {
        activity.setId(id);
        activityMapper.updateById(activity);
        saveActivityProducts(id, productIds);
        log.info("活动更新成功: id={}, title={}, productCount={}",
                id, activity.getTitle(),
                productIds != null ? productIds.size() : 0);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        GsActivity a = new GsActivity();
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
