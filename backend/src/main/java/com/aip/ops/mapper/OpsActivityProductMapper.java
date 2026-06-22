package com.aip.ops.mapper;

import com.aip.ops.entity.OpsActivityProduct;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface OpsActivityProductMapper extends BaseMapper<OpsActivityProduct> {

    /**
     * 查询活动关联的商品映射关系（仅查本地 ops 表，不再 JOIN mall_db.mall_product）。
     * mall_product 的商品名称/封面/价格通过 ProductBatchClient 批量获取。
     * biz_operator_merchant 和 biz_merchant_miniapp_config 是本地表，保留 JOIN。
     */
    @Select("""
        SELECT ap.product_id, ap.sort, ap.create_time,
               op.mall_product_id, op.operator_id,
               mmc.app_id AS miniapp_app_id, mmc.path_template AS miniapp_path
        FROM ops_activity_product ap
        JOIN ops_product op ON op.id = ap.product_id
        LEFT JOIN biz_operator_merchant bom ON bom.operator_id = op.operator_id AND bom.status = 1
        LEFT JOIN biz_merchant_miniapp_config mmc ON mmc.merchant_id = bom.merchant_id AND mmc.app_id IS NOT NULL
        WHERE ap.activity_id = #{activityId}
        ORDER BY ap.sort ASC, ap.create_time ASC
    """)
    List<Map<String, Object>> selectProductsByActivityId(@Param("activityId") Long activityId);

    /** 删除活动的所有商品关联 */
    @Delete("DELETE FROM ops_activity_product WHERE activity_id = #{activityId}")
    int deleteByActivityId(@Param("activityId") Long activityId);
}
