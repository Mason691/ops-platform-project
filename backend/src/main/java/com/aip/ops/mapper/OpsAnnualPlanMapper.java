package com.aip.ops.mapper;

import com.aip.ops.entity.OpsAnnualPlan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface OpsAnnualPlanMapper extends BaseMapper<OpsAnnualPlan> {

    /** 年度总金额 — 所有已审核计划的金额汇总 */
    @Select("<script>"
            + "SELECT COALESCE(SUM(plan_amount), 0) FROM ops_annual_plan "
            + "WHERE delete_flag = 0 AND year = #{year} AND status = 1 "
            + "<if test='orgIds != null and orgIds.size() > 0'>"
            + "AND org_id IN <foreach collection='orgIds' item='oid' open='(' separator=',' close=')'>#{oid}</foreach>"
            + "</if>"
            + "</script>")
    BigDecimal sumTotalByYear(@Param("year") Integer year, @Param("orgIds") List<Long> orgIds);

    /** 按品类汇总 — 已审核计划 */
    @Select("<script>"
            + "SELECT category, COALESCE(SUM(plan_amount), 0) AS total "
            + "FROM ops_annual_plan "
            + "WHERE delete_flag = 0 AND year = #{year} AND status = 1 "
            + "<if test='orgIds != null and orgIds.size() > 0'>"
            + "AND org_id IN <foreach collection='orgIds' item='oid' open='(' separator=',' close=')'>#{oid}</foreach>"
            + "</if>"
            + "GROUP BY category ORDER BY category"
            + "</script>")
    List<Map<String, Object>> sumByCategory(@Param("year") Integer year, @Param("orgIds") List<Long> orgIds);

    /** 按组织汇总 — 已审核计划 */
    @Select("<script>"
            + "SELECT org_id, COALESCE(SUM(plan_amount), 0) AS total "
            + "FROM ops_annual_plan "
            + "WHERE delete_flag = 0 AND year = #{year} AND status = 1 "
            + "<if test='orgIds != null and orgIds.size() > 0'>"
            + "AND org_id IN <foreach collection='orgIds' item='oid' open='(' separator=',' close=')'>#{oid}</foreach>"
            + "</if>"
            + "GROUP BY org_id ORDER BY org_id"
            + "</script>")
    List<Map<String, Object>> sumByOrg(@Param("year") Integer year, @Param("orgIds") List<Long> orgIds);

    /** 子公司各品类明细 — 已审核计划 */
    @Select("<script>"
            + "SELECT p.*, c.short_name AS org_name "
            + "FROM ops_annual_plan p "
            + "LEFT JOIN ai_platform.sys_corp c ON c.id = p.org_id AND c.delete_flag = 0 "
            + "WHERE p.delete_flag = 0 AND p.year = #{year} AND p.status = 1 "
            + "<if test='orgIds != null and orgIds.size() > 0'>"
            + "AND p.org_id IN <foreach collection='orgIds' item='oid' open='(' separator=',' close=')'>#{oid}</foreach>"
            + "</if>"
            + "ORDER BY p.org_id, p.category"
            + "</script>")
    List<Map<String, Object>> orgDetail(@Param("year") Integer year, @Param("orgIds") List<Long> orgIds);
}
