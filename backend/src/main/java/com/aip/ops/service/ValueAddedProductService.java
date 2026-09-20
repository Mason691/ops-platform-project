package com.aip.ops.service;

import com.aip.ops.entity.OpsValueAddedProduct;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;

public interface ValueAddedProductService {

    Map<String, Object> page(int pageNum, int pageSize, String category, String brand, String model, Integer status);

    /** 按列表同筛选条件导出产品信息（Excel，环境无 openpyxl 时降级 TSV）。 */
    void export(String category, String brand, String model, Integer status, HttpServletResponse response);

    OpsValueAddedProduct getById(Long id);

    void create(OpsValueAddedProduct product);

    void update(Long id, OpsValueAddedProduct product);

    void delete(Long id);
}
