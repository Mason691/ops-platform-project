package com.aip.ops.dto;

import java.util.List;

/** 物资领用制单选项：员工/组织/部门 + 供应链商品类别。 */
public record MaterialOptionsView(
        Long corpId,
        String corpName,
        String company,
        Long employeeUserId,
        String employeeName,
        String employeePhone,
        Long deptId,
        String deptName,
        List<EmployeeView> employees,
        List<CategoryView> categories,
        String productError
) {
    public record EmployeeView(Long userId, String name, String phone,
                               Long deptId, String deptName, boolean isSelf) {}

    public record CategoryView(String key, String name) {}
}
