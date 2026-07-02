package com.aip.ops.service;

import java.util.List;
import java.util.Map;

/**
 * Employee identity verification and personal business data queries.
 * All business data queries are scoped to the current user's phone number
 * (agent_phone) and only return records with calc_status='已计算'.
 */
public interface EmployeeService {

    /**
     * Check if the current member is also an employee (exists in platform sys_user).
     * Returns {isEmployee, companies: [{corpId, corpName, roleType, roleName}]}.
     */
    Map<String, Object> checkIdentity();

    /** List distinct months (YYYY-MM) that have business data for this employee. */
    List<String> getAvailableMonths();

    /** Category-level summary for a given month: totals by category. */
    List<Map<String, Object>> getCategorySummary(String month);

    /** Individual detail records for a given month + category. */
    List<Map<String, Object>> getDetailRecords(String month, String category);

    /** Monthly aggregate stats with month-over-month comparison. */
    Map<String, Object> getMonthlyStats(String month);
}
