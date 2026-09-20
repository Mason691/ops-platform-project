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

    /** Aggregate stats for current filter — deduplicated by (receipt_no, category, model)
     *  so that multi-agent rows sharing the same order+product are counted once.
     *  Rows with NULL receipt_no use a per-row sentinel (COALESCE + id) to stay
     *  independent — unrelated orders must not merge under a shared NULL group. */
    @Select("<script>"
            + "SELECT COALESCE(SUM(t.sale_price),0) as totalSale, "
            + "COALESCE(SUM(t.commission_amount),0) as totalCommission "
            + "FROM ("
            + "SELECT COALESCE(receipt_no, CONCAT('__NULL__', id)) as receipt_no, "
            + "category, model, "
            + "MAX(sale_price) as sale_price, "
            + "SUM(commission_amount) as commission_amount "
            + "FROM ops_business_data WHERE delete_flag=0 "
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='receiptNo != null and receiptNo != \"\"'>AND receipt_no = #{receiptNo}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            +  "<if test='isSpecial != null and isSpecial != \"\"'>AND is_special = #{isSpecial}</if> "
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + "GROUP BY 1, category, model"
            + ") t"
            + "</script>")
    Map<String, BigDecimal> aggregateStats(@Param("company") String company,
                                           @Param("batchNo") String batchNo,
                                           @Param("agent") String agent,
                                           @Param("receiptNo") String receiptNo,
                                           @Param("category") String category,
                                           @Param("isSpecial") String isSpecial,
                                           @Param("corpIds") List<Long> corpIds);

    /** Per-category totals for current filter — same (receipt_no, category, model)
     *  dedup rule as aggregateStats so per-category sums reconcile with overall totals. */
    @Select("<script>"
            + "SELECT t.category as category, "
            + "COALESCE(SUM(t.sale_price),0) as totalSale, "
            + "COALESCE(SUM(t.commission_amount),0) as totalCommission "
            + "FROM ("
            + "SELECT COALESCE(receipt_no, CONCAT('__NULL__', id)) as receipt_no, "
            + "category, model, "
            + "MAX(sale_price) as sale_price, "
            + "SUM(commission_amount) as commission_amount "
            + "FROM ops_business_data WHERE delete_flag=0 "
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='receiptNo != null and receiptNo != \"\"'>AND receipt_no = #{receiptNo}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            +  "<if test='isSpecial != null and isSpecial != \"\"'>AND is_special = #{isSpecial}</if> "
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + "GROUP BY 1, category, model"
            + ") t GROUP BY t.category ORDER BY SUM(t.sale_price) DESC"
            + "</script>")
    List<Map<String, Object>> categoryStats(@Param("company") String company,
                                            @Param("batchNo") String batchNo,
                                            @Param("agent") String agent,
                                            @Param("receiptNo") String receiptNo,
                                            @Param("category") String category,
                                            @Param("isSpecial") String isSpecial,
                                            @Param("corpIds") List<Long> corpIds);

    /** Count only — delegates to COUNT(*) for speed. */
    @Select("<script>"
            + "SELECT COUNT(*) FROM ops_business_data WHERE delete_flag=0 "
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='receiptNo != null and receiptNo != \"\"'>AND receipt_no = #{receiptNo}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            +  "<if test='isSpecial != null and isSpecial != \"\"'>AND is_special = #{isSpecial}</if> "
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + "</script>")
    long countFiltered(@Param("company") String company,
                       @Param("batchNo") String batchNo,
                       @Param("agent") String agent,
                       @Param("receiptNo") String receiptNo,
                       @Param("category") String category,
                       @Param("isSpecial") String isSpecial,
                       @Param("corpIds") List<Long> corpIds);

    /** Find which receipt numbers from the given set already exist in DB (within corp scope). */
    @Select("<script>"
            + "SELECT DISTINCT receipt_no FROM ops_business_data WHERE delete_flag=0 "
            + "<if test='corpId != null'>AND corp_id = #{corpId}</if> "
            + "AND receipt_no IN <foreach collection='receiptNos' item='r' open='(' separator=',' close=')'>#{r}</foreach>"
            + "</script>")
    List<String> findExistingReceiptNos(@Param("receiptNos") List<String> receiptNos,
                                        @Param("corpId") Long corpId);

    /** Per-category × per-company totals for commission summary (品类提成汇总) —
     *  销售金额按（收据号+公司）去重：同一订单只取一条 sale_price 计一次，归属规则：
     *  含 户内美装销售(6) 则归属销售品类；否则按品类中文名排序取首品类；
     *  提成金额仍按每条明细全量汇总（提成按明细独立计算）。排除已上报集团行。 */
    @Select("<script>"
            + "SELECT r.category as category, r.company as company, "
            + "COALESCE(SUM(CASE WHEN r.rn = 1 THEN r.sale_price ELSE 0 END),0) as totalSale, "
            + "COALESCE(SUM(r.commission_amount),0) as totalCommission "
            + "FROM ("
            + "SELECT b.category, b.company, b.sale_price, b.commission_amount, "
            + "ROW_NUMBER() OVER (PARTITION BY b.grp, b.company ORDER BY b.salePri, b.catName, b.id) as rn "
            + "FROM ("
            + "SELECT id, category, company, sale_price, commission_amount, "
            + "COALESCE(receipt_no, CONCAT('__NULL__', id)) as grp, "
            + "CASE WHEN category = '6' THEN 0 ELSE 1 END as salePri, "
            + "CASE category WHEN '10' THEN '保险' WHEN '4' THEN '壁挂炉' WHEN '5' THEN '壁挂炉以旧换新销售' WHEN '2' THEN '抽油烟机' WHEN '7' THEN '定尺波纹管' WHEN '8' THEN '户内美装工程' WHEN '6' THEN '户内美装销售' WHEN '9' THEN '户内美装运维' WHEN '12' THEN '美装安全评估' WHEN '11' THEN '美装管道整改' WHEN 'qimi' THEN '气密性检测' WHEN '1' THEN '燃气灶' WHEN '3' THEN '热水器' ELSE category END as catName "
            + "FROM ops_business_data WHERE delete_flag=0 "
            + "AND calc_status != '已上报集团' "   // != 而非 <>：<script> XML 解析器中 <> 会破坏标签解析
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='receiptNo != null and receiptNo != \"\"'>AND receipt_no = #{receiptNo}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + ") b"
            + ") r GROUP BY r.category, r.company"
            + "</script>")
    List<Map<String, Object>> categoryCompanyStats(@Param("company") String company,
                                                   @Param("batchNo") String batchNo,
                                                   @Param("agent") String agent,
                                                   @Param("receiptNo") String receiptNo,
                                                   @Param("category") String category,
                                                   @Param("corpIds") List<Long> corpIds);

    /** 品类提成汇总（按月）— 与 categoryCompanyStats 相同的收据号去重+归属口径，附加销售月份维度。
     *  订单月份 = 归属首行（户内美装销售优先、否则品类中文名排序）的 sale_date 月份，
     *  销售/提成同归该月，保证各月合计与全部汇总对账一致。返回行含 saleMonth（YYYY-MM）。 */
    @Select("<script>"
            + "SELECT r.category as category, r.company as company, r.saleMonth as saleMonth, "
            + "COALESCE(SUM(CASE WHEN r.rn = 1 THEN r.sale_price ELSE 0 END),0) as totalSale, "
            + "COALESCE(SUM(r.commission_amount),0) as totalCommission "
            + "FROM ("
            + "SELECT b.category, b.company, b.sale_price, b.commission_amount, "
            + "ROW_NUMBER() OVER (PARTITION BY b.grp, b.company ORDER BY b.salePri, b.catName, b.id) as rn, "
            + "DATE_FORMAT(FIRST_VALUE(b.sale_date) OVER (PARTITION BY b.grp, b.company ORDER BY b.salePri, b.catName, b.id), '%Y-%m') as saleMonth "
            + "FROM ("
            + "SELECT id, category, company, sale_price, sale_date, commission_amount, "
            + "COALESCE(receipt_no, CONCAT('__NULL__', id)) as grp, "
            + "CASE WHEN category = '6' THEN 0 ELSE 1 END as salePri, "
            + "CASE category WHEN '10' THEN '保险' WHEN '4' THEN '壁挂炉' WHEN '5' THEN '壁挂炉以旧换新销售' WHEN '2' THEN '抽油烟机' WHEN '7' THEN '定尺波纹管' WHEN '8' THEN '户内美装工程' WHEN '6' THEN '户内美装销售' WHEN '9' THEN '户内美装运维' WHEN '12' THEN '美装安全评估' WHEN '11' THEN '美装管道整改' WHEN 'qimi' THEN '气密性检测' WHEN '1' THEN '燃气灶' WHEN '3' THEN '热水器' ELSE category END as catName "
            + "FROM ops_business_data WHERE delete_flag=0 "
            + "AND calc_status != '已上报集团' "
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='receiptNo != null and receiptNo != \"\"'>AND receipt_no = #{receiptNo}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + ") b"
            + ") r GROUP BY r.category, r.company, r.saleMonth"
            + "</script>")
    List<Map<String, Object>> categoryCompanyStatsByMonth(@Param("company") String company,
                                                          @Param("batchNo") String batchNo,
                                                          @Param("agent") String agent,
                                                          @Param("receiptNo") String receiptNo,
                                                          @Param("category") String category,
                                                          @Param("corpIds") List<Long> corpIds);
}
