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

/**
 * 增值业务退货申请（按商品分录发起，审批通过后同步该订单同产品全部经办人行）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_return")
public class OpsBusinessReturn extends BaseEntity {

    public static final String STATUS_PENDING = "审批中";
    public static final String STATUS_APPROVED = "已通过";
    public static final String STATUS_REJECTED = "已驳回";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    @TableField("order_id")
    private Long orderId;

    @TableField("item_id")
    private Long itemId;

    /** 订单内产品分组键：品类|品牌|型号|单价；审批时据此更新同产品全部经办人行。 */
    @TableField("product_key")
    private String productKey;

    @TableField("return_quantity")
    private Integer returnQuantity;

    @TableField("return_amount")
    private BigDecimal returnAmount;

    @TableField("reason")
    private String reason;

    /** 审批中/已通过/已驳回 */
    @TableField("status")
    private String status;

    @TableField("applicant_user_id")
    private Long applicantUserId;

    @TableField("applicant_name")
    private String applicantName;

    @TableField("applicant_phone")
    private String applicantPhone;

    @TableField("approver_user_id")
    private Long approverUserId;

    @TableField("approver_name")
    private String approverName;

    @TableField("approve_time")
    private LocalDateTime approveTime;

    @TableField("approve_remark")
    private String approveRemark;
}
