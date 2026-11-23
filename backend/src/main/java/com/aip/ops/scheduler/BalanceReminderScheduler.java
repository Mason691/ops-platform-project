package com.aip.ops.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定金尾款补录提醒（默认每日 09:00）。
 *
 * <p>刷新未结清订单的 {@code balance_remind_time}，供“货款补录”列表展示
 * 最近提醒时间；公众号消息推送为可选增强，未在本任务启用。</p>
 *
 * <p>频率可通过 {@code business-data.balance-reminder.cron} 覆盖。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceReminderScheduler {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "${business-data.balance-reminder.cron:0 0 9 * * *}")
    public void markReminder() {
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE ops_business_order SET balance_remind_time = NOW() "
                            + "WHERE delete_flag = 0 AND payment_type = 'DEPOSIT' "
                            + "AND is_full_payment = 0");
            if (updated > 0) {
                log.info("定金尾款补录提醒已刷新: orders={}", updated);
            }
        } catch (Exception e) {
            log.error("定金尾款补录提醒刷新失败", e);
        }
    }
}
