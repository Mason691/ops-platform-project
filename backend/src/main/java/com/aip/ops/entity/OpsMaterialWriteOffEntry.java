package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 物资核销明细。
 *
 * <p>requisition_qty 为领用数量原值；remaining_qty 为创建时的可核销余量快照。
 * 审批通过时按领用明细当前余量再次校验，防止期间被其他核销单消耗。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_material_write_off_entry")
public class OpsMaterialWriteOffEntry extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long corpId;
    private Long writeOffId;
    private Long requisitionEntryId;
    private Long materialId;
    private String materialNumber;
    private String materialName;
    private String category;
    private String brand;
    private String model;
    private String unit;
    private Integer requisitionQty;
    private Integer remainingQty;
    private Integer writeOffQty;
    private Integer returnQty;
}
