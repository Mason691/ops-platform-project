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
    private String userName;
    private String phone;
    private String area;
    private String address;
    private String status;
    private BigDecimal balance;
    private BigDecimal arrears;
    private LocalDateTime openTime;

    /** Transient: resolved community name from ai_platform.sys_community */
    @TableField(exist = false)
    private String communityName;
}
