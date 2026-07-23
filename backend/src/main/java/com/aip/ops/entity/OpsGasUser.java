package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_gas_user")
public class OpsGasUser extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long corpId;
    private Long communityId;
    private String batchNo;
    private String userNo;
    private String meterNo;
    private String fullName;                        // sb_gas_user.full_name
    private String phone;
    private String gasSupplyCertificateNumber;      // sb_gas_user.gas_supply_certificate_number
    private String gasCompanyName;                  // sb_gas_user.gas_company_name
    private String areaName;                        // sb_gas_user.area_name
    private String detailedAddress;                 // sb_gas_user.detailed_address
    private String status;
    private BigDecimal gasBalance;                  // sb_gas_user.gas_balance
    private BigDecimal arrears;
    private LocalDateTime accountOpeningTime;       // sb_gas_user.account_opening_time
    private Long platformMemberId;

    /** Transient: resolved community name from ai_platform.sys_community */
    @TableField(exist = false)
    private String communityName;
}
