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
    private int matchedCount;  // 已匹配现有会员行数（platform_member_id 来自存量会员）
    private int unmatchedCount; // 未匹配会员行数（platform_member_id 留空，不阻断导入）
    private int createdCount;  // 本次导入自动新建会员数
    private List<String> createdList = new ArrayList<>(); // 新建会员清单（姓名 手机号）
    private int defaultQtyCount; // 销售数量为空默认按 1 处理的行数
    private int detailedAddressEmptyCount; // 详细地址为空的行数（便于运营核对）
    private List<String> errors = new ArrayList<>();
    private String message;     // human-readable summary, e.g. "已有用户 XXX，增量导入 XXXX"

    public ImportResult() {}

    public static ImportResult success(int count, int totalRows) {
        return success(count, totalRows, 0, 0, 0, List.of());
    }

    public static ImportResult success(int count, int totalRows, int matchedCount, int unmatchedCount) {
        return success(count, totalRows, matchedCount, unmatchedCount, 0, List.of());
    }

    public static ImportResult success(int count, int totalRows, int matchedCount,
                                       int unmatchedCount, int createdCount, List<String> createdList) {
        ImportResult r = new ImportResult();
        r.status = "success";
        r.phase = "INSERT";
        r.count = count;
        r.totalRows = totalRows;
        r.successRows = count;
        r.matchedCount = matchedCount;
        r.unmatchedCount = unmatchedCount;
        r.createdCount = createdCount;
        r.createdList = createdList != null ? createdList : List.of();
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
        m.put("matchedCount", matchedCount);
        m.put("unmatchedCount", unmatchedCount);
        m.put("createdCount", createdCount);
        m.put("createdList", createdList);
        m.put("defaultQtyCount", defaultQtyCount);
        m.put("detailedAddressEmptyCount", detailedAddressEmptyCount);
        m.put("errors", errors);
        m.put("message", message);
        return m;
    }

    public int getExistingCount() { return existingCount; }
    public void setExistingCount(int v) { existingCount = v; }
    public int getMatchedCount() { return matchedCount; }
    public void setMatchedCount(int v) { matchedCount = v; }
    public int getUnmatchedCount() { return unmatchedCount; }
    public void setUnmatchedCount(int v) { unmatchedCount = v; }
    public int getCreatedCount() { return createdCount; }
    public void setCreatedCount(int v) { createdCount = v; }
    public List<String> getCreatedList() { return createdList; }
    public void setCreatedList(List<String> v) { createdList = v; }
    public int getDefaultQtyCount() { return defaultQtyCount; }
    public void setDefaultQtyCount(int v) { defaultQtyCount = v; }
    public int getDetailedAddressEmptyCount() { return detailedAddressEmptyCount; }
    public void setDetailedAddressEmptyCount(int v) { detailedAddressEmptyCount = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
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
