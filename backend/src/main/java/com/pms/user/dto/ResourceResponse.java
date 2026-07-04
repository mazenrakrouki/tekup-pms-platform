package com.pms.user.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ResourceResponse(
        Long id,
        Long userId,
        String userFullName,
        BigDecimal dailyRate,
        BigDecimal tccRate,
        BigDecimal annualCost,
        LocalDate staffingStart,
        LocalDate staffingEnd
) {}
