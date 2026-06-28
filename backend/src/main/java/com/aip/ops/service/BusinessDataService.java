package com.aip.ops.service;

import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

public interface BusinessDataService {
    Map<String, Object> page(int pageNum, int pageSize, String company, String batchNo);
    List<String> getBatches();
    Map<String, Object> importExcel(MultipartFile file, String batchNo);
    void batchDelete(List<Long> ids);
    Map<String, Object> calcCommission();
}
