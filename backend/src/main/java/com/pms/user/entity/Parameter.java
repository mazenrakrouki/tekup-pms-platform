package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "parameters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Parameter extends BaseEntity {

    @Column(name = "param_key", nullable = false, unique = true, length = 100)
    private String paramKey;

    @Column(name = "param_value", nullable = false, length = 500)
    private String paramValue;

    @Column(length = 255)
    private String description;
}
