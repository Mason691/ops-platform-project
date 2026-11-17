package com.aip.ops.service;

import com.aip.ops.dto.MaterialRequisitionRequest;
import com.aip.ops.dto.MaterialRequisitionSummary;
import com.aip.ops.dto.MaterialRequisitionView;
import com.aip.ops.dto.WorkflowBusinessEvent;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDate;
import java.util.List;

/** 员工物资领用：提交申请、审批流转、记录查询。 */
public interface MaterialRequisitionService {

    MaterialRequisitionView create(MaterialRequisitionRequest request);

    Page<MaterialRequisitionSummary> page(String status, String keyword,
                                          LocalDate dateFrom, LocalDate dateTo,
                                          long pageNum, long pageSize);

    MaterialRequisitionView detail(Long id);

    /** 可核销领用单：当前员工可见、已通过/已发放、未核销完成、无审批中核销单。 */
    List<MaterialRequisitionSummary> pickable(String keyword, LocalDate dateFrom, LocalDate dateTo);

    void handleWorkflowEvent(WorkflowBusinessEvent event);
}
