package com.aip.ops.service;

import com.aip.ops.dto.DispatchCreateRequest;

import java.util.List;
import java.util.Map;

/**
 * 员工端工单（公众号 H5）：待处理接单 + 本组织发起派工。
 *
 * <p>发起派工对任何员工开放（会员 SSO 令牌拒绝），且来源订单必须属于
 * 当前员工组织（{@code createForCorp} 强制）。</p>
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

    /** 当前员工是否可发起派工（任何员工；会员 SSO 令牌拒绝）。 */
    Map<String, Object> canDispatch();

    /** 本组织可派工订单（已财务核对或户内美装，且无进行中派工）。 */
    Map<String, Object> listDispatchableOrders(int pageNum, int pageSize, String keyword);

    /** 可派工订单详情（客户信息 + 可施工商品分组）。 */
    Map<String, Object> dispatchableOrder(Long orderId);

    /** 订单归属组织可指派员工。 */
    List<Map<String, Object>> dispatchAssignees();

    /** 发起派工（仅限本组织订单），创建后异步推送公众号通知。 */
    Map<String, Object> createDispatch(DispatchCreateRequest request);
}
