package com.aip.ops.service;

import com.aip.ops.entity.OpsCompanyCommissionRule;
import com.aip.ops.entity.OpsPersonalCommissionRule;

import java.util.Map;

public interface CommissionRuleService {

    // ─── 个人提成规则 ───

    Map<String, Object> personalPage(int pageNum, int pageSize, String category, Integer status);

    OpsPersonalCommissionRule personalGetById(Long id);

    void personalCreate(OpsPersonalCommissionRule rule);

    void personalUpdate(Long id, OpsPersonalCommissionRule rule);

    void personalDelete(Long id);

    // ─── 公司提成规则 ───

    Map<String, Object> companyPage(int pageNum, int pageSize, String category, Integer status);

    OpsCompanyCommissionRule companyGetById(Long id);

    void companyCreate(OpsCompanyCommissionRule rule);

    void companyUpdate(Long id, OpsCompanyCommissionRule rule);

    void companyDelete(Long id);
}
