package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsCategoryLifecycleRule;
import com.aip.ops.mapper.OpsCategoryLifecycleRuleMapper;
import com.aip.ops.service.CategoryLifecycleRuleService;
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
public class CategoryLifecycleRuleServiceImpl implements CategoryLifecycleRuleService {

    private final OpsCategoryLifecycleRuleMapper mapper;
    private final PlatformInternalClient platformInternalClient;

    /** 品类缓存（从平台数据字典加载），volatile + 幂等重载保证线程安全 */
    private volatile Set<String> categoryCache;

    /** 默认品类兜底 */
    private static final Set<String> DEFAULT_CATEGORIES =
            Set.of("燃气灶", "抽油烟机", "壁挂炉", "热水器");

    private Set<String> getValidCategories() {
        if (categoryCache != null) return categoryCache;
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
                if (!set.isEmpty()) { categoryCache = set; return set; }
            }
        } catch (Exception e) {
            log.warn("加载 PRODUCT_CATEGORY 字典失败，使用默认品类", e);
        }
        return DEFAULT_CATEGORIES;
    }

    @Override
    public List<Map<String, String>> getCategoryOptions() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = platformInternalClient.get(
                    "/api/dict/data?typeCode=PRODUCT_CATEGORY", null);
            Object data = resp.get("data");
            if (data instanceof List) {
                List<Map<String, String>> result = new ArrayList<>();
                for (Object item : (List<?>) data) {
                    if (item instanceof Map) {
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("key", String.valueOf(((Map<?, ?>) item).get("itemKey")));
                        entry.put("label", String.valueOf(((Map<?, ?>) item).get("itemValue")));
                        result.add(entry);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to load PRODUCT_CATEGORY dict for category options", e);
        }
        return List.of();
    }

    @Override
    public Map<String, Object> page(int pageNum, int pageSize, String category) {
        LambdaQueryWrapper<OpsCategoryLifecycleRule> qw = new LambdaQueryWrapper<>();
        if (category != null && !category.isEmpty()) {
            qw.eq(OpsCategoryLifecycleRule::getCategory, category);
        }
        qw.orderByAsc(OpsCategoryLifecycleRule::getCategory).orderByAsc(OpsCategoryLifecycleRule::getId);

        long total = mapper.selectCount(qw);
        int offset = (pageNum - 1) * pageSize;
        qw.last("LIMIT " + offset + "," + pageSize);
        List<OpsCategoryLifecycleRule> records = mapper.selectList(qw);
        return Map.of("records", records, "total", total,
                "size", pageSize, "current", pageNum);
    }

    @Override
    public OpsCategoryLifecycleRule getByCategory(String category) {
        LambdaQueryWrapper<OpsCategoryLifecycleRule> qw = new LambdaQueryWrapper<>();
        qw.eq(OpsCategoryLifecycleRule::getCategory, category);
        OpsCategoryLifecycleRule rule = mapper.selectOne(qw);
        if (rule == null) throw BusinessException.notFound("品类 " + category + " 未配置年限规则");
        return rule;
    }

    @Override
    public OpsCategoryLifecycleRule getById(Long id) {
        OpsCategoryLifecycleRule rule = mapper.selectById(id);
        if (rule == null) throw BusinessException.notFound("规则不存在");
        return rule;
    }

    @Override
    @Transactional
    public void create(OpsCategoryLifecycleRule rule) {
        // 品类唯一性校验
        LambdaQueryWrapper<OpsCategoryLifecycleRule> qw = new LambdaQueryWrapper<>();
        qw.eq(OpsCategoryLifecycleRule::getCategory, rule.getCategory());
        if (mapper.selectCount(qw) > 0) {
            throw new BusinessException("品类 " + rule.getCategory() + " 已配置过规则，不允许重复");
        }
        mapper.insert(rule);
    }

    @Override
    @Transactional
    public void update(Long id, OpsCategoryLifecycleRule rule) {
        getById(id); // 校验存在
        // 品类唯一性校验（排除自身）
        LambdaQueryWrapper<OpsCategoryLifecycleRule> qw = new LambdaQueryWrapper<>();
        qw.eq(OpsCategoryLifecycleRule::getCategory, rule.getCategory());
        qw.ne(OpsCategoryLifecycleRule::getId, id);
        if (mapper.selectCount(qw) > 0) {
            throw new BusinessException("品类 " + rule.getCategory() + " 已配置过规则");
        }
        rule.setId(id);
        mapper.updateById(rule);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
    }

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        mapper.deleteBatchIds(ids);
    }
}
