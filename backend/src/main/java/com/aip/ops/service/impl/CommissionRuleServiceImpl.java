package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsCompanyCommissionRule;
import com.aip.ops.entity.OpsPersonalCommissionRule;
import com.aip.ops.mapper.OpsCompanyCommissionRuleMapper;
import com.aip.ops.mapper.OpsPersonalCommissionRuleMapper;
import com.aip.ops.service.CommissionRuleService;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommissionRuleServiceImpl implements CommissionRuleService {

    private final OpsPersonalCommissionRuleMapper personalMapper;
    private final OpsCompanyCommissionRuleMapper companyMapper;

    private static final List<String> VALID_CATEGORIES =
            List.of("燃气灶", "抽油烟机", "壁挂炉", "热水器");

    private void validateCategory(String category) {
        if (category != null && !VALID_CATEGORIES.contains(category)) {
            throw new BusinessException("品类必须为：" + String.join("/", VALID_CATEGORIES));
        }
    }

    // ─── 个人提成规则 ───

    @Override
    public Map<String, Object> personalPage(int pageNum, int pageSize, String category, Integer status) {
        Page<OpsPersonalCommissionRule> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<OpsPersonalCommissionRule> qw = new LambdaQueryWrapper<>();
        if (category != null && !category.isEmpty()) {
            qw.eq(OpsPersonalCommissionRule::getCategory, category);
        }
        if (status != null) {
            qw.eq(OpsPersonalCommissionRule::getStatus, status);
        }
        qw.orderByDesc(OpsPersonalCommissionRule::getPriority).orderByAsc(OpsPersonalCommissionRule::getId);
        IPage<OpsPersonalCommissionRule> result = personalMapper.selectPage(page, qw);
        return Map.of(
                "records", result.getRecords(),
                "total", result.getTotal(),
                "size", result.getSize(),
                "current", result.getCurrent()
        );
    }

    @Override
    public OpsPersonalCommissionRule personalGetById(Long id) {
        OpsPersonalCommissionRule rule = personalMapper.selectById(id);
        if (rule == null) {
            throw BusinessException.notFound("个人提成规则不存在");
        }
        return rule;
    }

    @Override
    @Transactional
    public void personalCreate(OpsPersonalCommissionRule rule) {
        validateCategory(rule.getCategory());
        if (rule.getRuleType() == null || rule.getRuleType().isEmpty()) {
            rule.setRuleType("percentage");
        }
        if (rule.getStatus() == null) {
            rule.setStatus(1);
        }
        personalMapper.insert(rule);
    }

    @Override
    @Transactional
    public void personalUpdate(Long id, OpsPersonalCommissionRule rule) {
        OpsPersonalCommissionRule existing = personalGetById(id);
        validateCategory(rule.getCategory());
        rule.setId(id);
        personalMapper.updateById(rule);
    }

    @Override
    @Transactional
    public void personalDelete(Long id) {
        personalGetById(id); // 确保存在
        personalMapper.deleteById(id);
    }

    // ─── 公司提成规则 ───

    @Override
    public Map<String, Object> companyPage(int pageNum, int pageSize, String category, Integer status) {
        Page<OpsCompanyCommissionRule> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<OpsCompanyCommissionRule> qw = new LambdaQueryWrapper<>();
        if (category != null && !category.isEmpty()) {
            qw.eq(OpsCompanyCommissionRule::getCategory, category);
        }
        if (status != null) {
            qw.eq(OpsCompanyCommissionRule::getStatus, status);
        }
        qw.orderByDesc(OpsCompanyCommissionRule::getPriority).orderByAsc(OpsCompanyCommissionRule::getId);
        IPage<OpsCompanyCommissionRule> result = companyMapper.selectPage(page, qw);
        return Map.of(
                "records", result.getRecords(),
                "total", result.getTotal(),
                "size", result.getSize(),
                "current", result.getCurrent()
        );
    }

    @Override
    public OpsCompanyCommissionRule companyGetById(Long id) {
        OpsCompanyCommissionRule rule = companyMapper.selectById(id);
        if (rule == null) {
            throw BusinessException.notFound("公司提成规则不存在");
        }
        return rule;
    }

    @Override
    @Transactional
    public void companyCreate(OpsCompanyCommissionRule rule) {
        validateCategory(rule.getCategory());
        if (rule.getRuleType() == null || rule.getRuleType().isEmpty()) {
            rule.setRuleType("percentage");
        }
        if (rule.getStatus() == null) {
            rule.setStatus(1);
        }
        companyMapper.insert(rule);
    }

    @Override
    @Transactional
    public void companyUpdate(Long id, OpsCompanyCommissionRule rule) {
        companyGetById(id);
        validateCategory(rule.getCategory());
        rule.setId(id);
        companyMapper.updateById(rule);
    }

    @Override
    @Transactional
    public void companyDelete(Long id) {
        companyGetById(id);
        companyMapper.deleteById(id);
    }
}
