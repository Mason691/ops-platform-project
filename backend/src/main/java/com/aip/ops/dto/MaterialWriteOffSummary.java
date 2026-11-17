package com.aip.ops.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 物资核销记录列表项。 */
public record MaterialWriteOffSummary(
        Long id,
        String writeOffNo,
        String requisitionNo,
        String status,
        LocalDate writeOffDate,
        String applicantName,
        String materialSummary,
        Integer itemCount,
        Integer writeOffTotal,
        Integer returnTotal,
        LocalDateTime submitTime,
        String wfNodeName
) {}
