package com.aip.ops.service;

import com.aip.ops.dto.BusinessReturnApproveRequest;
import com.aip.ops.dto.BusinessReturnRequest;

import java.util.List;
import java.util.Map;

/**
 * 退货申请/审批服务。审批通过后更新订单内同产品全部经办人行、主表合计，
 * 并触发订单提成重算；已上报集团订单禁止退货。
 */
public interface BusinessReturnService {

    /** 经办人/财务发起退货申请（同一产品存在审批中申请时拒绝）。 */
    Map<String, Object> create(Long itemId, BusinessReturnRequest request);

    /** 某订单的全部退货申请（含已被驳回）。 */
    List<Map<String, Object>> listByOrder(Long orderId);

    /** 待审批列表（当前组织范围）。 */
    List<Map<String, Object>> listPending();

    /** 审批：approved=true 通过并生效，false 驳回。 */
    Map<String, Object> approve(Long requestId, BusinessReturnApproveRequest request);
}
