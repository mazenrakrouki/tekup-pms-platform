package com.pms.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.transaction.annotation.EnableTransactionManagement;

// =============================================================================
// FILE: JpaConfig.java
//
// WHAT THIS FILE IS
//   An empty class whose only content is its three annotations. It switches on
//   two pieces of Spring machinery for the whole application: the automatic
//   filling of the audit columns, and the management of database transactions.
//
// WHERE IT SITS IN THE FLOW
//   Spring Boot reads it at start-up, and it then works in the background of
//   every write:
//     service method marked @Transactional
//       -> Spring opens one database transaction (switched on by
//          @EnableTransactionManagement below)
//       -> repository.save(entity)
//       -> AuditingEntityListener, declared on BaseEntity
//          (com.pms.shared.entity), fills created_at / updated_at and asks
//          SpringSecurityAuditorAware (same package as this file) "who is
//          writing?" to fill created_by / updated_by
//       -> the INSERT or UPDATE is sent
//       -> commit, or rollback if anything threw.
//   It calls nothing. It is read, not executed.
//
// WHY IT EXISTS - what would break if you deleted it
//   The class looks empty and it is tempting to think it does nothing. Remove
//   it and two things break at once:
//     1. the four audit fields of BaseEntity stay empty. createdAt there is
//        mapped with nullable = false, and the column created_at is NOT NULL in
//        the Flyway migration V1, so the very first save of any row fails - the
//        application would still start and then refuse to write anything.
//        The "DEFAULT NOW()" written beside that column in V1 does not rescue
//        it: Hibernate names every mapped column in its INSERT and would send
//        an explicit NULL, while a default only applies to a column the INSERT
//        leaves out;
//     2. "who changed this DI line, and when?" becomes unanswerable, because
//        created_by / updated_by (added for every table by V19, audit item H-6)
//        would never be filled.
//   An empty class with annotations is the normal Spring way to say "switch
//   this on for the whole application": there is nowhere else to put these two
//   switches, and keeping them together in a file named JpaConfig is what makes
//   them findable.
// =============================================================================

// @Configuration: Spring reads this class at start-up. Without it the two
// annotations below are just decoration on a class nobody looks at.
@Configuration
// @EnableJpaAuditing switches on the Spring Data auditing machinery: before
// each INSERT or UPDATE it fills the fields marked @CreatedDate,
// @LastModifiedDate, @CreatedBy and @LastModifiedBy in BaseEntity.
//
// auditorAwareRef names the bean that answers "who is writing right now?" -
// here SpringSecurityAuditorAware, in this same package, which reads the
// logged-in user out of the Spring Security context. The name is the bean name
// written as text, so it is NOT checked by the compiler: a typo such as
// "springSecurityAuditorAwre" compiles perfectly and stops the application at
// start-up with "No qualifying bean of type AuditorAware".
// Without auditorAwareRef, the dates would still be filled but created_by and
// updated_by would stay empty, and the audit trail would record every change
// with no author.
@EnableJpaAuditing(auditorAwareRef = "springSecurityAuditorAware")
// @EnableTransactionManagement is what gives @Transactional its meaning: Spring
// wraps each annotated method so that everything it writes is committed
// together, or undone together.
// Why it matters here: a service that writes two tables in one method - say a
// backlog item and the sprint it belongs to - must not be able to save the
// first and lose the second. Without transaction management the @Transactional
// annotations scattered over the services would be ignored in silence, each
// save would stand alone, and a crash in the middle would leave half-written
// business data behind.
// Note: Spring Boot also switches this on by itself. Writing it here is
// explicit rather than necessary - the behaviour of the application does not
// depend on somebody remembering that Boot does it.
@EnableTransactionManagement
public class JpaConfig {
}
