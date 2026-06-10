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
}
