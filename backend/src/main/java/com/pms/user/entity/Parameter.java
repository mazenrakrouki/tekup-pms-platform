package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// =============================================================================
// FILE: Parameter.java
//
// WHAT THIS FILE IS
//   One row of the "parameters" table: a setting of the application stored as a
//   pair of strings, a key and a value. For example the key "CURRENCY" with the
//   value "TND".
//
// WHERE IT SITS IN THE FLOW
//   Created by:  Flyway, not by Java. V3__schema_user_resource.sql creates the
//     table and inserts the three rows the platform ships with:
//       TCC_DEFAULT_RATE = 0.42        (default overhead coefficient, 42%)
//       CURRENCY         = EUR         (V26 changes it to TND for the demo)
//       FISCAL_YEAR_START= 01-01       (start of the fiscal year, MM-DD)
//   Read by:  nothing in Java today. Be honest about this in front of the jury:
//     there is no ParameterRepository, no service and no controller for this
//     entity. The three values above are decided in code and in the SQL seeds,
//     not read back from this table at runtime.
//
// WHY IT EXISTS
//   It is the mapped half of a settings table that is already in the database.
//   Keeping the entity means the table has a Java face ready for the day a
//   setting has to be changed without a new release: add a repository, read
//   the key, done. Deleting the file would compile and the application would
//   behave exactly the same, but the table would become an orphan that nobody
//   can see from the code.
//   Note the side effect of keeping it: the application starts with
//   ddl-auto: validate (ADR-019 - Flyway owns the schema), so Hibernate checks
//   at boot that the "parameters" table and its three columns really exist. A
//   migration that dropped a column would be caught at startup instead of much
//   later.
//
// WHY A KEY/VALUE TABLE AND NOT ONE COLUMN PER SETTING
//   Adding a setting here is one INSERT. With one column per setting, every new
//   setting would need a migration, an entity change and a redeploy.
//   The price paid for that flexibility: the value is always a String, so the
//   reader has to convert it ("0.42" -> BigDecimal) and nothing in the database
//   prevents someone from storing "forty-two percent" in TCC_DEFAULT_RATE.
// =============================================================================

/**
 * One application setting, stored as a key and a text value.
 *
 * <p>Everything common to the tables of this project - the id, the created and
 * updated dates, who created and updated the row, and the soft-delete flag -
 * comes from BaseEntity. That is why only three fields are declared here.
 */
// @Entity maps this class to a database table. Without it Hibernate treats the
// class as an ordinary object, and any future "SELECT p FROM Parameter p" would
// fail at startup with "Unknown entity: com.pms.user.entity.Parameter".
@Entity
// @Table pins the table name to "parameters". The name derived from the class
// would be "parameter" in the singular, which is not the table Flyway created,
// so without this line the boot-time validation would stop the application.
@Table(name = "parameters")
// Lombok writes the getters and setters at compile time. Hibernate needs real
// getXxx/setXxx methods to read the object and to fill it after a SELECT.
@Getter
@Setter
// JPA requires an empty constructor: Hibernate builds the object first and puts
// the column values in afterwards. Without it, loading a row throws
// "No default constructor for entity".
@NoArgsConstructor
// Exists only to give @Builder a constructor to call.
@AllArgsConstructor
// @Builder gives Parameter.builder().paramKey("CURRENCY").paramValue("TND")...
// Why: the all-args constructor takes three strings in a row, and passing the
// value where the key is expected would compile without a warning. The builder
// names each one.
@Builder
public class Parameter extends BaseEntity {

    // The name of the setting, for example "CURRENCY".
    //
    // nullable = false: a row with no key could never be found again.
    // unique = true: it declares that one key appears only once. The real rule
    // is the constraint uk_parameters_key created by V3; this line does not
    // create it (Flyway owns the schema, ADR-019), it documents it and makes
    // the Java mapping agree with the database. Why the rule is needed: two
    // rows both called CURRENCY would make "the currency of the platform"
    // ambiguous, and a lookup by key would return whichever row the database
    // happened to read first.
    // length = 100 must match VARCHAR(100) in V3, otherwise the boot-time
    // validation fails with a clear message instead of a truncation error later.
    //
    // Careful, one trap: uk_parameters_key is an ABSOLUTE unique constraint, not
    // a partial one like uk_users_email. This project soft-deletes, so a row
    // marked deleted still holds its key. Soft-deleting CURRENCY and inserting
    // CURRENCY again would be refused with a duplicate-key error.
    @Column(name = "param_key", nullable = false, unique = true, length = 100)
    private String paramKey;

    // The value, always as text: "0.42", "TND", "01-01". 500 characters is
    // generous on purpose, so a setting that one day holds a list or a short
    // JSON string does not need a migration.
    // nullable = false: a setting with no value is not a setting. Forcing the
    // caller to write an empty string makes "deliberately empty" visible,
    // while a null would look like "we forgot to fill it in".
    @Column(name = "param_value", nullable = false, length = 500)
    private String paramValue;

    // Plain-language explanation of what the setting does, for the person who
    // opens the table in six months. It is the only column here that may be
    // null: V3 declares it without NOT NULL, so the mapping must allow null too
    // or Hibernate would refuse rows that are perfectly legal.
    @Column(length = 255)
    private String description;
}
