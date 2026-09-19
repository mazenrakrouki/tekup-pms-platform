package com.pms;

// =============================================================================
// PmsApplication.java - the one and only entry point of the PMS backend.
//
// WHAT THIS FILE IS
//   The main class of the Spring Boot application (Spring Boot 3.3.6 on Java
//   21, see backend/pom.xml). Starting it starts the whole server: the embedded
//   Tomcat web server, the PostgreSQL connection pool, the Flyway database
//   migrations (V1 .. V28) and every bean of the project.
//   ("Bean" = an object that Spring builds once and keeps for you, so that other
//   classes just ask for it in their constructor instead of calling "new".)
//
// WHERE IT SITS IN THE FLOW
//   Who calls it: the JVM (java -jar target/pms-backend-0.0.1-SNAPSHOT.jar),
//     Maven (mvnw spring-boot:run), the container built from backend/Dockerfile
//     (it copies that same jar to /app/app.jar and runs java -jar /app/app.jar),
//     and the Spring Boot test runner - the @SpringBootTest classes under
//     src/test/java start their context from THIS class, which they find by
//     walking up the package tree. Nothing in the application code calls it.
//   What it calls next: SpringApplication.run(). That one call reads
//     src/main/resources/application.yml (plus application-dev.yml or
//     application-prod.yml), scans the package com.pms and every package under
//     it (auth, user, project, billing, agile, kpi, mission, governance, team,
//     workload, shared), builds every @Component / @Service / @Repository /
//     @RestController it finds there, runs Flyway, then opens the HTTP port.
//
// WHY IT EXISTS - what would break if you deleted it
//   Without this class there is no entry point: nothing starts, no REST endpoint
//   answers, no migration runs. Its PACKAGE matters just as much as its code.
//   Component scanning starts at the package of this class (com.pms) and only
//   goes downwards. Example of what goes wrong: move this file into
//   com.pms.auth and Spring finds only the auth beans; ProjectService in
//   com.pms.project is never built and startup fails with "required a bean of
//   type com.pms.project.service.ProjectService that could not be found".
//
// WHAT IS DELIBERATELY *NOT* IN THIS FILE
//   No security rule is written here. Authorization in PMS is dynamic and
//   permission-based: @PreAuthorize("hasAuthority('X')") sits on the SERVICE
//   methods, never on the controllers, and it never tests a role name. On top of
//   that, ProjectScopeInterceptor (ADR-021) checks BOTH the permission and the
//   project scope for URLs matching /api/projects/{id}/**. This class only turns
//   the engine on; the rules live in com.pms.auth and in each service.
//
//   No token is built or verified here either. Signing in returns a short-lived
//   JWT access token plus a refresh token stored in an HttpOnly cookie (a cookie
//   that JavaScript in the browser cannot read), rotated at every refresh, with
//   a tokenVersion counter that cancels all the sessions of one user at once.
//   That whole mechanism lives in com.pms.auth - JwtService, AuthService,
//   JwtAuthenticationFilter - and this class only makes sure those beans get
//   built and that the cache they need exists (see @EnableCaching below).
// =============================================================================

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Boot class of the PMS backend.
 *
 * <p>It holds no business logic, and that is on purpose. Everything this class
 * "does" is done through the three annotations below: each one switches on a
 * Spring feature that the rest of the code depends on. Keeping the class empty
 * means there is no hidden startup behaviour to look for when something goes
 * wrong - the answer is always in application.yml or in a scanned bean.</p>
 *
 * <p>Why a class and not a plain static main in some utility file: Spring Boot
 * needs a real class to use as the starting point of the component scan and as
 * the "primary source" of the application context, so the class itself is the
 * configuration.</p>
 */
// @SpringBootApplication is three annotations in one:
//   - @Configuration       : this class may declare beans;
//   - @ComponentScan       : find beans in com.pms and below (see header above);
//   - @EnableAutoConfiguration : look at the jars on the classpath and configure
//     the obvious things by itself - a Tomcat server because spring-boot-starter-web
//     is present, a HikariCP pool and Flyway because the PostgreSQL driver and
//     flyway-core are present, Hibernate because spring-boot-starter-data-jpa is.
// Why this instead of writing the configuration by hand: the project would
// otherwise need dozens of @Bean methods for the server, the DataSource, the
// EntityManagerFactory and the transaction manager, and each of them would be
// one more thing that can silently drift from application.yml.
// Without it: nothing is scanned and nothing is auto-configured - the process
// starts, finds no web server, and exits immediately.
@SpringBootApplication
// @EnableCaching switches on Spring's cache layer. In this project the cache is
// not used through @Cacheable; the code asks for the CacheManager directly
// (JwtAuthenticationFilter, AuthService, UserCrudService, RoleAdminService) and
// reads/clears entries by hand.
// Why it is still required: Spring Boot only creates the CacheManager bean when
// this annotation is present. The cache itself is Caffeine, sized in
// application.yml (maximumSize=2000, expireAfterWrite=5m).
// What it is for: ADR-017. The JWT access token carries identity only (email,
// tokenVersion), not the permission list. On every request the filter loads the
// user's security context - active flag, tokenVersion, permissions - and keeps
// it in the "securityContext" cache under the key "email:tokenVersion".
// Without this annotation: there is no CacheManager bean, so
// JwtAuthenticationFilter cannot be built and the application refuses to start
// with "required a bean of type org.springframework.cache.CacheManager". And if
// the cache existed but were bypassed, every single HTTP request would hit the
// database to re-read the user and its permissions.
@EnableCaching
// @EnableAsync switches on the machinery that makes a method annotated @Async
// return straight away and run its body on a background thread pool.
// Honest state of the code today: no method in com.pms carries @Async, so at
// runtime this annotation currently changes nothing. It is the switch, not the
// user of the switch.
// Why that matters to know: if a background job is added later (for example
// sending a notification after a mission is validated) and this annotation has
// been removed, the @Async on that method is simply ignored - no error, no
// warning - and the "background" work runs on the HTTP request thread, so the
// user waits for it.
@EnableAsync
public class PmsApplication {

    /**
     * Starts the application: builds the Spring context, then leaves the web
     * server running.
     *
     * <p>Watch out for the usual shortcut "main() waits here until the server
     * stops". It does not. SpringApplication.run() returns as soon as startup
     * is finished, and main() ends right after. The process stays alive because
     * the embedded Tomcat has started non-daemon threads, and a JVM only exits
     * when its last non-daemon thread is done. That is the real reason the log
     * prints "Started PmsApplication in ... seconds" and the terminal never
     * gives the prompt back until you press Ctrl+C.</p>
     *
     * <p>Returns nothing: the ApplicationContext that run() gives back is not
     * kept, because nothing here needs to look inside it.</p>
     *
     * @param args command line arguments, passed straight to Spring Boot. This
     *             is how the environment is chosen, because application.yml sets
     *             no active profile on purpose - for example
     *             --spring.profiles.active=prod to load application-prod.yml.
     *             Passing them on rather than ignoring them is what lets the same
     *             jar run in dev and in prod without a rebuild.
     */
    public static void main(String[] args) {
        // Hands control to Spring Boot. The order of what happens inside matters
        // for this project: Flyway applies the pending migrations (V1 .. V28)
        // BEFORE Hibernate starts, and Hibernate is set to ddl-auto=validate
        // (ADR-019: Flyway owns the schema, Hibernate never changes it).
        // Why this order is needed: a new column added in V28 exists in the
        // database before the entity is checked against it. Without it, startup
        // would fail with a schema validation error such as "missing column
        // ... in table ...", or - worse with ddl-auto=update - Hibernate would
        // quietly alter the production schema behind Flyway's back.
        SpringApplication.run(PmsApplication.class, args);
    }
}
