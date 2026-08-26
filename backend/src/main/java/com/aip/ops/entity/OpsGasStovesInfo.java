package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 燃具信息 — 外部燃气系统 jinyuan_info.sb_gas_stoves_info 的全量同步镜像。
 * remote_id 为远程主键（去重键），gas_stoves_class 存字典 GAS_APPLIANCE_TYPE itemKey。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_gas_stoves_info")
public class OpsGasStovesInfo extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long corpId;
    private String batchNo;
    private String remoteId;                 // sb_gas_stoves_info.id
    private String sbGasUserId;              // sb_gas_stoves_info.sb_gas_user_id
    private String tGasStovesInfoId;         // sb_gas_stoves_info.t_gas_stoves_info_id
    private String gasStovesClass;           // 字典 GAS_APPLIANCE_TYPE itemKey（1灶具 2热水器 3挂壁炉）
    private String gasStovesBrand;
    private String gasStovesType;
    private Integer power;
    private String focalEye;
    private LocalDateTime installationTime;
    private LocalDateTime startupTime;
    private LocalDateTime buyTime;
    private String installationPosition;     // 1厨房 2过道 3阳台 4其他
    private String userNumber;               // 远程值，对应 sb_gas_user.gas_supply_certificate_number
    private String gasCompanyId;
    private String gasTheCompanyId;
    private String userId;
    private String gasStovesFactory;
    private Integer gasStovesNum;
}
