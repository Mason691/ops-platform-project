package com.aip.ops.service;

import com.aip.ops.dto.MaterialWriteOffRequest;
import com.aip.ops.dto.MaterialWriteOffSummary;
import com.aip.ops.dto.MaterialWriteOffView;
import com.aip.ops.dto.WorkflowBusinessEvent;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDate;

/** 员工物资核销：基于已通过领用单核销使用量/退回量，审批通过后回写领用余量。 */
public interface MaterialWriteOffService {

    MaterialWriteOffView create(MaterialWriteOffRequest request);

    Page<MaterialWriteOffSummary> page(String status, String keyword,
                                       LocalDate dateFrom, LocalDate dateTo,
                                       long pageNum, long pageSize);

    MaterialWriteOffView detail(Long id);

    /**
     * 审批人视角详情：调用方必须先通过底座 taskId 校验“当前用户是待办审批人且
     * businessId 匹配”，本方法不再按 write_off_user_ids 过滤。
     */
    MaterialWriteOffView approvalDetail(Long id);

    void handleWorkflowEvent(WorkflowBusinessEvent event);
}
