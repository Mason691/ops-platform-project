package com.aip.ops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 财务分类 Excel 导入结果。
 *
 * <p>策略：按编码覆盖更新、缺失新增；文件内编码重复的后续行跳过；
 * 格式错误/名称为空的行计入失败并返回原因。</p>
 */
@Data
public class FfCategoryImportResult {

    /** 非空数据行总数（不含空行和表头行） */
    private int total;

    /** 新增条数 */
    private int inserted;

    /** 覆盖更新条数 */
    private int updated;

    /** 跳过条数（文件内编码重复） */
    private int skipped;

    /** 失败条数（编码格式错误、名称为空等） */
    private int failed;

    /** 失败明细（最多保留 100 条，防止响应过大） */
    private List<ErrorItem> errors = new ArrayList<>();

    public void addError(int row, String code, String reason) {
        if (errors.size() < 100) {
            errors.add(new ErrorItem(row, code, reason));
        }
    }

    @Data
    @AllArgsConstructor
    public static class ErrorItem {
        /** Excel 行号（1 基） */
        private int row;
        /** 解析出的编码（可能为空） */
        private String code;
        /** 失败/跳过原因 */
        private String reason;
    }
}
