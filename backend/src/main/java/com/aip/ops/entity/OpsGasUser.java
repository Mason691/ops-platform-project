package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_gas_user")
public class OpsGasUser extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long corpId;
    /** 组织匹配状态：1=已匹配（corp_id 来自 sys_corp.gas_company_id 匹配） 0=未匹配异常（corp_id=0，待人工处理） */
    private Integer corpMatchStatus;
    private Long communityId;
    private String batchNo;
    private String userNo;
    private String meterNo;
    private String fullName;                        // sb_gas_user.full_name
    private String gender;                           // 性别（身份证解析）
    private LocalDate birthDate;                     // 出生日期（身份证解析）
    private String phone;
    private String gasSupplyCertificateNumber;      // sb_gas_user.gas_supply_certificate_number
    private String gasCompanyName;                  // sb_gas_user.gas_company_name
    private String gasCompanyId;                    // sb_gas_user.gas_company_id（供灶具按公司+供气证号关联）
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

    /** Transient: 组织简称（平台批量接口解析；corp_id=0 未匹配行不解析） */
    @TableField(exist = false)
    private String corpName;

    // ── 计算字段（Transient）──
    @TableField(exist = false)
    private Integer age;                // 年龄（根据 birth_date 计算）
    @TableField(exist = false)
    private Integer accountYears;       // 开户年数
    @TableField(exist = false)
    private Boolean isMember;           // 是否会员
    @TableField(exist = false)
    private String maskedPhone;         // 脱敏手机号
    @TableField(exist = false)
    private Integer deviceCount;        // 名下燃气设备数（ops_gas_stoves_info.gas_user_id，列表富化）
}
