package com.pms;

// Entry point of the PMS Spring Boot backend (Spring Boot 3.3.6, Java 21). Starting it starts
// Tomcat, the PostgreSQL pool, the Flyway migrations (V1..V28) and every bean in the project.
// Its package (com.pms) matters as much as its code: component scanning starts here and only
// goes downwards, so moving this class would stop Spring from finding beans in sibling packages.
// No security rule or token logic lives here; that is in com.pms.auth and in each service.

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Boot class of the PMS backend. Holds no business logic on purpose — everything it does comes
 * from the three annotations below, each switching on a Spring feature the rest of the code relies on.
 */
// @SpringBootApplication = @Configuration + @ComponentScan (com.pms and below) + @EnableAutoConfiguration
// (Tomcat, HikariCP, Flyway, Hibernate, based on what's on the classpath). Without it nothing is
// scanned or configured and the process exits immediately.
@SpringBootApplication
// Enables Spring's CacheManager bean (Caffeine, sized in application.yml). Used directly by
// JwtAuthenticationFilter/AuthService/UserCrudService/RoleAdminService to cache each user's
// security context (ADR-017) rather than re-reading permissions from the DB on every request.
@EnableCaching
// Enables @Async methods to run on a background thread pool. Nothing in com.pms uses @Async yet,
// but removing this would silently make any future @Async method run on the request thread instead.
@EnableAsync
public class PmsApplication {

    /**
     * Starts the Spring context and returns; the process stays alive because Tomcat keeps
     * non-daemon threads running, not because this method blocks.
     *
     * @param args passed straight to Spring Boot, e.g. --spring.profiles.active=prod, since
     *             application.yml sets no active profile on purpose.
     */
    public static void main(String[] args) {
        // Flyway (V1..V28) runs before Hibernate, which is ddl-auto=validate (ADR-019: Flyway
        // owns the schema). Without this order, startup fails on schema mismatches.
        SpringApplication.run(PmsApplication.class, args);
    }
}
