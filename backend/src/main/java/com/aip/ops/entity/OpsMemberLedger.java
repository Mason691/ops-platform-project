package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_member_ledger")
public class OpsMemberLedger extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long corpId;
    private Long memberId;
    /** 平台会员ID（ai_platform.sys_member.id），安检设备台账自动填充 */
    private Long platformMemberId;
    /** 来源安检设备ID（ops_gas_stoves_info.id），去重键 */
    private String gasStovesId;
    private Long activityId;
    private Long communityId;
    private String category;
    private LocalDate usageDate;
    private String productName;
    private String brand;
    private String model;
    private String grade;
    private java.math.BigDecimal salesPrice;
    private String receiptNo;
    private LocalDate receiptDate;
    private String agent;
    private String remark;

    /** Transient: resolved names for display */
    @TableField(exist = false)
    private String memberName;
    /** 会员手机号（gas_user.phone，会员唯一识别） */
    @TableField(exist = false)
    private String memberPhone;
    @TableField(exist = false)
    private String communityName;
    /** 区县地区（小区所属区县名，sys_region.region_name） */
    @TableField(exist = false)
    private String regionName;
    @TableField(exist = false)
    private String gridManagerName;
    @TableField(exist = false)
    private String activityTitle;
}
