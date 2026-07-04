package com.pms.workload.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ChargeReelleResponse(
        Long id,
        Long projectId,
        String projectCode,
        Long userId,
        String userFullName,
        Integer year,
        Integer month,
        BigDecimal actualDays,
        LocalDateTime submittedAt,
        LocalDateTime validatedAt,
        Long validatedById,
        String validatedByName
) {}
