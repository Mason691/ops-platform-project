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

/**
 * 增值业务订单头（主表）。
 *
 * <p>一条业务订单一行，承载订单级公共信息与合计；商品×经办人明细存
 * {@link OpsBusinessData}（表 ops_business_order_item）。拆分后保留
 * ops_business_data 作为子表的兼容视图，供 SCM / AI 只读。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_order")
public class OpsBusinessOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    /** 订单幂等键：新单为 O|{orderNo}；历史迁移数据按订单编号/业务组号/收据号归组。 */
    @TableField("order_key")
    private String orderKey;

    @TableField("batch_no")
    private String batchNo;

    @TableField("order_no")
    private String orderNo;

    @TableField("business_group_no")
    private String businessGroupNo;

    @TableField("receipt_no")
    private String receiptNo;

    @TableField("company")
    private String company;

    @TableField("customer")
    private String customer;

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

    @TableField("agent_names")
    private String agentNames;

    @TableField("agent_phones")
    private String agentPhones;

    @TableField("receipt_date")
    private String receiptDate;

    @TableField("finance_receipt_date")
    private LocalDate financeReceiptDate;

    @TableField("fund_dynamic_rows")
    private Integer fundDynamicRows;

    @TableField("is_received")
    private String isReceived;

    @TableField("voucher_url")
    private String voucherUrl;

    /** 订单合计金额：订单内 品类+品牌+型号+单价 去重后求和。 */
    @TableField("total_amount")
    private BigDecimal totalAmount;

    /** 订单提成合计：子表 commission_amount 之和（多经办人已均分，合计不变）。 */
    @TableField("total_commission")
    private BigDecimal totalCommission;

    @TableField("calc_status")
    private String calcStatus;

    /** 业务状态（工作流驱动，字典 business_order_status：0~6）。 */
    @TableField("biz_status")
    private String bizStatus;

    /** 当前工作流实例 ID（wf_instance.id，平台工作流组件）。 */
    @TableField("wf_instance_id")
    private Long wfInstanceId;

    @TableField("wf_node_order")
    private Integer wfNodeOrder;

    @TableField("wf_node_name")
    private String wfNodeName;

    @TableField("finance_check_user_id")
    private Long financeCheckUserId;

    @TableField("finance_check_user_name")
    private String financeCheckUserName;

    @TableField("finance_check_time")
    private LocalDateTime financeCheckTime;

    /** 财务核对结论：PASS / REJECT / HOLD。 */
    @TableField("finance_check_conclusion")
    private String financeCheckConclusion;

    @TableField("finance_check_remark")
    private String financeCheckRemark;
}
