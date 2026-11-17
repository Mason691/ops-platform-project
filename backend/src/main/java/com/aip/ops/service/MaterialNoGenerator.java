package com.aip.ops.service;

import com.aip.sdk.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 物资领用/核销单号生成：{LY|WX}-yyyyMMdd-NNN。
 *
 * <p>并发安全依赖 ops_material_seq 的 (bill_type, bill_date) 唯一键 +
 * 原子 UPDATE current_no=current_no+1（InnoDB 行锁），禁止 SELECT MAX+1。</p>
 */
@Component
@RequiredArgsConstructor
public class MaterialNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_SEQ = 999;

    private final JdbcTemplate jdbcTemplate;

    public String next(String billType) {
        String date = LocalDate.now().format(DATE_FMT);
        int no = increment(billType, date);
        if (no > MAX_SEQ) {
            throw BusinessException.of("当日单号已达上限（" + MAX_SEQ + "），请联系管理员");
        }
        return billType + "-" + date + "-" + String.format("%03d", no);
    }

    private int increment(String billType, String date) {
        int updated = jdbcTemplate.update(
                "UPDATE ops_material_seq SET current_no=current_no+1 "
                        + "WHERE bill_type=? AND bill_date=?", billType, date);
        if (updated == 1) {
            return read(billType, date);
        }
        int inserted = jdbcTemplate.update(
                "INSERT INTO ops_material_seq (bill_type, bill_date, current_no) "
                        + "VALUES (?,?,1) ON DUPLICATE KEY UPDATE current_no=current_no",
                billType, date);
        if (inserted == 1) {
            return read(billType, date);
        }
        int again = jdbcTemplate.update(
                "UPDATE ops_material_seq SET current_no=current_no+1 "
                        + "WHERE bill_type=? AND bill_date=?", billType, date);
        if (again == 1) {
            return read(billType, date);
        }
        throw BusinessException.of("单号生成失败，请重试");
    }

    private int read(String billType, String date) {
        Integer no = jdbcTemplate.queryForObject(
                "SELECT current_no FROM ops_material_seq WHERE bill_type=? AND bill_date=?",
                Integer.class, billType, date);
        return no == null ? 0 : no;
    }
}
