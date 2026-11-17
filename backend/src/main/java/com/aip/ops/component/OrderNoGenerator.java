package com.aip.ops.component;

import com.aip.sdk.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 全局订单编号生成器：{dd}（日期中的天，两位）+ 至少 3 位全局流水。
 *
 * <p>用 REQUIRES_NEW 独立事务提交：导入/手工录入的长事务不会一直持有
 * ops_order_sequence 单行锁，避免并发录入被导入任务阻塞。代价是外层事务
 * 回滚时号段可能产生空洞（编号只要求唯一，不要求连续）。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderNoGenerator {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        jdbcTemplate.update("INSERT IGNORE INTO ops_order_sequence (id, last_seq) VALUES (1, 0)");
        jdbcTemplate.update("UPDATE ops_order_sequence SET last_seq = last_seq + 1 WHERE id = 1");
        Long seq = jdbcTemplate.queryForObject(
                "SELECT last_seq FROM ops_order_sequence WHERE id = 1", Long.class);
        if (seq == null || seq <= 0) {
            throw BusinessException.of("订单编号生成失败，请稍后重试");
        }
        int day = LocalDate.now().getDayOfMonth();
        return String.format(Locale.ROOT, "%02d%03d", day, seq);
    }
}
