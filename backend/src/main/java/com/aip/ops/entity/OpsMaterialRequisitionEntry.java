package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 物资领用明细。
 *
 * <p>可核销余量 = quantity - written_off_qty - returned_qty；核销审批通过后累加后两者。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_material_requisition_entry")
public class OpsMaterialRequisitionEntry extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long corpId;
    private Long requisitionId;
    private Long materialId;
    private String materialNumber;
    private String materialName;
    private String category;
    private String brand;
    private String model;
    private String unit;
    private Integer quantity;
    private Integer writtenOffQty;
    private Integer returnedQty;
}
