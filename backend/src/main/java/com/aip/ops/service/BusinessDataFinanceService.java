package com.aip.ops.service;

import com.aip.ops.dto.FinanceCheckRequest;

import java.util.Map;

/**
 * 增值业务-财务核对：补充/修改收据号、财务收款日期、资金动态表横排数、是否收款，
 * 并记录核对人与核对时间。已上报集团的数据锁定，禁止核对。
 */
public interface BusinessDataFinanceService {

    /**
     * 工作流节点动作调用：按订单整单核对，操作人/权限由工作流任务保证，
     * 不再读取当前 JWT 权限（内部回调线程没有 SecurityContext）。
     */
    Map<String, Object> financeCheckByOrder(Long orderId, FinanceCheckRequest request,
                                            Long operatorId, String operatorName);
}
