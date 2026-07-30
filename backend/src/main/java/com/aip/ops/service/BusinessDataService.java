package com.aip.ops.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

public interface BusinessDataService {
    Map<String, Object> page(int pageNum, int pageSize, String company, String batchNo, String agent, String receiptNo);
    List<String> getBatches();
    Map<String, Object> importExcel(MultipartFile file, String batchNo, Long corpId);
    void batchDelete(List<Long> ids);

    /** 经办人核对：将指定批次下已计算数据标记为已核对 */
    int batchCheck(String batchNo);
    Map<String, Object> calcCommission();
    void exportExcel(String company, String batchNo, String agent, HttpServletResponse response);
}
