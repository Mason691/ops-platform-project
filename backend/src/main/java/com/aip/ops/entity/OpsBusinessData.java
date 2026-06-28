package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_data")
public class OpsBusinessData extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("batch_no") private String batchNo;
    @TableField("company") private String company;
    @TableField("customer") private String customer;
    @TableField("brand") private String brand;
    @TableField("category") private String category;
    @TableField("model") private String model;
    @TableField("sale_price") private BigDecimal salePrice;
    @TableField("sale_date") private String saleDate;
    @TableField("receipt_date") private String receiptDate;
    @TableField("receipt_no") private String receiptNo;
    @TableField("agent") private String agent;
    @TableField("retail_floor_price") private BigDecimal retailFloorPrice;
    @TableField("group_lowest_price") private BigDecimal groupLowestPrice;
    @TableField("commission_rate") private BigDecimal commissionRate;
    @TableField("commission_amount") private BigDecimal commissionAmount;
    @TableField("calc_status") private String calcStatus;
    @TableField("remark") private String remark;
    @TableField("operator_id") private Long operatorId;
}
