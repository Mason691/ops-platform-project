package com.aip.ops.service;

import com.aip.ops.dto.EmployeeBusinessEntryRequest;

import java.util.Map;

/**
 * 员工手工新增业务数据。
 *
 * <p>所有方法仅面向已登录员工：组织/经办人信息从 JWT + 平台 sys_user 派生，
 * 不接受前端传入的 corp_id / agent / agent_phone。</p>
 */
public interface EmployeeBusinessEntryService {

    /** 表单选项：员工/公司信息、可选批次、品牌/品类/型号。 */
    Map<String, Object> getManualEntryOptions();

    /** 按客户手机号查询平台会员姓名（仅返回会员 id + 姓名）。 */
    Map<String, Object> lookupMember(String phone);

    /** 保存手工录入的业务数据。 */
    Map<String, Object> createManualEntry(EmployeeBusinessEntryRequest request);
}
