package com.aip.ops.service;

import com.aip.ops.entity.OpsCategoryLifecycleRule;

import java.util.List;
import java.util.Map;

/**
 * 品类使用年限规则服务（CRUD + 品类下拉选项 + 批量删除）。
 */
public interface CategoryLifecycleRuleService {

    /** 分页查询（按品类筛选可选） */
    Map<String, Object> page(int pageNum, int pageSize, String category);

    /** 按品类查询规则（单条，前端 byCategory 使用） */
    OpsCategoryLifecycleRule getByCategory(String category);

    /** 主键查询 */
    OpsCategoryLifecycleRule getById(Long id);

    /** 新增 */
    void create(OpsCategoryLifecycleRule rule);

    /** 更新 */
    void update(Long id, OpsCategoryLifecycleRule rule);

    /** 单个删除 */
    void delete(Long id);

    /** 批量删除 */
    void batchDelete(List<Long> ids);

    /** 品类下拉选项（从数据字典 PRODUCT_CATEGORY 加载，返回 [{key, label}]） */
    List<Map<String, String>> getCategoryOptions();
}
