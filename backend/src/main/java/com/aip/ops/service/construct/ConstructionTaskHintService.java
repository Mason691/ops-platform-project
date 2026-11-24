package com.aip.ops.service.construct;

import com.aip.ops.entity.OpsBusinessOrder;
import com.aip.ops.mapper.OpsBusinessOrderMapper;
import com.aip.sdk.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 施工节点唯一处理人解析。
 *
 * <p>施工员只取 {@code ops_business_order.worker_id}（派工出库时写入），
 * 绝不按“施工员角色”查询全量人员；平台回调解析失败必须抛错阻断流程推进。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConstructionTaskHintService {

    private final OpsBusinessOrderMapper orderMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 平台回调使用：解析失败必须抛错阻断流程推进。 */
    public OrderWorker resolveOrderWorker(Long orderId) {
        return resolveOrderWorker(requireOrder(orderId));
    }

    /** 平台回调使用：订单已加载时避免重复查询。 */
    public OrderWorker resolveOrderWorker(OpsBusinessOrder order) {
        if (order == null || order.getWorkerId() == null) {
            String orderNo = order == null ? "" : String.valueOf(order.getOrderNo());
            throw BusinessException.of("ORDER_WORKER_NOT_SET：订单 " + orderNo
                    + " 未指派施工员，请先完成派工");
        }
        String userName = resolveUserName(order.getWorkerId(), order.getWorkerName());
        log.info("订单施工员解析: orderId={}, orderNo={}, workerId={}, workerName={}, source=ORDER_FIELD",
                order.getId(), order.getOrderNo(), order.getWorkerId(), userName);
        return new OrderWorker(order.getWorkerId(), userName);
    }

    private OpsBusinessOrder requireOrder(Long orderId) {
        OpsBusinessOrder order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null || Integer.valueOf(1).equals(order.getDeleteFlag())) {
            throw BusinessException.notFound("业务订单不存在");
        }
        return order;
    }

    private String resolveUserName(Long userId, String fallback) {
        if (userId == null) {
            return text(fallback);
        }
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT COALESCE(NULLIF(real_name,''), NULLIF(nickname,''), username) "
                            + "FROM ai_platform.sys_user WHERE id = ? AND delete_flag = 0 LIMIT 1",
                    String.class, userId);
            String name = names.isEmpty() ? "" : text(names.get(0));
            return name.isEmpty() ? text(fallback) : name;
        } catch (Exception e) {
            log.warn("解析施工员姓名失败 userId={}: {}", userId, e.getMessage());
            return text(fallback);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record OrderWorker(Long userId, String userName) {
    }
}
