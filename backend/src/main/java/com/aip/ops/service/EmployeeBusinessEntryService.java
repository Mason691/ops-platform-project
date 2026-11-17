package com.aip.ops.service;

import com.aip.ops.dto.EmployeeBusinessEntryRequest;

import java.util.Map;

/**
 * 员工手工新增业务数据。
 *
 * <p>所有方法仅面向已登录员工：组织/当前经办人信息从 JWT + 平台 sys_user 派生，
 * 不接受前端传入的 corp_id / agent / agent_phone；共同经办人仅接受员工ID，由服务端回查组织归属。</p>
 */
public interface EmployeeBusinessEntryService {

    /** 表单选项：员工/公司信息、可选批次、品牌/品类/型号。 */
    Map<String, Object> getManualEntryOptions();

    /** 按客户手机号查询平台会员姓名（仅返回会员 id + 姓名）。 */
    Map<String, Object> lookupMember(String phone);

    /** 保存手工录入的业务数据（同步发起工作流并带回下一审批人）。 */
    Map<String, Object> createManualEntry(EmployeeBusinessEntryRequest request);

    /** 结果页轮询/详情兜底：查询订单当前审批人。 */
    Map<String, Object> currentApprover(Long orderId);

    /**
     * 收据号查重（制单阶段失焦提示）：同组织内仅允许同一客户复用，
     * 被其他客户占用时返回 duplicate=true + message。
     */
    Map<String, Object> checkReceiptNo(String receiptNo, String customer);
}
