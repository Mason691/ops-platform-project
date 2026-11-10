package com.aip.ops.service.impl;

import com.aip.ops.dto.FfCategoryImportResult;
import com.aip.ops.entity.FfCategory;
import com.aip.ops.mapper.FfCategoryMapper;
import com.aip.ops.service.FfCategoryService;
import com.aip.ops.util.FfCategoryExcelParser;
import com.aip.ops.util.FfCategoryExcelParser.RowData;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 财务分类字典服务实现。
 *
 * <p>平台级基础数据，无 corp_id；编码唯一性由应用层保证（逻辑删除下不建 DB 唯一键，
 * 避免与已删除行冲突）。导入策略：按编码覆盖名称/父级/层级，缺失新增，
 * 文件内重复编码跳过；不清空存量数据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FfCategoryServiceImpl implements FfCategoryService {

    /** 分类编码固定 5 位：两位数字 + 点 + 两位数字 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{2}\\.\\d{2}$");
    private static final int CODE_LENGTH = 5;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_PAGE_SIZE = 200;

    private final FfCategoryMapper mapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public Page<FfCategory> page(int pageNum, int pageSize, String categoryCode, String categoryName, Integer status) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<FfCategory> qw = Wrappers.lambdaQuery(FfCategory.class)
                .like(StringUtils.hasText(categoryCode), FfCategory::getCategoryCode, categoryCode)
                .like(StringUtils.hasText(categoryName), FfCategory::getCategoryName, categoryName)
                .eq(status != null, FfCategory::getStatus, status)
                .orderByAsc(FfCategory::getSort)
                .orderByAsc(FfCategory::getId);
        return mapper.selectPage(new Page<>(safePageNum, safePageSize), qw);
    }

    @Override
    public FfCategory getDetail(Long id) {
        FfCategory category = mapper.selectById(id);
        if (category == null) {
            throw BusinessException.notFound("分类不存在");
        }
        return category;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(FfCategory category) {
        normalizeAndValidate(category);
        assertCodeAvailable(category.getCategoryCode(), null);
        applyHierarchy(category, category.getCategoryCode());
        category.setId(null);
        category.setDeleteFlag(0);
        if (category.getStatus() == null) {
            category.setStatus(1);
        }
        if (category.getSort() == null) {
            category.setSort(0);
        }
        mapper.insert(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, FfCategory category) {
        FfCategory existing = getDetail(id);
        normalizeAndValidate(category);
        assertCodeAvailable(category.getCategoryCode(), id);
        applyHierarchy(category, category.getCategoryCode());
        if (category.getStatus() == null) {
            category.setStatus(existing.getStatus());
        }
        if (category.getSort() == null) {
            category.setSort(existing.getSort() == null ? 0 : existing.getSort());
        }

        FfCategory patch = new FfCategory();
        patch.setCategoryCode(category.getCategoryCode());
        patch.setCategoryName(category.getCategoryName());
        patch.setParentCode(category.getParentCode());
        patch.setLevel(category.getLevel());
        patch.setStatus(category.getStatus());
        patch.setSort(category.getSort());
        // remark 允许清空，通过 wrapper.set 显式写入 NULL
        mapper.update(patch, Wrappers.<FfCategory>lambdaUpdate()
                .eq(FfCategory::getId, id)
                .set(FfCategory::getRemark, category.getRemark()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getDetail(id);
        mapper.deleteById(id);
    }

    /**
     * 解析在事务外完成，事务只包住数据库写入 —— 50MB 大文件解析可能耗时数秒，
     * 避免在此期间长期占用 Hikari 连接。
     */
    @Override
    public FfCategoryImportResult importFromExcel(InputStream input) {
        List<RowData> rows = FfCategoryExcelParser.readRows(input);
        FfCategoryImportResult result = new FfCategoryImportResult();
        result.setTotal(rows.size());
        if (rows.isEmpty()) {
            return result;
        }
        transactionTemplate.executeWithoutResult(status -> doImport(rows, result));
        log.info("财务分类导入完成：total={}, inserted={}, updated={}, skipped={}, failed={}",
                result.getTotal(), result.getInserted(), result.getUpdated(),
                result.getSkipped(), result.getFailed());
        return result;
    }

    private void doImport(List<RowData> rows, FfCategoryImportResult result) {
        Map<String, FfCategory> existing = loadExisting(rows);
        Map<String, Integer> seenCodes = new HashMap<>();
        int sort = 10;
        for (RowData row : rows) {
            sort = importRow(row, existing, seenCodes, result, sort);
        }
    }

    /**
     * 处理一行：拆分 → 校验 → 文件内去重 → 覆盖更新/新增。
     *
     * @return 下一行的排序值（新增行按 Excel 行序 ×10 递增）
     */
    private int importRow(RowData row, Map<String, FfCategory> existing,
                          Map<String, Integer> seenCodes, FfCategoryImportResult result, int sort) {
        String text = row.text();
        if (text.length() < CODE_LENGTH) {
            fail(result, row.rowNum(), "", "内容不足 5 位，无法拆分编码");
            return sort;
        }
        String code = text.substring(0, CODE_LENGTH).strip();
        String name = text.substring(CODE_LENGTH).strip();
        if (!CODE_PATTERN.matcher(code).matches()) {
            fail(result, row.rowNum(), code, "分类编码格式不正确（应为两位数字.两位数字，如 11.01）");
            return sort;
        }
        if (name.isEmpty()) {
            fail(result, row.rowNum(), code, "分类名称为空");
            return sort;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            fail(result, row.rowNum(), code, "分类名称超过 " + MAX_NAME_LENGTH + " 字");
            return sort;
        }
        Integer firstRow = seenCodes.putIfAbsent(code, row.rowNum());
        if (firstRow != null) {
            result.setSkipped(result.getSkipped() + 1);
            result.addError(row.rowNum(), code, "编码与第 " + firstRow + " 行重复，已跳过");
            return sort;
        }
        FfCategory current = existing.get(code);
        if (current != null) {
            updateExisting(current, name, result);
            return sort;
        }
        insertNew(code, name, sort, result);
        return sort + 10;
    }

    private void updateExisting(FfCategory current, String name, FfCategoryImportResult result) {
        FfCategory patch = new FfCategory();
        patch.setId(current.getId());
        patch.setCategoryName(name);
        applyHierarchy(patch, current.getCategoryCode());
        mapper.updateById(patch);
        result.setUpdated(result.getUpdated() + 1);
    }

    private void insertNew(String code, String name, int sort, FfCategoryImportResult result) {
        FfCategory category = new FfCategory();
        category.setCategoryCode(code);
        category.setCategoryName(name);
        category.setStatus(1);
        category.setSort(sort);
        applyHierarchy(category, code);
        mapper.insert(category);
        result.setInserted(result.getInserted() + 1);
    }

    private void fail(FfCategoryImportResult result, int rowNum, String code, String reason) {
        result.setFailed(result.getFailed() + 1);
        result.addError(rowNum, code, reason);
    }

    /** 一次性加载文件中出现的编码对应的存量记录（@TableLogic 自动排除已删除行） */
    private Map<String, FfCategory> loadExisting(List<RowData> rows) {
        Set<String> codes = rows.stream()
                .filter(r -> r.text().length() >= CODE_LENGTH)
                .map(r -> r.text().substring(0, CODE_LENGTH).strip())
                .filter(code -> CODE_PATTERN.matcher(code).matches())
                .collect(Collectors.toSet());
        if (codes.isEmpty()) {
            return Collections.emptyMap();
        }
        List<FfCategory> list = mapper.selectList(
                Wrappers.lambdaQuery(FfCategory.class).in(FfCategory::getCategoryCode, codes));
        return list.stream().collect(Collectors.toMap(
                FfCategory::getCategoryCode, c -> c, (a, b) -> a));
    }

    private void normalizeAndValidate(FfCategory category) {
        category.setCategoryCode(category.getCategoryCode() == null
                ? null : category.getCategoryCode().strip());
        category.setCategoryName(category.getCategoryName() == null
                ? null : category.getCategoryName().strip());
        category.setRemark(category.getRemark() == null ? null : category.getRemark().strip());
        if (!StringUtils.hasText(category.getCategoryCode())) {
            throw BusinessException.of("分类编码不能为空");
        }
        if (!CODE_PATTERN.matcher(category.getCategoryCode()).matches()) {
            throw BusinessException.of("分类编码格式不正确（应为两位数字.两位数字，如 11.01）");
        }
        if (!StringUtils.hasText(category.getCategoryName())) {
            throw BusinessException.of("分类名称不能为空");
        }
        if (category.getCategoryName().length() > MAX_NAME_LENGTH) {
            throw BusinessException.of("分类名称不能超过 " + MAX_NAME_LENGTH + " 字");
        }
        if (category.getStatus() != null && category.getStatus() != 0 && category.getStatus() != 1) {
            throw BusinessException.of("状态只能为 1（启用）或 0（禁用）");
        }
    }

    private void assertCodeAvailable(String code, Long excludeId) {
        LambdaQueryWrapper<FfCategory> qw = Wrappers.lambdaQuery(FfCategory.class)
                .eq(FfCategory::getCategoryCode, code);
        if (excludeId != null) {
            qw.ne(FfCategory::getId, excludeId);
        }
        if (mapper.selectCount(qw) > 0) {
            throw BusinessException.of("分类编码 " + code + " 已存在");
        }
    }

    /** 按点号推导父级编码与层级：11.01 → parent=11, level=2 */
    private void applyHierarchy(FfCategory category, String code) {
        int dot = code.lastIndexOf('.');
        category.setParentCode(dot > 0 ? code.substring(0, dot) : null);
        category.setLevel(code.split("\\.").length);
    }
}
