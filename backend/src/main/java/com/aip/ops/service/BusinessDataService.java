package com.aip.ops.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

public interface BusinessDataService {
    Map<String, Object> page(int pageNum, int pageSize, String company, String batchNo, String agent, String receiptNo, String category);
    List<String> getBatches();
    Map<String, Object> importExcel(MultipartFile file, String batchNo, Long corpId);
    void batchDelete(List<Long> ids);

    /** 经办人核对：将指定批次下已计算数据标记为已核对 */
    int batchCheck(String batchNo);
    /** 获取所有"未计算"批次列表，按时间倒序 */
    List<String> getUncalcBatches();
    /** 个人提成计算（按指定批次），batchNo 为 null 时计算所有未计算批次 */
    Map<String, Object> calcCommission(String batchNo);
    void exportExcel(String company, String batchNo, String agent, HttpServletResponse response);
}
