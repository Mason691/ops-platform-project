package com.aip.ops.mapper;

import com.aip.ops.entity.OpsActivityProduct;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface OpsActivityProductMapper extends BaseMapper<OpsActivityProduct> {

    /** 查询活动关联的商品简要信息（通过 ops_product JOIN mall_product） */
    @Select("""
        SELECT ap.product_id, mp.name, mp.cover_image, mp.points_price, mp.cash_price,
               op.operator_id, mmc.app_id AS miniapp_app_id, mmc.path AS miniapp_path
        FROM ops_activity_product ap
        JOIN ops_product op ON op.id = ap.product_id
        JOIN mall_db.mall_product mp ON mp.id = op.mall_product_id
        LEFT JOIN biz_merchant_miniapp_config mmc ON mmc.merchant_id = op.operator_id AND mmc.app_id IS NOT NULL
        WHERE ap.activity_id = #{activityId}
        ORDER BY ap.create_time ASC
    """)
    List<Map<String, Object>> selectProductsByActivityId(@Param("activityId") Long activityId);

    /** 删除活动的所有商品关联 */
    @Delete("DELETE FROM ops_activity_product WHERE activity_id = #{activityId}")
    int deleteByActivityId(@Param("activityId") Long activityId);
}
