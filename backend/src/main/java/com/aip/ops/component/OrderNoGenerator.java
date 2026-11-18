package com.aip.ops.component;

import com.aip.ops.service.OrgRoleService;
import com.aip.sdk.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * 业务订单编号生成器：{dd}（日期中的天，两位）+ 每日流水（至少 3 位）。
 *
 * <p>计数域为“运营商 + 其下全部城市合伙人”：不同运营商体系各自从 001 起，
 * 同一运营商体系内所有子公司/经办人共用一个当日序列，跨日自动归零。</p>
 *
 * <p>用 REQUIRES_NEW 独立事务提交：导入/手工录入的长事务不会一直持有
 * 每日流水行锁，避免并发录入被导入任务阻塞。代价是外层事务回滚时号段可能
 * 产生空洞（编号只要求唯一，不要求绝对连续）。</p>
 *
 * <p>当日超过 999 单时自动进位为 4 位（如 {@code 181000}），不阻断下单。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNoGenerator {

    /** 订单编号日期时区：与生产容器 TZ 一致，显式指定避免 JVM 默认时区差异。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final int THREE_DIGIT_MAX = 999;

    private final JdbcTemplate jdbcTemplate;
    private final OrgRoleService orgRoleService;

    /**
     * 取下一个订单编号。
     *
     * <p>原子 SQL：首次插入当日行时把 last_seq 置 1 并返回 1；已存在则
     * last_seq+1 并返回新值。两条语句在同一 REQUIRES_NEW 事务/连接内，
     * {@code LAST_INSERT_ID()} 结果不会被其他连接串号。</p>
     *
     * @param corpId 订单归属组织（不是运营商）；内部严格解析运营商计数域
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Generated next(Long corpId) {
        Long operatorCorpId = orgRoleService.getOperatorCorpIdStrict(corpId);
        LocalDate today = LocalDate.now(ZONE);
        Long seq;
        try {
            jdbcTemplate.update(
                    "INSERT INTO ops_order_daily_sequence (operator_corp_id, seq_date, last_seq) "
                            + "VALUES (?, ?, LAST_INSERT_ID(1)) "
                            + "ON DUPLICATE KEY UPDATE last_seq = LAST_INSERT_ID(last_seq + 1)",
                    operatorCorpId, Date.valueOf(today));
            seq = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } catch (DataAccessException e) {
            log.error("订单编号生成失败 operatorCorpId={} date={}: {}",
                    operatorCorpId, today, e.getMessage());
            throw BusinessException.of("订单编号生成失败，请稍后重试");
        }
        if (seq == null || seq <= 0) {
            throw BusinessException.of("订单编号生成失败，请稍后重试");
        }
        if (seq > THREE_DIGIT_MAX) {
            // 需求允许“进位为4位”；不静默截断，保留日志便于运营发现单量异常
            log.warn("订单日流水超过999，自动进位为4位 operatorCorpId={} date={} seq={}",
                    operatorCorpId, today, seq);
        }
        String dateKey = String.format(Locale.ROOT, "%04d%02d%02d",
                today.getYear(), today.getMonthValue(), today.getDayOfMonth());
        String orderNo = String.format(Locale.ROOT, "%02d%03d",
                today.getDayOfMonth(), seq);
        // 方案A：order_no 可跨月重复，order_key 带完整日期保证 (corp_id, order_key) 唯一
        return new Generated(orderNo, dateKey, "O|" + dateKey + "|" + orderNo);
    }

    /** 一次取号结果：展示编号、完整日期键、数据库唯一键。 */
    public record Generated(String orderNo, String dateKey, String orderKey) {
    }
}
