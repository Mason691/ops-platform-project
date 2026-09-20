package com.aip.ops.service;

import com.aip.ops.entity.OpsValueAddedProduct;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;

public interface ValueAddedProductService {

    Map<String, Object> page(int pageNum, int pageSize, String category, String brand, String model, Integer status);

    /** 导出核心四品类（燃气灶/抽油烟机/热水器/壁挂炉）全量产品信息，
     *  不受列表筛选影响；描述并入备注列。Excel 样式化输出，环境无 openpyxl 时降级 TSV。 */
    void export(HttpServletResponse response);

    OpsValueAddedProduct getById(Long id);

    void create(OpsValueAddedProduct product);

    void update(Long id, OpsValueAddedProduct product);

    void delete(Long id);
}
