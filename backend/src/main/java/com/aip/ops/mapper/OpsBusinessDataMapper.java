package com.aip.ops.mapper;

import com.aip.ops.entity.OpsBusinessData;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface OpsBusinessDataMapper extends BaseMapper<OpsBusinessData> {

    /** Aggregate stats for current filter — single query, no full table scan. */
    @Select("<script>"
            + "SELECT COALESCE(SUM(sale_price),0) as totalSale, "
            + "COALESCE(SUM(commission_amount),0) as totalCommission "
            + "FROM ops_business_data WHERE delete_flag=0 "
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + "</script>")
    Map<String, BigDecimal> aggregateStats(@Param("company") String company,
                                           @Param("batchNo") String batchNo,
                                           @Param("agent") String agent,
                                           @Param("corpIds") List<Long> corpIds);

    /** Count only — delegates to COUNT(*) for speed. */
    @Select("<script>"
            + "SELECT COUNT(*) FROM ops_business_data WHERE delete_flag=0 "
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + "</script>")
    long countFiltered(@Param("company") String company,
                       @Param("batchNo") String batchNo,
                       @Param("agent") String agent,
                       @Param("corpIds") List<Long> corpIds);
}
