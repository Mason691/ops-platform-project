package com.aip.ops.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 物资领用详情（含明细与当前审批人）。 */
public record MaterialRequisitionView(
        Long id,
        String requisitionNo,
        String status,
        Long applicantUserId,
        String applicantName,
        String applicantNames,
        Long deptId,
        String deptName,
        String purpose,
        LocalDate expectedDate,
        String remark,
        String writeOffStatus,
        LocalDateTime submitTime,
        String approvalRemark,
        LocalDateTime approveTime,
        String wfNodeName,
        String currentApproverName,
        String currentApproverRole,
        Boolean approverResolved,
        String approverMessage,
        List<EntryView> entries,
        Integer itemCount,
        Integer totalQty
) {
    public record EntryView(Long id, Long materialId, String materialNumber, String materialName,
                            String category, String brand, String model, String unit,
                            Integer quantity, Integer writtenOffQty, Integer returnedQty,
                            Integer remainingQty, String remark) {}
}
