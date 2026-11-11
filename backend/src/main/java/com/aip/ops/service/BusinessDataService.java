package com.aip.ops.service;

import com.aip.ops.dto.ModelConfirmDecision;
import com.aip.ops.entity.SysImportTask;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface BusinessDataService {
    Map<String, Object> page(int pageNum, int pageSize, String company, String batchNo, String agent, String receiptNo, String category, String isSpecial, String brand, String model, String receiptDateStart, String receiptDateEnd);
    /** 订单维度聚合列表：订单级公共信息 + 产品明细 + 合计金额 + 经办人列表 */
    Map<String, Object> orderPage(int pageNum, int pageSize, String company, String batchNo,
                                  String agent, String receiptNo, String customer, String orderNo,
                                  String category, String isSpecial, String brand, String model,
                                  String receiptDateStart, String receiptDateEnd);
    List<String> getBatches();
    /** 导入阶段一（异步工作线程）：文件校验 + 品牌型号匹配解析，结果 JSON（fail/confirm/ready）写入任务 progressMsg */
    void runAsyncCheck(SysImportTask task);
    /** 导入阶段二（异步工作线程）：应用型号确认决策后入库，结果 JSON（success/fail）写入任务 progressMsg */
    void runAsyncConfirm(SysImportTask task, List<ModelConfirmDecision> decisions);
    void batchDelete(List<Long> ids);

    /** 经办人核对：将指定批次下已计算数据标记为已核对（管理后台路径） */
    int batchCheck(String batchNo);

    /** 员工自助核对：仅更新当前组织 + 当前经办人的已计算行（防跨组织/跨经办人写入） */
    int batchCheckOwned(String batchNo, Long corpId, String agentPhone);
    /** 获取所有"未计算"批次列表，按时间倒序 */
    List<String> getUncalcBatches();
    /** 个人提成计算（按指定批次），batchNo 为 null 时计算所有未计算批次 */
    Map<String, Object> calcCommission(String batchNo);
    /** 退货审批后重算指定订单的“未计算”行提成，并同步主表状态/合计 */
    Map<String, Object> recalculateOrder(Long orderId);
    /** 同步导出：按筛选条件查询并生成 XLSX 文件到 outFile（Kafka 不可用时的降级路径） */
    void exportExcel(String company, String batchNo, String agent, String category,
                     String brand, String model, String receiptDateStart, String receiptDateEnd, Path outFile);
    /** 提成上报集团：将当前筛选条件下非"已上报集团"的数据锁定为"已上报集团"，返回受影响行数 */
    int reportToGroup(String company, String batchNo, String agent, String receiptNo, String category);
    /** 手工重匹配会员：按行 corp 的燃气公司范围，以 客户手机→客户名称 重匹配并回写 platform_member_id（已上报集团行跳过） */
    Map<String, Object> rematchMembers(List<Long> ids);
    /** 按批次重匹配会员：批次内未关联行按 全局手机号→公司+姓名→创建 三级规则回写 platform_member_id（已上报集团行跳过） */
    Map<String, Object> rematchMembersByBatch(String batchNo);
    /** 品类提成汇总：按品类×公司汇总非已上报数据（提成金额+销售额），按模板格式生成 Excel 流式输出 */
    void exportCommissionSummary(String company, String batchNo, String agent, String receiptNo, String category,
                                 HttpServletResponse response);
}
