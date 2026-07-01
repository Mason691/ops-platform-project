package com.aip.ops.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/**
 * 会员管理服务 — 查询 ops_member 并关联底座 sys_member 信息。
 */
public interface MemberService {

    /**
     * 分页查询会员列表。
     *
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param phone     手机号模糊搜索（可选）
     * @param startDate 注册起始时间（可选）
     * @param endDate   注册截止时间（可选）
     * @param status    账号状态（可选，null=全部）
     */
    Page<Map<String, Object>> page(int pageNum, int pageSize,
                                   String phone, String startDate, String endDate, Integer status);

    /**
     * 查询会员详情，包含底座 sys_member 信息。
     */
    Map<String, Object> getDetail(Long id);

    /**
     * 修改会员启用/禁用状态。
     */
    void updateStatus(Long id, Integer status);
}
