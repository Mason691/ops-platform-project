package com.aip.ops.service;

import com.aip.ops.entity.OpsHomeConfig;

import java.util.List;
import java.util.Map;

/**
 * 首页配置管理 Service。
 */
public interface HomeConfigService {

    /** 分页查询首页配置（按类型 + operatorId） */
    Map<String, Object> pageByType(Long operatorId, String configType, int pageNum, int pageSize);

    /** 创建配置 */
    Long create(OpsHomeConfig config);

    /** 更新配置（部分更新，null 字段不覆盖） */
    void update(Long id, OpsHomeConfig config);

    /** 删除配置 */
    void delete(Long id);

    /** 批量更新排序：items 为 [{id, sort}, ...] */
    void batchUpdateSort(List<Map<String, Object>> sortItems);

    /** 获取 operator 下可用活动视频（type='activity' 且 video_url 非空） */
    List<Map<String, Object>> getAvailableVideos(Long operatorId);

    /** 获取 operator 绑定销售商的所有上架商品 */
    List<Map<String, Object>> getRecommendableProducts(Long operatorId);

    /** 【公开】组装首页数据：按时序返回 banners / videos / recommends */
    Map<String, Object> getHomeData(Long operatorId);
}
