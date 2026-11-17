package com.aip.ops.service;

import com.aip.ops.dto.FfCategoryImportResult;
import com.aip.ops.entity.FfCategory;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.io.InputStream;

/**
 * 财务分类字典服务（分页查询 / CRUD / Excel 导入）。
 */
public interface FfCategoryService {

    /**
     * 分页查询，支持编码、名称模糊匹配与状态筛选。
     */
    Page<FfCategory> page(int pageNum, int pageSize, String categoryCode, String categoryName, Integer status);

    /** 主键查询，不存在时抛 404 */
    FfCategory getDetail(Long id);

    /** 新增（编码唯一校验 + 层级推导） */
    void create(FfCategory category);

    /** 编辑（编码唯一校验 + 层级推导） */
    void update(Long id, FfCategory category);

    /** 逻辑删除 */
    void delete(Long id);

    /**
     * 从 Excel「分类」Sheet 导入：按编码覆盖更新、缺失新增，
     * 文件内重复编码跳过，格式错误行计入失败明细。
     */
    FfCategoryImportResult importFromExcel(InputStream input);
}
