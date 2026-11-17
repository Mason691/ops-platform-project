package com.aip.ops.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 物资领用记录列表项。 */
public record MaterialRequisitionSummary(
        Long id,
        String requisitionNo,
        String status,
        LocalDate expectedDate,
        String applicantNames,
        String purpose,
        String materialSummary,
        Integer itemCount,
        Integer totalQty,
        String writeOffStatus,
        LocalDateTime submitTime,
        String wfNodeName
) {}
