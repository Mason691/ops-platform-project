package com.aip.ops.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 物资核销详情（含明细、凭证与当前审批人）。 */
public record MaterialWriteOffView(
        Long id,
        String writeOffNo,
        Long requisitionId,
        String requisitionNo,
        String status,
        String applicantName,
        Long deptId,
        String deptName,
        String writeOffUserNames,
        LocalDate writeOffDate,
        String usageDesc,
        String remark,
        List<String> voucherUrls,
        String approvalRemark,
        LocalDateTime approveTime,
        String wfNodeName,
        String currentApproverName,
        String currentApproverRole,
        Boolean approverResolved,
        String approverMessage,
        List<EntryView> entries,
        Integer itemCount,
        Integer writeOffTotal,
        Integer returnTotal
) {
    public record EntryView(Long id, Long requisitionEntryId, Long materialId,
                            String materialNumber, String materialName, String category,
                            String brand, String model, String unit, Integer requisitionQty,
                            Integer remainingQty, Integer writeOffQty, Integer returnQty,
                            String remark) {}
}
