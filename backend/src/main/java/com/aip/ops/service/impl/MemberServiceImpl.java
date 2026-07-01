package com.aip.ops.service.impl;

import com.aip.ops.entity.OpsMember;
import com.aip.ops.mapper.OpsMemberMapper;
import com.aip.ops.service.DataScopeService;
import com.aip.ops.service.MemberService;
import com.aip.sdk.client.PlatformInternalClient;
import com.aip.sdk.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 会员管理服务实现。
 *
 * <p>数据权限：通过 {@link DataScopeService} 限制查询范围：
 * <ul>
 *   <li>超管 → 查全部</li>
 *   <li>运营商 → 自己 + 下属城市合伙人的会员</li>
 *   <li>城市合伙人 → 仅自己的会员</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    private final OpsMemberMapper mapper;
    private final DataScopeService dataScopeService;
    private final PlatformInternalClient platformClient;

    @Override
    public Page<Map<String, Object>> page(int pageNum, int pageSize,
                                          String phone, String startDate, String endDate, Integer status) {
        LambdaQueryWrapper<OpsMember> qw = new LambdaQueryWrapper<>();

        // 数据权限：过滤可见的 corp_id
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        if (visibleCorpIds == null) {
            // 超管：不过滤（null = 全量）
        } else if (visibleCorpIds.isEmpty()) {
            return new Page<>(pageNum, pageSize, 0);
        } else {
            qw.in(OpsMember::getCorpId, visibleCorpIds);
        }

        // 筛选条件
        if (phone != null && !phone.isBlank()) {
            qw.like(OpsMember::getPhone, phone.trim());
        }
        if (status != null) {
            qw.eq(OpsMember::getStatus, status);
        }
        // 注册时间范围 — 基于 createTime（BaseEntity 审计字段）
        if (startDate != null && !startDate.isBlank()) {
            qw.ge(OpsMember::getCreateTime, startDate.trim());
        }
        if (endDate != null && !endDate.isBlank()) {
            qw.le(OpsMember::getCreateTime, endDate.trim() + " 23:59:59");
        }

        qw.orderByDesc(OpsMember::getCreateTime);

        // 分页查询
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<OpsMember> mpPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageNum, pageSize);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<OpsMember> result =
                mapper.selectPage(mpPage, qw);

        // 将 OpsMember 转换为 Map（脱敏，排除密码字段）
        List<Map<String, Object>> records = result.getRecords().stream()
                .map(this::toMap).toList();

        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize);
        page.setRecords(records);
        page.setTotal(result.getTotal());
        return page;
    }

    @Override
    public Map<String, Object> getDetail(Long id) {
        OpsMember member = mapper.selectById(id);
        if (member == null) {
            throw BusinessException.notFound("会员不存在");
        }

        Map<String, Object> detail = toMap(member);

        // 关联底座 sys_member 信息
        if (member.getPlatformMemberId() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = platformClient.get(
                        "/api/internal/member/" + member.getPlatformMemberId(), null);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) resp.get("data");
                if (data != null) {
                    detail.put("platformMember", data);
                }
            } catch (Exception e) {
                log.warn("获取底座会员信息失败: platformMemberId={}, error={}",
                        member.getPlatformMemberId(), e.getMessage());
            }
        }

        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        OpsMember member = mapper.selectById(id);
        if (member == null) {
            throw BusinessException.notFound("会员不存在");
        }

        // 城市合伙人不允许修改会员状态
        Set<Long> visibleCorpIds = dataScopeService.getVisibleCorpIds();
        if (visibleCorpIds != null && !visibleCorpIds.contains(member.getCorpId())) {
            throw BusinessException.forbidden("无权操作该会员");
        }

        member.setStatus(status != null && status == 1 ? 1 : 0);
        mapper.updateById(member);
        log.info("会员状态已更新: id={}, phone={}, status={}", id, member.getPhone(), member.getStatus());
    }

    /**
     * 将 OpsMember 转为安全的 Map（不包含密码）。
     */
    private Map<String, Object> toMap(OpsMember m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("corpId", m.getCorpId());
        map.put("phone", m.getPhone());
        map.put("nickname", m.getNickname());
        map.put("avatar", m.getAvatar());
        map.put("openid", m.getOpenid());
        map.put("unionid", m.getUnionid());
        map.put("pointsBalance", m.getPointsBalance());
        map.put("platformMemberId", m.getPlatformMemberId());
        map.put("status", m.getStatus());
        map.put("lastLoginTime", m.getLastLoginTime());
        map.put("createTime", m.getCreateTime());
        return map;
    }
}
