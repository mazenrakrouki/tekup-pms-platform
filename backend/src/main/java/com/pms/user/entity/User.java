package com.pms.user.entity;

import com.pms.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// =============================================================================
// FILE: User.java
//
// WHAT THIS FILE IS
//   The account a person signs in with. One Java object here = one row of the
//   "users" table. It holds the login e-mail, the hashed password, the on/off
//   switch of the account, and the single Role that decides what the person is
//   allowed to do.
//
// WHERE IT SITS IN THE FLOW
//   Who loads it:  UserRepository (queries such as findActiveByEmailWithRole).
//   Who reads it:
//     - UserDetailsServiceImpl and JwtAuthenticationFilter walk
//       user.getRole().getPermissions() and turn every permission code into a
//       Spring Security "authority". Those authorities are exactly what
//       @PreAuthorize("hasAuthority('X')") on the SERVICE methods tests.
//     - JwtService copies the e-mail, firstLogin and tokenVersion into the JWT.
//     - UserMapper / ProjectMapper / MissionMapper copy a few safe fields into
//       DTOs (a DTO is a small flat object sent to the browser). They never
//       send this object itself, because it carries the password hash.
//   Who writes to it:  UserCrudService (create, activate, reset password) and
//     AuthService (login, refresh, logout, first password change).
//
// WHY IT EXISTS
//   Delete it and there is no sign-in at all: no password to compare, no role
//   to read, therefore no authority list, therefore every @PreAuthorize in the
//   whole application would refuse every call.
//
// ADR-022 — why User and Resource are two separate entities.
//   A User is the ACCOUNT (who can log in). What a person COSTS the company
//   (daily rate, TCC rate, staffing dates) lives in Resource, in this same
//   folder. They are deliberately not merged: an assistant needs an account but
//   has no billable rate, and a rate must keep existing for past cost
//   calculations even after the account has been switched off.
//
// Soft delete: the "deleted" flag comes from BaseEntity. Rows are never really
// removed; every repository query in this project adds "deleted = false" by
// hand. So a deleted user still occupies its e-mail in the table — that is why
// the unique rule on the e-mail column is a PARTIAL one (see the email field).
// =============================================================================

/**
 * A user account.
 *
 * <p>Why so few fields: everything that is common to all tables (id, created
 * date, modified date, who created it, the soft-delete flag) is inherited from
 * BaseEntity, which carries the JPA auditing listener (ADR-009). Repeating
 * those five columns in twenty entities would be twenty chances to forget one.
 *
 * <p>Why the class holds two small methods (revokeAllTokens, getFullName)
 * instead of being a pure bag of fields: both are rules about the user, not
 * about one screen. Putting "increase the token version" in the entity means
 * the four places that log a person out cannot each invent their own version of
 * the rule.
 */
// @Entity tells JPA/Hibernate that this class is mapped to a database table.
// Without it, Hibernate ignores the class and every query on User fails at
// startup with "Unknown entity: com.pms.user.entity.User".
@Entity
// @Table pins the table name to "users". Why it is needed: the default name
// derived from the class would be "user", and USER is a reserved word in
// PostgreSQL, so "SELECT ... FROM user" would fail with a syntax error.
@Table(name = "users")
// Lombok writes the getters and setters at compile time. Hibernate and
// MapStruct both need real getUser.../setUser... methods to read and fill the
// object; without them MapStruct would generate an empty mapper.
@Getter
@Setter
// @NoArgsConstructor is required by JPA: Hibernate builds the object with the
// empty constructor and then fills the fields. Without it, loading a user
// throws "No default constructor for entity".
@NoArgsConstructor
// @AllArgsConstructor exists only so that @Builder has a constructor to call.
@AllArgsConstructor
// @Builder gives User.builder().firstName("...").build(). Why: the all-args
// constructor takes ten values in a fixed order, and swapping firstName and
// lastName by mistake would compile silently. The builder names every value.
@Builder
public class User extends BaseEntity {

    // length = 100 is not decoration: it makes Hibernate expect VARCHAR(100),
    // which is what V1__schema_auth.sql created. The application starts with
    // ddl-auto: validate, so a mismatch here stops the boot with a clear error
    // instead of failing later on one long name.
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    // Note what is NOT written here: no unique = true. The rule "no two active
    // accounts with the same e-mail" is enforced by the database, not by JPA.
    // V1 created a plain UNIQUE constraint; V18 replaced it with a PARTIAL
    // unique index: UNIQUE INDEX uk_users_email ON users(email) WHERE deleted =
    // FALSE. Why partial: this project soft-deletes, so the row of a removed
    // account stays in the table. With an absolute UNIQUE, deleting
    // jean@s2i.tn and then re-creating the same person would be refused with
    // "duplicate key", while the admin sees no such account on screen.
    @Column(nullable = false, length = 255)
    private String email;

    // Stores the BCrypt hash, never the password itself. BCrypt is a one-way
    // function: you can check a password against the hash, but you cannot read
    // the password back out of it. UserCrudService calls
    // passwordEncoder.encode(...) before filling this field.
    // Why it matters: if the database is ever copied by someone who should not
    // have it, the accounts still cannot be used. This is also the reason no
    // mapper in the project ever copies this field into a response DTO.
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    // false = the account is switched off. UserDetailsServiceImpl passes
    // accountLocked(!active) to Spring Security, so an inactive person is
    // refused at login even with the right password.
    // @Builder.Default: Lombok's builder IGNORES the "= true" initializer
    // unless this annotation is present. Without it, every account created
    // through User.builder()...build() would come out with active = false, and
    // the new colleague could never sign in.
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // true = the person has never chosen their own password; they still have
    // the one the admin generated. JwtService copies this into the token as the
    // "firstLogin" claim (a claim is one named value carried inside the JWT),
    // and FirstLoginFilter then blocks every endpoint except the
    // change-password one. AuthService sets it to false once the change
    // succeeds.
    // @Builder.Default again: without it the builder would produce firstLogin =
    // false, the filter would let the account through, and the generated
    // password the admin sent by e-mail would stay valid forever.
    @Column(name = "first_login", nullable = false)
    @Builder.Default
    private boolean firstLogin = true;

    // The revocation counter of ADR-017. Every token JwtService signs carries
    // the value this field had at signing time. JwtAuthenticationFilter
    // compares the value inside the token with the value in the database and
    // refuses the request when they differ.
    // Why a counter and not a list of revoked tokens: JWTs are stateless, so
    // there is nothing to delete server-side. One integer per user revokes
    // every token of that user at once, without a blacklist table to clean.
    // @Builder.Default: an int already starts at 0, so the value is the same
    // either way here; the annotation keeps the three defaults of this class
    // consistent and silences Lombok's warning that it ignores the "= 0".
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private int tokenVersion = 0;

    // One user has exactly one role; one role is shared by many users.
    // fetch = EAGER means Hibernate reads the role row in the same trip as the
    // user. Why EAGER here: the file application.yml sets open-in-view: false,
    // so the database session is already closed when MapStruct builds the HTTP
    // response. UserMapper reads role.name at that moment; with LAZY it would
    // throw LazyInitializationException on the users list screen. Role.permissions
    // is EAGER for the same reason, so loading a user brings back the complete
    // chain user -> role -> permissions, which is exactly the authority list
    // that @PreAuthorize needs.
    // nullable = false on role_id: there is no such thing as a user without a
    // role. A null role would mean an empty authority list, and the person
    // would be silently locked out of every screen instead of being refused
    // with a clear error.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    /**
     * Invalidates every token this user currently holds, by moving the counter
     * one step forward.
     *
     * <p>Returns nothing; it only changes the in-memory object. The new value
     * reaches the database when the surrounding @Transactional service method
     * commits (AuthService on refresh, on logout and on password change;
     * UserCrudService when an account is deactivated).
     *
     * <p>Why a method on the entity instead of user.setTokenVersion(x + 1) at
     * each call site: the rule "revoking = +1, never a fixed value" is written
     * once. A caller that wrote setTokenVersion(1) would re-open every token
     * that was signed when the counter was at 1.
     */
    public void revokeAllTokens() {
        this.tokenVersion++;
    }

    /**
     * Gives back the display name, first name then last name, separated by one
     * space.
     *
     * <p>There is no full_name column in the database — the value is built on
     * the fly. Why it is not stored: a stored copy would have to be rewritten
     * every time somebody's name is corrected, and the two columns would
     * eventually disagree.
     *
     * <p>Consequence to remember when reading the mappers: a query that does
     * not JOIN FETCH the user cannot call this method after the transaction
     * closes, because firstName and lastName are real columns that have to be
     * loaded first.
     */
    public String getFullName() {
        return firstName + " " + lastName;
    }
}
