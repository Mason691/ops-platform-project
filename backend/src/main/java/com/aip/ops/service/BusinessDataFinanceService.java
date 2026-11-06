package com.aip.ops.service;

import com.aip.ops.dto.FinanceCheckRequest;

import java.util.Map;

/**
 * 增值业务-财务核对：补充/修改收据号、财务收款日期、资金动态表横排数、是否收款，
 * 并记录核对人与核对时间。已上报集团的数据锁定，禁止核对。
 */
public interface BusinessDataFinanceService {

    /**
     * 财务核对保存。
     *
     * @param id      业务数据 id
     * @param request 四项财务信息
     * @return 更新后的财务核对字段
     */
    Map<String, Object> financeCheck(Long id, FinanceCheckRequest request);
}
