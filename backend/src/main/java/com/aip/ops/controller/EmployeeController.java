package com.aip.ops.controller;

import com.aip.ops.service.EmployeeService;
import com.aip.sdk.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee identity verification and personal business data viewing.
 * All endpoints require JWT authentication (covered by SecurityConfig).
 */
@Slf4j
@RestController
@RequestMapping("/api/ops/employee")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    /** Check if current member is also an employee. Returns associated companies. */
    @GetMapping("/identity")
    public R<?> checkIdentity() {
        return R.ok(employeeService.checkIdentity());
    }

    /** List months (YYYY-MM) that have business data for this employee. */
    @GetMapping("/business-data/months")
    public R<?> getMonths() {
        return R.ok(employeeService.getAvailableMonths());
    }

    /** Category-level summary for a given month. */
    @GetMapping("/business-data/summary")
    public R<?> getSummary(@RequestParam String month) {
        return R.ok(employeeService.getCategorySummary(month));
    }

    /** Individual detail records for a given month + category. */
    @GetMapping("/business-data/detail")
    public R<?> getDetail(@RequestParam String month,
                          @RequestParam(required = false) String category) {
        return R.ok(employeeService.getDetailRecords(month, category));
    }

    /** Monthly aggregate stats with month-over-month comparison. */
    @GetMapping("/business-data/stats")
    public R<?> getStats(@RequestParam String month) {
        return R.ok(employeeService.getMonthlyStats(month));
    }

    /** All-in-one overview: all-time totals + monthly breakdown with detail records. */
    @GetMapping("/business-data/overview")
    public R<?> getOverview() {
        return R.ok(employeeService.getOverview());
    }
}
