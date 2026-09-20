package com.pms.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.transaction.annotation.EnableTransactionManagement;

// Empty class carrying two switches for the whole app: JPA auditing (fills BaseEntity's
// created_at/by, updated_at/by) and transaction management. Without auditing, createdAt is
// NOT NULL in Flyway's V1 and the first save of any row would fail.

@Configuration
// auditorAwareRef is a bean name as text, not compiler-checked: a typo compiles fine and only
// fails at start-up. Without it, dates get filled but created_by/updated_by stay empty.
@EnableJpaAuditing(auditorAwareRef = "springSecurityAuditorAware")
// Gives @Transactional its meaning (commit/rollback together). Boot enables this by itself
// too; kept explicit here so it doesn't depend on someone remembering that.
@EnableTransactionManagement
public class JpaConfig {
}
