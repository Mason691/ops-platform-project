package com.aip.ops.entity;

import com.aip.sdk.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务数据订单工作流节点处理留痕。
 *
 * <p>每次节点动作（财务核对/派工出库/施工记录/验收/售后）落一条，
 * 唯一键 {@code (task_id, action_type)} 保证工作流重复回调幂等。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_business_flow_record")
public class OpsBusinessFlowRecord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("corp_id")
    private Long corpId;

    @TableField("order_id")
    private Long orderId;

    @TableField("instance_id")
    private Long instanceId;

    @TableField("task_id")
    private Long taskId;

    @TableField("node_order")
    private Integer nodeOrder;

    @TableField("node_name")
    private String nodeName;

    @TableField("action_type")
    private String actionType;

    @TableField("biz_status")
    private String bizStatus;

    @TableField("operator_user_id")
    private Long operatorUserId;

    @TableField("operator_name")
    private String operatorName;

    @TableField("opinion")
    private String opinion;

    @TableField("form_json")
    private String formJson;

    @TableField("result_json")
    private String resultJson;
}
