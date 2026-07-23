package com.aip.ops.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入结果，支持 phased validation（FILE_CHECK/HEADER_CHECK/ROW_CHECK/BUSINESS_CHECK/INSERT）。
 */
public class ImportResult {

    private String status;     // "success" or "fail"
    private String phase;      // current phase identifier
    private int count;
    private int totalRows;
    private int successRows;
    private int failRows;
    private int existingCount; // 已有用户数量（用于增量导入场景）
    private List<String> errors = new ArrayList<>();
    private String message;     // human-readable summary, e.g. "已有用户 XXX，增量导入 XXXX"

    public ImportResult() {}

    public static ImportResult success(int count, int totalRows) {
        ImportResult r = new ImportResult();
        r.status = "success";
        r.phase = "INSERT";
        r.count = count;
        r.totalRows = totalRows;
        r.successRows = count;
        r.message = "文件业务数据为" + totalRows + "条，成功导入" + count + "条";
        return r;
    }

    public static ImportResult fail(String phase, List<String> errors, int totalRows) {
        ImportResult r = new ImportResult();
        r.status = "fail";
        r.phase = phase;
        r.errors = errors;
        r.totalRows = totalRows;
        r.failRows = errors.size();
        r.message = "文件业务数据为" + totalRows + "条，导入失败。原因：" + String.join("；", errors);
        return r;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("phase", phase);
        m.put("count", count);
        m.put("totalRows", totalRows);
        m.put("successRows", successRows);
        m.put("failRows", failRows);
        m.put("existingCount", existingCount);
        m.put("errors", errors);
        m.put("message", message);
        return m;
    }

    public int getExistingCount() { return existingCount; }
    public void setExistingCount(int v) { existingCount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getPhase() { return phase; }
    public void setPhase(String v) { phase = v; }
    public int getCount() { return count; }
    public void setCount(int v) { count = v; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int v) { totalRows = v; }
    public int getSuccessRows() { return successRows; }
    public void setSuccessRows(int v) { successRows = v; }
    public int getFailRows() { return failRows; }
    public void setFailRows(int v) { failRows = v; }
    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> v) { errors = v; }
}
