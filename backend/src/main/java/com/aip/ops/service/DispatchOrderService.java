package com.aip.ops.service;

import com.aip.ops.dto.DispatchCreateRequest;

import java.util.List;
import java.util.Map;

/**
 * 派工单管理（后台员工端；任何员工可发起，数据范围按组织隔离）。
 */
public interface DispatchOrderService {

    /** 发起派工（状态 PENDING），返回派工单摘要。 */
    Map<String, Object> create(DispatchCreateRequest request);

    /**
     * 发起派工并强制来源订单归属指定组织（员工 H5：城市合伙人只能对
     * 本组织订单发起派工，不接受跨组织）。
     */
    Map<String, Object> createForCorp(DispatchCreateRequest request, Long requiredCorpId);

    /** 派工单分页列表（组织数据范围隔离）。 */
    Map<String, Object> list(int pageNum, int pageSize, String status,
                             String dispatchType, String keyword, Long corpId);

    /** 派工单详情（含商品明细与消息记录）。 */
    Map<String, Object> detail(Long id);

    /** 取消待接单派工单（派发人/运营）。 */
    void cancel(Long id, String reason);

    /** 指定组织的可选员工（sys_user + sys_user_corp）。 */
    List<Map<String, Object>> assignees(Long corpId);

    /** 按来源订单批量查询进行中的派工单（列表提示重复派工用）。 */
    List<Map<String, Object>> activeByOrderIds(List<Long> orderIds);
}
