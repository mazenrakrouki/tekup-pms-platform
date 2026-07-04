package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "resources")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Resource extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "daily_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyRate;

    @Column(name = "tcc_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal tccRate;

    @Column(name = "staffing_start", nullable = false)
    private LocalDate staffingStart;

    @Column(name = "staffing_end")
    private LocalDate staffingEnd;

    public BigDecimal getAnnualCost() {
        return dailyRate.multiply(tccRate.add(BigDecimal.ONE)).multiply(BigDecimal.valueOf(218));
    }
}
