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
 * 订单施工流转日志：派工 → 待接受 → 已接受 → 已上门 → 客户确认 → 施工中。
 *
 * <p>每一步一条，含操作人、时间、定位、现场照片与备注；只增不改，
 * 是施工责任界定的审计依据。拒绝原因使用 {@link BaseEntity#getRemark()}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_order_construct_log")
public class OpsOrderConstructLog extends BaseEntity {

    public static final String ACTION_ASSIGN = "ASSIGN";
    public static final String ACTION_ACCEPT = "ACCEPT";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_ARRIVE = "ARRIVE";
    public static final String ACTION_CUSTOMER_CONFIRM = "CUSTOMER_CONFIRM";
    public static final String ACTION_CUSTOMER_REFUSE = "CUSTOMER_REFUSE";
    public static final String ACTION_START = "START";
    public static final String ACTION_SUBMIT_ACCEPTANCE = "SUBMIT_ACCEPTANCE";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    @TableField("order_id")
    private Long orderId;

    @TableField("order_no")
    private String orderNo;

    @TableField("from_status")
    private String fromStatus;

    @TableField("to_status")
    private String toStatus;

    /** ASSIGN/ACCEPT/REJECT/ARRIVE/CUSTOMER_CONFIRM/CUSTOMER_REFUSE/START。 */
    @TableField("action")
    private String action;

    @TableField("operator_id")
    private Long operatorId;

    @TableField("operator_name")
    private String operatorName;

    /** 0=内部用户 1=外部施工员。 */
    @TableField("operator_type")
    private Integer operatorType;

    @TableField("operate_time")
    private LocalDateTime operateTime;

    @TableField("location_lng")
    private BigDecimal locationLng;

    @TableField("location_lat")
    private BigDecimal locationLat;

    @TableField("location_address")
    private String locationAddress;

    /** SUCCESS / FAILED / MANUAL。 */
    @TableField("location_status")
    private String locationStatus;

    /** 现场照片附件ID，逗号分隔。 */
    @TableField("attachment_ids")
    private String attachmentIds;

    /** 客户告知书ID（CUSTOMER_CONFIRM 动作）。 */
    @TableField("notice_id")
    private Long noticeId;

    /** 派工指派的目标施工员（ASSIGN 动作）：操作人仍是派工人，施工员单独留痕。 */
    @TableField("worker_id")
    private Long workerId;

    @TableField("worker_name")
    private String workerName;

    @TableField("worker_phone")
    private String workerPhone;
}
