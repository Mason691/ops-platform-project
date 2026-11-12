package com.aip.ops.service;

import com.aip.ops.dto.ScmSaleReturnSyncRequest;

import java.util.Map;

/**
 * SCM 销售退货审核/反审核反写业务数据订单：审核按退货数量生成负数分录
 * （数量/收款金额/提成为负），反审核删除本单负数分录。按台账增量处理，重复调用幂等。
 */
public interface ScmSaleReturnSyncService {

    Map<String, Object> sync(ScmSaleReturnSyncRequest request);
}
