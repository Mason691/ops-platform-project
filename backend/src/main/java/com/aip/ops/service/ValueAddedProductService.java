package com.aip.ops.service;

import com.aip.ops.entity.OpsValueAddedProduct;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;

public interface ValueAddedProductService {

    Map<String, Object> page(int pageNum, int pageSize, String category, String brand, String model, Integer status);

    /** 导出核心四品类（燃气灶/抽油烟机/热水器/壁挂炉）产品信息，品牌/型号/状态筛选生效；
     *  品类筛选不适用（导出恒为四品类）。Excel 样式化输出，环境无 openpyxl 时降级 TSV。 */
    void export(String brand, String model, Integer status, HttpServletResponse response);

    OpsValueAddedProduct getById(Long id);

    void create(OpsValueAddedProduct product);

    void update(Long id, OpsValueAddedProduct product);

    void delete(Long id);
}
