package com.aip.ops.service;

import com.aip.ops.entity.OpsAnnualPlan;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface AnnualPlanService {

    /** 分页列表查询 */
    Map<String, Object> page(int pageNum, int pageSize, Integer year, Long orgId, String category, Integer status);

    /** 详情 */
    OpsAnnualPlan getById(Long id);

    /** 新增 */
    void create(OpsAnnualPlan plan);

    /** 修改 */
    void update(Long id, OpsAnnualPlan plan);

    /** 删除 */
    void delete(Long id);

    /** 审核 */
    void approve(Long id);

    /** 集团年度总金额汇总 */
    Map<String, Object> summary(Integer year);

    /** 集团各品类汇总 */
    Map<String, Object> summaryByCategory(Integer year);

    /** 子公司各品类明细 */
    Map<String, Object> orgDetail(Integer year);

    /** Excel 导入 */
    Map<String, Object> importExcel(MultipartFile file, Long corpId);
}
