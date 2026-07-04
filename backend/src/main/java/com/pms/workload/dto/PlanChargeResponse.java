package com.pms.workload.dto;

import java.math.BigDecimal;

public record PlanChargeResponse(
        Long id,
        Long projectId,
        String projectCode,
        Long userId,
        String userFullName,
        Integer year,
        Integer month,
        BigDecimal plannedDays
) {}
