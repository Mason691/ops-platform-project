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
 * 业务订单作废退款申请。
 *
 * <p>一张订单同一时间只允许一条 PENDING；终审通过后订单置为业务状态 -1，
 * 退款记录保留审批金额/原因/附件与流程实例，供审计与列表展示。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_refund")
public class OpsBusinessRefund extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_TERMINATED = "TERMINATED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    @TableField("order_id")
    private Long orderId;

    /** 退款申请单号（唯一）。 */
    @TableField("refund_no")
    private String refundNo;

    /** 退款原因（必填）。 */
    @TableField("reason")
    private String reason;

    /** 退款金额，默认订单应付合计，不得超过。 */
    @TableField("amount")
    private BigDecimal amount;

    /** 补充附件 URL / ID（逗号分隔）。 */
    @TableField("attachment_urls")
    private String attachmentUrls;

    @TableField("attachment_ids")
    private String attachmentIds;

    /** PENDING / APPROVED / REJECTED / TERMINATED。 */
    @TableField("status")
    private String status;

    /** 申请时订单业务状态，驳回/终止时恢复。 */
    @TableField("original_biz_status")
    private String originalBizStatus;

    @TableField("wf_instance_id")
    private Long wfInstanceId;

    @TableField("wf_node_order")
    private Integer wfNodeOrder;

    @TableField("wf_node_name")
    private String wfNodeName;

    @TableField("applicant_user_id")
    private Long applicantUserId;

    @TableField("applicant_name")
    private String applicantName;

    @TableField("applicant_phone")
    private String applicantPhone;

    /** 驳回/终止原因。 */
    @TableField("reject_reason")
    private String rejectReason;

    @TableField("approved_time")
    private LocalDateTime approvedTime;

    // 退款备注使用 BaseEntity.remark（ops_business_refund.remark 列）
}
