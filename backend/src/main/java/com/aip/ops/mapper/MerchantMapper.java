package com.aip.ops.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 跨库查询底座平台组织表，获取"销售商"角色组织列表。
 * mall_db 和 ai_platform 在同一 MySQL 实例，使用 ai_platform.sys_corp 跨库访问。
 *
 * 角色筛选不是 sys_corp.org_role_id = 3（那是组织注册角色，非企业角色），
 * 而是通过 sys_corp_role 表，role_name = ${merchantRoleName} 确认该组织拥有销售商企业角色。
 */
@Mapper
public interface MerchantMapper {

    @Select("<script>"
            + "SELECT c.id, c.name, c.contact_name AS contactName, "
            + "       c.contact_phone AS phone, c.address "
            + "FROM ai_platform.sys_corp c "
            + "INNER JOIN ai_platform.sys_corp_role cr "
            + "  ON c.id = cr.corp_id AND cr.delete_flag = 0 AND cr.role_name = #{roleName} "
            + "WHERE c.delete_flag = 0 AND c.status = 1 "
            + "<if test='keyword != null and keyword != \"\"'>"
            + "  AND (c.name LIKE CONCAT('%', #{keyword}, '%') "
            + "       OR c.contact_phone LIKE CONCAT('%', #{keyword}, '%')) "
            + "</if>"
            + "GROUP BY c.id "
            + "ORDER BY c.id "
            + "LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> selectMerchants(@Param("offset") int offset,
                                              @Param("size") int size,
                                              @Param("roleName") String roleName,
                                              @Param("keyword") String keyword);

    /**
     * 批量查询组织名称（用于商品列表中展示所属销售商名称）。
     * 不限定角色，仅通过组织 ID 查询。
     */
    @Select("<script>"
            + "SELECT id, name FROM ai_platform.sys_corp "
            + "WHERE delete_flag = 0 "
            + "AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Map<String, Object>> selectCorpNamesByIds(@Param("ids") java.util.Set<Long> ids);
}
