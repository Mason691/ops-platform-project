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

    /** Aggregate stats for current filter — positive rows deduplicated by (order_no,
     *  business_group_no, receipt_no, category, brand, model) so multi-agent rows sharing the
     *  same order+product are counted once; return (negative) rows get a per-return-bill group
     *  key and act as independent deductions (never merged into positive dedup). */
    @Select("<script>"
            + "SELECT COALESCE(SUM(t.receipt_amount),0) as totalSale, "
            + "COALESCE(SUM(t.commission_amount),0) as totalCommission "
            + "FROM ("
            + "SELECT CASE WHEN sale_quantity < 0 "
            + "  THEN CONCAT('__RET__', COALESCE(source_return_id, 0), '_', COALESCE(order_id, id)) "
            + "  ELSE COALESCE(NULLIF(order_no, ''), NULLIF(business_group_no, ''), NULLIF(receipt_no, ''), CONCAT('__NULL__', id)) END as receipt_no, "
            + "category, brand, model, "
            + "MAX(receipt_amount) as receipt_amount, "
            + "SUM(commission_amount) as commission_amount "
            + "FROM ops_business_order_item WHERE delete_flag=0 "
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='receiptNo != null and receiptNo != \"\"'>AND receipt_no = #{receiptNo}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            +  "<if test='isSpecial != null and isSpecial != \"\"'>AND is_special = #{isSpecial}</if> "
            + "<if test='brand != null and brand != \"\"'>AND brand = #{brand}</if> "
            + "<if test='model != null and model != \"\"'>AND model = #{model}</if> "
            + "<if test='receiptDateStart != null and receiptDateStart != \"\"'>AND receipt_date >= #{receiptDateStart}</if> "
            + "<if test='receiptDateEnd != null and receiptDateEnd != \"\"'>AND receipt_date &lt;= #{receiptDateEnd}</if> "   // &lt;= 而非 <=：<script> 注解 SQL 是 XML，< 会破坏标签解析（2026-10-19 生产启动崩溃回滚教训）
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + "GROUP BY 1, category, brand, model"
            + ") t"
            + "</script>")
    Map<String, BigDecimal> aggregateStats(@Param("company") String company,
                                           @Param("batchNo") String batchNo,
                                           @Param("agent") String agent,
                                           @Param("receiptNo") String receiptNo,
                                           @Param("category") String category,
                                           @Param("isSpecial") String isSpecial,
                                           @Param("brand") String brand,
                                           @Param("model") String model,
                                           @Param("receiptDateStart") String receiptDateStart,
                                           @Param("receiptDateEnd") String receiptDateEnd,
                                           @Param("corpIds") List<Long> corpIds);

    /** Per-category totals for current filter — same (order_no/business_group_no/receipt_no, category, brand, model)
     *  dedup rule as aggregateStats so per-category sums reconcile with overall totals. */
    @Select("<script>"
            + "SELECT t.category as category, "
            + "COALESCE(SUM(t.receipt_amount),0) as totalSale, "
            + "COALESCE(SUM(t.commission_amount),0) as totalCommission "
            + "FROM ("
            + "SELECT CASE WHEN sale_quantity < 0 "
            + "  THEN CONCAT('__RET__', COALESCE(source_return_id, 0), '_', COALESCE(order_id, id)) "
            + "  ELSE COALESCE(NULLIF(order_no, ''), NULLIF(business_group_no, ''), NULLIF(receipt_no, ''), CONCAT('__NULL__', id)) END as receipt_no, "
            + "category, brand, model, "
            + "MAX(receipt_amount) as receipt_amount, "
            + "SUM(commission_amount) as commission_amount "
            + "FROM ops_business_order_item WHERE delete_flag=0 "
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='receiptNo != null and receiptNo != \"\"'>AND receipt_no = #{receiptNo}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            +  "<if test='isSpecial != null and isSpecial != \"\"'>AND is_special = #{isSpecial}</if> "
            + "<if test='brand != null and brand != \"\"'>AND brand = #{brand}</if> "
            + "<if test='model != null and model != \"\"'>AND model = #{model}</if> "
            + "<if test='receiptDateStart != null and receiptDateStart != \"\"'>AND receipt_date >= #{receiptDateStart}</if> "
            + "<if test='receiptDateEnd != null and receiptDateEnd != \"\"'>AND receipt_date &lt;= #{receiptDateEnd}</if> "   // &lt;= 而非 <=：<script> 注解 SQL 是 XML，< 会破坏标签解析（2026-10-19 生产启动崩溃回滚教训）
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + "GROUP BY 1, category, brand, model"
            + ") t GROUP BY t.category ORDER BY SUM(t.receipt_amount) DESC"
            + "</script>")
    List<Map<String, Object>> categoryStats(@Param("company") String company,
                                            @Param("batchNo") String batchNo,
                                            @Param("agent") String agent,
                                            @Param("receiptNo") String receiptNo,
                                            @Param("category") String category,
                                            @Param("isSpecial") String isSpecial,
                                            @Param("brand") String brand,
                                            @Param("model") String model,
                                            @Param("receiptDateStart") String receiptDateStart,
                                            @Param("receiptDateEnd") String receiptDateEnd,
                                            @Param("corpIds") List<Long> corpIds);

    /** Count only — delegates to COUNT(*) for speed. */
    @Select("<script>"
            + "SELECT COUNT(*) FROM ops_business_order_item WHERE delete_flag=0 "
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='receiptNo != null and receiptNo != \"\"'>AND receipt_no = #{receiptNo}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            +  "<if test='isSpecial != null and isSpecial != \"\"'>AND is_special = #{isSpecial}</if> "
            + "<if test='brand != null and brand != \"\"'>AND brand = #{brand}</if> "
            + "<if test='model != null and model != \"\"'>AND model = #{model}</if> "
            + "<if test='receiptDateStart != null and receiptDateStart != \"\"'>AND receipt_date >= #{receiptDateStart}</if> "
            + "<if test='receiptDateEnd != null and receiptDateEnd != \"\"'>AND receipt_date &lt;= #{receiptDateEnd}</if> "   // &lt;= 而非 <=：<script> 注解 SQL 是 XML，< 会破坏标签解析（2026-10-19 生产启动崩溃回滚教训）
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + "</script>")
    long countFiltered(@Param("company") String company,
                       @Param("batchNo") String batchNo,
                       @Param("agent") String agent,
                       @Param("receiptNo") String receiptNo,
                       @Param("category") String category,
                       @Param("isSpecial") String isSpecial,
                       @Param("brand") String brand,
                       @Param("model") String model,
                       @Param("receiptDateStart") String receiptDateStart,
                       @Param("receiptDateEnd") String receiptDateEnd,
                       @Param("corpIds") List<Long> corpIds);

    /** Find which receipt numbers from the given set already exist in DB (within corp scope). */
    @Select("<script>"
            + "SELECT DISTINCT receipt_no FROM ops_business_order_item WHERE delete_flag=0 "
            + "<if test='corpId != null'>AND corp_id = #{corpId}</if> "
            + "AND receipt_no IN <foreach collection='receiptNos' item='r' open='(' separator=',' close=')'>#{r}</foreach>"
            + "</script>")
    List<String> findExistingReceiptNos(@Param("receiptNos") List<String> receiptNos,
                                        @Param("corpId") Long corpId);

    /** Per-category × per-company totals for commission summary (品类提成汇总) —
     *  先按「业务组号/收据号 + 品类+品牌+型号」去重每个产品行（多经办人只计一次），
     *  再按订单汇总销售额并归属：含 户内美装销售(6) 则归属销售品类；否则按品类中文名排序取首品类；
     *  提成金额按明细全量汇总（多经办人已均分，合计不变）。排除已上报集团行。 */
    @Select("<script>"
            + "SELECT r.category as category, r.company as company, "
            + "COALESCE(SUM(CASE WHEN r.rn = 1 THEN r.order_sale ELSE 0 END),0) as totalSale, "
            + "COALESCE(SUM(r.commission_amount),0) as totalCommission "
            + "FROM ("
            + "SELECT p.category, p.company, p.commission_amount, "
            + "SUM(p.amount) OVER (PARTITION BY p.grp, p.company) as order_sale, "
            + "ROW_NUMBER() OVER (PARTITION BY p.grp, p.company ORDER BY p.salePri, p.catName, p.id) as rn "
            + "FROM ("
            + "SELECT d0.grp, d0.company, d0.category, d0.salePri, d0.catName, "
            + "MIN(d0.id) as id, MAX(d0.receipt_amount) as amount, SUM(d0.commission_amount) as commission_amount "
            + "FROM ("
            + "SELECT id, category, company, brand, model, receipt_amount, commission_amount, "
            + "CASE WHEN sale_quantity < 0 "
            + "  THEN CONCAT('__RET__', COALESCE(source_return_id, 0), '_', COALESCE(order_id, id), "
            + "       '_', COALESCE(category,''), '_', COALESCE(brand,''), '_', COALESCE(model,'')) "
            + "  ELSE COALESCE(NULLIF(order_no,''), NULLIF(business_group_no,''), NULLIF(receipt_no,''), CONCAT('__NULL__', id)) END as grp, "
            + "CASE WHEN category = '6' THEN 0 ELSE 1 END as salePri, "
            + "CASE category WHEN '10' THEN '保险' WHEN '4' THEN '壁挂炉' WHEN '5' THEN '壁挂炉以旧换新销售' WHEN '2' THEN '抽油烟机' WHEN '7' THEN '定尺波纹管' WHEN '8' THEN '户内美装工程' WHEN '6' THEN '户内美装销售' WHEN '9' THEN '户内美装运维' WHEN '12' THEN '美装安全评估' WHEN '11' THEN '美装管道整改' WHEN 'qimi' THEN '气密性检测' WHEN '1' THEN '燃气灶' WHEN '3' THEN '热水器' ELSE category END as catName "
            + "FROM ops_business_order_item WHERE delete_flag=0 "
            + "AND calc_status != '已上报集团' "   // != 而非 <>：<script> XML 解析器中 <> 会破坏标签解析
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='receiptNo != null and receiptNo != \"\"'>AND receipt_no = #{receiptNo}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + ") d0 "
            + "GROUP BY d0.grp, d0.company, d0.category, d0.brand, d0.model, d0.salePri, d0.catName"
            + ") p"
            + ") r GROUP BY r.category, r.company"
            + "</script>")
    List<Map<String, Object>> categoryCompanyStats(@Param("company") String company,
                                                   @Param("batchNo") String batchNo,
                                                   @Param("agent") String agent,
                                                   @Param("receiptNo") String receiptNo,
                                                   @Param("category") String category,
                                                   @Param("corpIds") List<Long> corpIds);

    /** 品类提成汇总（按月）— 与 categoryCompanyStats 相同的产品级去重+订单归属口径，
     *  附加销售月份维度。订单月份 = 归属首行（户内美装销售优先、否则品类中文名排序）的 sale_date 月份，
     *  销售/提成同归该月，保证各月合计与全部汇总对账一致。返回行含 saleMonth（YYYY-MM）。 */
    @Select("<script>"
            + "SELECT r.category as category, r.company as company, r.saleMonth as saleMonth, "
            + "COALESCE(SUM(CASE WHEN r.rn = 1 THEN r.order_sale ELSE 0 END),0) as totalSale, "
            + "COALESCE(SUM(r.commission_amount),0) as totalCommission "
            + "FROM ("
            + "SELECT p.category, p.company, p.commission_amount, p.sale_date, "
            + "SUM(p.amount) OVER (PARTITION BY p.grp, p.company) as order_sale, "
            + "ROW_NUMBER() OVER (PARTITION BY p.grp, p.company ORDER BY p.salePri, p.catName, p.id) as rn, "
            + "DATE_FORMAT(FIRST_VALUE(p.sale_date) OVER (PARTITION BY p.grp, p.company ORDER BY p.salePri, p.catName, p.id), '%Y-%m') as saleMonth "
            + "FROM ("
            + "SELECT d0.grp, d0.company, d0.category, d0.salePri, d0.catName, "
            + "MIN(d0.id) as id, MIN(d0.sale_date) as sale_date, "
            + "MAX(d0.receipt_amount) as amount, SUM(d0.commission_amount) as commission_amount "
            + "FROM ("
            + "SELECT id, category, company, brand, model, receipt_amount, sale_date, commission_amount, "
            + "CASE WHEN sale_quantity < 0 "
            + "  THEN CONCAT('__RET__', COALESCE(source_return_id, 0), '_', COALESCE(order_id, id), "
            + "       '_', COALESCE(category,''), '_', COALESCE(brand,''), '_', COALESCE(model,'')) "
            + "  ELSE COALESCE(NULLIF(order_no,''), NULLIF(business_group_no,''), NULLIF(receipt_no,''), CONCAT('__NULL__', id)) END as grp, "
            + "CASE WHEN category = '6' THEN 0 ELSE 1 END as salePri, "
            + "CASE category WHEN '10' THEN '保险' WHEN '4' THEN '壁挂炉' WHEN '5' THEN '壁挂炉以旧换新销售' WHEN '2' THEN '抽油烟机' WHEN '7' THEN '定尺波纹管' WHEN '8' THEN '户内美装工程' WHEN '6' THEN '户内美装销售' WHEN '9' THEN '户内美装运维' WHEN '12' THEN '美装安全评估' WHEN '11' THEN '美装管道整改' WHEN 'qimi' THEN '气密性检测' WHEN '1' THEN '燃气灶' WHEN '3' THEN '热水器' ELSE category END as catName "
            + "FROM ops_business_order_item WHERE delete_flag=0 "
            + "AND calc_status != '已上报集团' "
            + "<if test='company != null and company != \"\"'>AND company = #{company}</if> "
            + "<if test='batchNo != null and batchNo != \"\"'>AND batch_no = #{batchNo}</if> "
            + "<if test='agent != null and agent != \"\"'>AND agent = #{agent}</if> "
            + "<if test='receiptNo != null and receiptNo != \"\"'>AND receipt_no = #{receiptNo}</if> "
            + "<if test='category != null and category != \"\"'>AND category = #{category}</if> "
            + "<if test='corpIds != null and corpIds.size() > 0'>AND corp_id IN <foreach collection='corpIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach></if> "
            + ") d0 "
            + "GROUP BY d0.grp, d0.company, d0.category, d0.brand, d0.model, d0.salePri, d0.catName"
            + ") p"
            + ") r GROUP BY r.category, r.company, r.saleMonth"
            + "</script>")
    List<Map<String, Object>> categoryCompanyStatsByMonth(@Param("company") String company,
                                                          @Param("batchNo") String batchNo,
                                                          @Param("agent") String agent,
                                                          @Param("receiptNo") String receiptNo,
                                                          @Param("category") String category,
                                                          @Param("corpIds") List<Long> corpIds);
}
