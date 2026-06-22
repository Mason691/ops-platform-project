package com.aip.ops.service;

import com.aip.ops.entity.OpsActivity;
import java.util.List;
import java.util.Map;

/**
 * 活动管理 Service — 统一活动 CRUD 和商品关联逻辑。
 */
public interface OpsActivityService {

    /**
     * 管理员分页查询活动列表（含关联商品数量）。
     * @return { records, total, size, current }
     */
    Map<String, Object> adminActivityPage(Long operatorId, int pageNum, int pageSize,
                                           String type, String category, Integer status);

    /**
     * 活动详情（含关联商品 + 直播间信息）。
     * @return { activity, products, liveRoom } 或 null
     */
    Map<String, Object> activityDetail(Long id);

    /**
     * 创建活动并保存商品关联。
     * @param productIds 关联商品映射 ID 列表
     * @return 新活动 ID
     */
    Long createActivity(OpsActivity activity, List<Long> productIds);

    /**
     * 更新活动并重新保存商品关联（先删后增）。
     */
    void updateActivity(Long id, OpsActivity activity, List<Long> productIds);

    /** 仅更新活动状态 */
    void updateStatus(Long id, Integer status);

    /** 删除活动及所有关联商品 */
    void deleteActivity(Long id);
}
