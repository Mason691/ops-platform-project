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
 * 员工物资核销单头。
 *
 * <p>核销只能针对已通过审批的领用单；同一领用单同时只允许一张 PENDING 核销单。
 * 审批通过后回写领用明细的已核销/已退回数量。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_material_write_off")
public class OpsMaterialWriteOff extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long corpId;
    private String writeOffNo;
    private Long requisitionId;
    private String requisitionNo;
    private Long applicantUserId;
    private String applicantName;
    private Long deptId;
    private String deptName;
    private Long writeOffUserId;
    private String writeOffUserName;
    private String writeOffUserIds;
    private String writeOffUserNames;
    private LocalDate writeOffDate;
    private String usageDesc;
    /** 凭证 URL 的 JSON 数组 */
    private String voucherUrls;
    /** PENDING/APPROVED/REJECTED */
    private String status;
    private String processInstanceId;
    private Integer wfNodeOrder;
    private String wfNodeName;
    private String approvalRemark;
    private LocalDateTime approveTime;
}
