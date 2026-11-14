package com.aip.ops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

/**
 * 平台工作流 → ops 业务回调事件。
 *
 * <p>event：
 * <ul>
 *   <li>NODE_ACTION：节点提交前的业务动作（同步执行，失败则不推进审批）；</li>
 *   <li>NODE_ENTERED：进入节点，写入 bizStatus；</li>
 *   <li>INSTANCE_COMPLETED / INSTANCE_REJECTED / INSTANCE_TERMINATED：实例结束事件。</li>
 * </ul>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowBusinessEvent {

    private String event;
    private String businessType;
    private String businessId;
    private String businessSummary;
    private Long instanceId;
    private String definitionCode;
    private Long nodeId;
    private Integer nodeOrder;
    private String nodeName;
    private String bizStatus;
    private String actionType;
    private Long taskId;
    private Long operatorUserId;
    private String operatorName;
    private String opinion;
    private Map<String, Object> formData;
    private String timestamp;
}
