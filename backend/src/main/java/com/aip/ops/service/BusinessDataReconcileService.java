package com.aip.ops.service;

import java.util.Map;

/**
 * 主子表对账：主表合计与子表聚合一致、结算数量与退货数量逻辑一致。
 */
public interface BusinessDataReconcileService {

    Map<String, Object> reconcile();
}
