package com.aip.ops.util;

import com.aip.sdk.exception.BusinessException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 「资金收支动态日报表」分类 Sheet 解析器。
 *
 * <p>实际文件结构：Sheet 名「分类」，仅 A 列有值，无表头，
 * 每行格式为「11.01 收入-气费（机械表）」（编码与名称可能无空格分隔）。
 * 编码与名称的拆分由调用方按“前五位 + 其余”完成。</p>
 */
public final class FfCategoryExcelParser {

    /** 分类数据所在工作表名 */
    public static final String SHEET_NAME = "分类";

    /** 表头/标题行文本，读取时忽略（实际文件无表头，兼容人工加表头的情况） */
    private static final Set<String> HEADER_TEXTS = Set.of("分类", "分类编码", "分类名称", "编码", "名称");

    /** 单文件数据行上限，防止异常超大文件拖垮服务 */
    private static final int MAX_ROWS = 10000;

    private FfCategoryExcelParser() {
    }

    /** Excel 中的一行非空数据 */
    public record RowData(int rowNum, String text) {
    }

    /**
     * 读取分类 Sheet 的 A 列非空文本行。
     *
     * @param input Excel 输入流（xlsx / xls）
     * @return 非空数据行（行号 1 基，已剔除表头行）
     */
    public static List<RowData> readRows(InputStream input) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                throw BusinessException.of("Excel 中未找到「" + SHEET_NAME + "」工作表");
            }
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<RowData> rows = new ArrayList<>();
            for (int i = sheet.getFirstRowNum(); i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Cell cell = row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell == null) {
                    continue;
                }
                String text = formatter.formatCellValue(cell, evaluator).strip();
                if (!text.isEmpty() && !HEADER_TEXTS.contains(text)) {
                    rows.add(new RowData(i + 1, text));
                    if (rows.size() > MAX_ROWS) {
                        throw BusinessException.of("数据行超过上限 " + MAX_ROWS + " 行");
                    }
                }
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of("Excel 解析失败：" + e.getMessage());
        }
    }
}
