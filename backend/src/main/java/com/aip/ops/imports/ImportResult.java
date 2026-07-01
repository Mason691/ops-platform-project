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
    private List<String> errors = new ArrayList<>();

    public ImportResult() {}

    public static ImportResult success(int count, int totalRows) {
        ImportResult r = new ImportResult();
        r.status = "success";
        r.phase = "INSERT";
        r.count = count;
        r.totalRows = totalRows;
        r.successRows = count;
        return r;
    }

    public static ImportResult fail(String phase, List<String> errors, int totalRows) {
        ImportResult r = new ImportResult();
        r.status = "fail";
        r.phase = phase;
        r.errors = errors;
        r.totalRows = totalRows;
        r.failRows = errors.size();
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
        m.put("errors", errors);
        return m;
    }

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
