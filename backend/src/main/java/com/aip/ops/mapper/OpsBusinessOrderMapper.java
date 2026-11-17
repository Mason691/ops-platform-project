package com.aip.ops.mapper;

import com.aip.ops.entity.OpsBusinessOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OpsBusinessOrderMapper extends BaseMapper<OpsBusinessOrder> {

    /** 子表状态变更后同步主表状态：已上报集团 > 未计算 > 已计算 > 经办人已核对。 */
    @Update("UPDATE ops_business_order m SET m.calc_status = ("
            + "  SELECT CASE "
            + "    WHEN COUNT(*) = 0 THEN m.calc_status "
            + "    WHEN SUM(i.calc_status = '已上报集团') > 0 THEN '已上报集团' "
            + "    WHEN SUM(i.calc_status = '未计算') > 0 THEN '未计算' "
            + "    WHEN SUM(i.calc_status = '已计算') > 0 THEN '已计算' "
            + "    WHEN SUM(i.calc_status = '经办人已核对') > 0 THEN '经办人已核对' "
            + "    ELSE MAX(i.calc_status) END "
            + "  FROM ops_business_order_item i "
            + "  WHERE i.order_id = m.id AND i.delete_flag = 0"
            + "), m.modify_time = NOW() "
            + "WHERE m.id = #{orderId}")
    int syncStatusFromItems(@Param("orderId") Long orderId);

    /** 订单内全部子表行（财务核对/退货/提成重算用）。 */
    @Select("SELECT * FROM ops_business_order_item "
            + "WHERE order_id = #{orderId} AND delete_flag = 0 ORDER BY id")
    List<com.aip.ops.entity.OpsBusinessData> selectItems(@Param("orderId") Long orderId);
}
