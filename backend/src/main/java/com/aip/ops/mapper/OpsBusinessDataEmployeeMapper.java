package com.aip.ops.mapper;

import com.aip.ops.entity.OpsBusinessData;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Employee-scoped business data queries.
 * All queries filter by agent_phone (current user's phone) + calc_status='已计算'.
 */
@Mapper
public interface OpsBusinessDataEmployeeMapper extends BaseMapper<OpsBusinessData> {

    /** List distinct months (YYYY-MM) that have data for this employee. */
    @Select("SELECT DISTINCT SUBSTRING(sale_date, 1, 7) as month FROM ops_business_data "
            + "WHERE agent_phone = #{agentPhone} AND calc_status = '已计算' AND delete_flag = 0 "
            + "ORDER BY month DESC")
    List<String> selectAvailableMonths(@Param("agentPhone") String agentPhone);

    /** Group by category within a month — sum sale, commission, count orders. */
    @Select("<script>"
            + "SELECT category, COALESCE(SUM(sale_price),0) as totalSale, "
            + "COALESCE(SUM(commission_amount),0) as totalCommission, COUNT(*) as orderCount "
            + "FROM ops_business_data WHERE agent_phone = #{agentPhone} "
            + "AND calc_status = '已计算' AND delete_flag = 0 "
            + "<if test='month != null and month != \"\"'>AND SUBSTRING(sale_date, 1, 7) = #{month}</if> "
            + "GROUP BY category ORDER BY totalSale DESC"
            + "</script>")
    List<Map<String, Object>> selectCategorySummary(@Param("agentPhone") String agentPhone,
                                                     @Param("month") String month);

    /** Individual records for a given month + category. */
    @Select("<script>"
            + "SELECT brand, model, sale_price as salePrice, commission_amount as commissionAmount, "
            + "sale_date as saleDate, customer "
            + "FROM ops_business_data WHERE agent_phone = #{agentPhone} "
            + "AND calc_status = '已计算' AND delete_flag = 0 "
            + "<if test='month != null and month != \"\"'>AND SUBSTRING(sale_date, 1, 7) = #{month}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            + "ORDER BY sale_date DESC"
            + "</script>")
    List<Map<String, Object>> selectDetailRecords(@Param("agentPhone") String agentPhone,
                                                   @Param("month") String month,
                                                   @Param("category") String category);

    /** Aggregate totals for a single month (no category breakdown). */
    @Select("SELECT COALESCE(SUM(sale_price),0) as totalSale, "
            + "COALESCE(SUM(commission_amount),0) as totalCommission, COUNT(*) as orderCount "
            + "FROM ops_business_data WHERE agent_phone = #{agentPhone} "
            + "AND calc_status = '已计算' AND delete_flag = 0 "
            + "AND SUBSTRING(sale_date, 1, 7) = #{month}")
    Map<String, Object> selectMonthlyStats(@Param("agentPhone") String agentPhone,
                                            @Param("month") String month);
}
