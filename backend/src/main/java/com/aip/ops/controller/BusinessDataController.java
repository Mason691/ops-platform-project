package com.aip.ops.controller;

import com.aip.ops.service.BusinessDataService;
import com.aip.sdk.result.R;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ops/admin/business-data")
@RequiredArgsConstructor
public class BusinessDataController {

    private final BusinessDataService service;

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") int pageNum,
                     @RequestParam(defaultValue = "10") int pageSize,
                     @RequestParam(required = false) String company,
                     @RequestParam(required = false) String batchNo,
                     @RequestParam(required = false) String agent) {
        return R.ok(service.page(pageNum, pageSize, company, batchNo, agent));
    }

    @GetMapping("/batches")
    public R<?> batches() { return R.ok(service.getBatches()); }

    @PostMapping("/import")
    public R<?> importExcel(@RequestParam("file") MultipartFile file) {
        String batchNo = "金厨美家" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return R.ok(service.importExcel(file, batchNo));
    }

    @PostMapping("/calc-commission")
    public R<?> calcCommission() { return R.ok(service.calcCommission()); }

    @PostMapping("/export")
    public void exportExcel(@RequestBody Map<String, String> params, HttpServletResponse response) {
        service.exportExcel(params.get("company"), params.get("batchNo"), params.get("agent"), response);
    }

    @DeleteMapping("/batch")
    public R<?> batchDelete(@RequestBody Map<String, List<Long>> params) {
        service.batchDelete(params.get("ids"));
        return R.ok("删除成功");
    }
}
