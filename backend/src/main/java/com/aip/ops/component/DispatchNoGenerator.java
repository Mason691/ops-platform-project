package com.aip.ops.component;

import com.aip.sdk.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 派工单号生成器：{公司代码}-PG-{yyyyMMdd}-{3位流水}，如 DB-PG-20251113-001。
 *
 * <p>流水按 {@code (company_code, seq_date)} 维度分配：{@code INSERT ... ON DUPLICATE KEY UPDATE}
 * 原子递增，InnoDB 行锁串行化并发请求；独立事务提交（REQUIRES_NEW），避免外层
 * 派工单事务回滚时长期持有流水行锁。编号只要求唯一，回滚可产生号段空洞。</p>
 *
 * <p>公司代码在事务外由 {@link CompanyCodeResolver} 解析后传入，避免事务持有连接期间
 * 调用底座内部 API。</p>
 */
@Component
@RequiredArgsConstructor
public class DispatchNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_SEQ = 999;

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next(String companyCode, Long corpId) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        jdbcTemplate.update(
                "INSERT INTO ops_dispatch_sequence (corp_id, company_code, seq_date, current_no) "
                        + "VALUES (?, ?, ?, 1) "
                        + "ON DUPLICATE KEY UPDATE corp_id = VALUES(corp_id), current_no = current_no + 1",
                corpId, companyCode, today);
        Integer no = jdbcTemplate.queryForObject(
                "SELECT current_no FROM ops_dispatch_sequence WHERE company_code = ? AND seq_date = ?",
                Integer.class, companyCode, today);
        if (no == null || no <= 0) {
            throw BusinessException.of("派工单号生成失败，请稍后重试");
        }
        if (no > MAX_SEQ) {
            throw BusinessException.of("当日派工单号已达上限（" + MAX_SEQ + "），请联系管理员");
        }
        return companyCode + "-PG-" + today.format(DATE_FMT)
                + "-" + String.format(Locale.ROOT, "%03d", no);
    }
}
