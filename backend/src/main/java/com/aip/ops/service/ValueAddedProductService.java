package com.aip.ops.service;

import com.aip.ops.entity.OpsValueAddedProduct;

import java.util.Map;

public interface ValueAddedProductService {

    Map<String, Object> page(int pageNum, int pageSize, String category, String brand, String model, Integer status);

    OpsValueAddedProduct getById(Long id);

    void create(OpsValueAddedProduct product);

    void update(Long id, OpsValueAddedProduct product);

    void delete(Long id);
}
