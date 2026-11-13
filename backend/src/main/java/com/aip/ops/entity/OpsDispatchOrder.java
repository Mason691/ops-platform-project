package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 派工单主表。
 *
 * <p>由运营/财务人员在业务数据订单上发起，指派组织员工上门施工或入户随访。
 * 员工接单后由 SCM 内部接口生成一张未审核的销售出库单，出库单 ID 回写
 * {@link #stockOutId}，形成「财务核对 → 派工 → 接单 → 出库」闭环。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_dispatch_order")
public class OpsDispatchOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 派工单号：{公司代码}-PG-{yyyyMMdd}-{3位流水}，如 DB-PG-20251113-001。 */
    @TableField("dispatch_no")
    private String dispatchNo;

    @TableField("source_order_id")
    private Long sourceOrderId;

    @TableField("source_order_no")
    private String sourceOrderNo;

    /** 来源订单经办人（仅展示/追溯；多个用「、」连接，不参与权限判断）。 */
    @TableField("source_agent_names")
    private String sourceAgentNames;

    @TableField("receipt_no")
    private String receiptNo;

    @TableField("corp_id")
    private Long corpId;

    /** CONSTRUCTION=施工 / VISIT=入户随访。 */
    @TableField("dispatch_type")
    private String dispatchType;

    @TableField("customer_name")
    private String customerName;

    @TableField("customer_phone")
    private String customerPhone;

    @TableField("platform_member_id")
    private Long platformMemberId;

    @TableField("community_id")
    private Long communityId;

    @TableField("community_name")
    private String communityName;

    @TableField("detailed_address")
    private String detailedAddress;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("expect_time")
    private LocalDateTime expectTime;

    @TableField("assignee_user_id")
    private Long assigneeUserId;

    @TableField("assignee_name")
    private String assigneeName;

    @TableField("assignee_username")
    private String assigneeUsername;

    /** PENDING/ACCEPTING(内部)/ACCEPTED/PROCESSING/COMPLETED/REJECTED/CANCELLED。 */
    @TableField("status")
    private String status;

    @TableField("dispatch_by")
    private Long dispatchBy;

    @TableField("dispatch_by_name")
    private String dispatchByName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("dispatch_time")
    private LocalDateTime dispatchTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("accept_time")
    private LocalDateTime acceptTime;

    /** 接单处理中锁定时间：状态 ACCEPTING 时非空，防取消/接单竞态。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("accept_lock_time")
    private LocalDateTime acceptLockTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("start_time")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("complete_time")
    private LocalDateTime completeTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("reject_time")
    private LocalDateTime rejectTime;

    @TableField("reject_reason")
    private String rejectReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("cancel_time")
    private LocalDateTime cancelTime;

    @TableField("cancel_reason")
    private String cancelReason;

    /** 接单后回写的 SCM 销售出库单 ID。 */
    @TableField("stock_out_id")
    private Long stockOutId;

    @TableField("stock_out_no")
    private String stockOutNo;

    /** 最近一次公众号消息推送状态：SUCCESS/FAILED/SKIPPED。 */
    @TableField("message_status")
    private String messageStatus;

    @TableField("message_error")
    private String messageError;

    /** 派发人修改客户信息时的原始值留痕（JSON）。 */
    @TableField("source_snapshot")
    private String sourceSnapshot;
}
