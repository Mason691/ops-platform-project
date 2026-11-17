package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工物资领用单头。
 *
 * <p>申请制：不绑仓库、不扣库存；提交即进入审批（PENDING），审批通过后可核销。
 * 备注复用 BaseEntity.remark；领用用途单独用 purpose。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_material_requisition")
public class OpsMaterialRequisition extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long corpId;
    private String requisitionNo;
    private Long applicantUserId;
    private String applicantName;
    /** 全部领用人 ID，逗号分隔；FIND_IN_SET 查询员工可见性 */
    private String applicantUserIds;
    private String applicantNames;
    private Long deptId;
    private String deptName;
    private String purpose;
    private LocalDate expectedDate;
    /** PENDING/APPROVED/REJECTED/ISSUED */
    private String status;
    private String processInstanceId;
    private Integer wfNodeOrder;
    private String wfNodeName;
    private String approvalRemark;
    private LocalDateTime approveTime;
    /** NONE/PARTIAL/COMPLETED */
    private String writeOffStatus;
}
