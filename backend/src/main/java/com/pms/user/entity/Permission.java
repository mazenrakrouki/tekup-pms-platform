package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "permissions",
       uniqueConstraints = @UniqueConstraint(name = "uk_permissions_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String code;    // ex. "ASSIGN_DEVELOPER"

    @Column(nullable = false, length = 50)
    private String module;  // ex. "PROJET"

    @Column(length = 255)
    private String description;
}
