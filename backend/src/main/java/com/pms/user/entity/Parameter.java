package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// One row of the "parameters" table: an application setting stored as a key/value string pair.
// Seeded by Flyway; nothing in Java reads it back today (no repository/service/controller exists
// yet) — this entity just keeps the table mapped and checked at boot (ddl-auto: validate) so a
// future reader can be added with one query instead of rediscovering the table from scratch.

/**
 * One application setting, stored as a key and a text value.
 *
 * <p>Id, timestamps, author columns and the soft-delete flag come from BaseEntity.
 */
@Entity
@Table(name = "parameters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Parameter extends BaseEntity {

    // The setting's name. unique = true documents the real constraint uk_parameters_key (Flyway
    // owns the schema, ADR-019). Note it's an ABSOLUTE unique index, not partial: a soft-deleted
    // key can't be reinserted.
    @Column(name = "param_key", nullable = false, unique = true, length = 100)
    private String paramKey;

    // The value, always stored as text; length is generous so a future list/JSON value needs no
    // migration.
    @Column(name = "param_value", nullable = false, length = 500)
    private String paramValue;

    // Plain-language explanation for future readers. The only nullable column — rows from a
    // migration that forgets it must still load.
    @Column(length = 255)
    private String description;
}
