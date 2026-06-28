package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsCompanyCommissionRule;
import com.aip.ops.entity.OpsPersonalCommissionRule;
import com.aip.ops.mapper.OpsCompanyCommissionRuleMapper;
import com.aip.ops.mapper.OpsPersonalCommissionRuleMapper;
import com.aip.ops.service.CommissionRuleService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionRuleServiceImpl implements CommissionRuleService {

    private final OpsPersonalCommissionRuleMapper personalMapper;
    private final OpsCompanyCommissionRuleMapper companyMapper;
    private final PlatformInternalClient platformInternalClient;

    /** 品类缓存（从平台数据字典加载），volatile + 幂等重载保证线程安全 */
    private volatile Set<String> categoryCache;

    /** 默认品类（字典接口不可用时的兜底，含全部 dict item_key） */
    private static final Set<String> DEFAULT_CATEGORIES =
            Set.of("1", "2", "3", "4", "5", "6", "7", "8", "9");

    /**
     * 从平台数据字典加载有效品类列表，结果缓存到内存。
     */
    private Set<String> getValidCategories() {
        if (categoryCache != null) {
            return categoryCache;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/dict/data?typeCode=PRODUCT_CATEGORY", null);
            Object data = resp.get("data");
            if (data instanceof List) {
                Set<String> set = new HashSet<>();
                for (Object item : (List<?>) data) {
                    if (item instanceof Map) {
                        Object val = ((Map<?, ?>) item).get("itemKey");
                        if (val != null) set.add(val.toString());
                    }
                }
                if (!set.isEmpty()) {
                    categoryCache = set;
                    return set;
                }
            }
        } catch (Exception e) {
            log.warn("从平台字典加载 PROPUCT_CATEGORY 失败，使用默认品类", e);
        }
        return DEFAULT_CATEGORIES;
    }

    private void validateCategory(String category) {
        if (category != null && !getValidCategories().contains(category)) {
            Set<String> cats = getValidCategories();
            throw new BusinessException("品类必须为：" + String.join("/", cats));
        }
    }

    // ─── 个人提成规则 ───

    @Override
    public Map<String, Object> personalPage(int pageNum, int pageSize, String category, Integer status) {
        LambdaQueryWrapper<OpsPersonalCommissionRule> qw = new LambdaQueryWrapper<>();
        if (category != null && !category.isEmpty()) qw.eq(OpsPersonalCommissionRule::getCategory, category);
        if (status != null) qw.eq(OpsPersonalCommissionRule::getStatus, status);
        qw.orderByDesc(OpsPersonalCommissionRule::getPriority).orderByAsc(OpsPersonalCommissionRule::getId);

        long total = personalMapper.selectCount(qw);
        int offset = (pageNum - 1) * pageSize;
        qw.last("LIMIT " + offset + "," + pageSize);
        return Map.of("records", personalMapper.selectList(qw), "total", total,
                "size", pageSize, "current", pageNum);
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
        if (rule.getCommissionType() == null || rule.getCommissionType().isEmpty()) {
            rule.setCommissionType("固定");
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
        LambdaQueryWrapper<OpsCompanyCommissionRule> qw = new LambdaQueryWrapper<>();
        if (category != null && !category.isEmpty()) qw.eq(OpsCompanyCommissionRule::getCategory, category);
        if (status != null) qw.eq(OpsCompanyCommissionRule::getStatus, status);
        qw.orderByDesc(OpsCompanyCommissionRule::getPriority).orderByAsc(OpsCompanyCommissionRule::getId);

        long total = companyMapper.selectCount(qw);
        int offset = (pageNum - 1) * pageSize;
        qw.last("LIMIT " + offset + "," + pageSize);
        return Map.of("records", companyMapper.selectList(qw), "total", total,
                "size", pageSize, "current", pageNum);
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
