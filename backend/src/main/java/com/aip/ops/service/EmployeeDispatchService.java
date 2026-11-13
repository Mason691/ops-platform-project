package com.aip.ops.service;

import java.util.Map;

/**
 * 员工端待处理工单（公众号 H5）。
 */
public interface EmployeeDispatchService {

    /** 待接单列表。 */
    Map<String, Object> listPending(int pageNum, int pageSize);

    /** 我的工单（可按状态筛选历史）。 */
    Map<String, Object> listMine(int pageNum, int pageSize, String status);

    /** 工单详情（仅被指派员工本人；打开时标记消息已读）。 */
    Map<String, Object> detail(Long id);

    /**
     * 接受任务：施工类型同步调用 SCM 生成未审核销售出库单并回写；
     * 随访类型直接置为已接单。重复调用幂等。
     */
    Map<String, Object> accept(Long id);

    /** 开始处理（已接单 → 处理中）。 */
    Map<String, Object> start(Long id);

    /** 完成工单（已接单/处理中 → 已完成）。 */
    Map<String, Object> complete(Long id);

    /** 退回工单（待接单 → 已退回，需填写原因）。 */
    Map<String, Object> reject(Long id, String reason);
}
