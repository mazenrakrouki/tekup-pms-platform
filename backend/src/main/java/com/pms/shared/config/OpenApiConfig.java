package com.pms.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Describes the API for springdoc (title, version, contact, bearer-auth scheme), which
// serves it at /v3/api-docs and /swagger-ui.html. Without the security scheme below, Swagger
// UI has no way to paste a token and every "Try it out" answers 401.

@Configuration
public class OpenApiConfig {

    /** springdoc looks for a bean of this exact type, so this must be a @Bean, not a constant. */
    @Bean
    public OpenAPI pmsOpenAPI() {
        // Local constant: written three times below (declare/require/inside the scheme); if the
        // copies drifted, Swagger UI would require a scheme that isn't declared, silently.
        final String schemeName = "bearerAuth";
        return new OpenAPI()
            .info(new Info()
                .title("PMS — Project Management System API")
                .description("API REST de la plateforme de gestion de projets informatiques ST2I. " +
                    "Authentification par JWT (Bearer token). Obtenez un token via POST /api/auth/login.")
                .version("1.0.0")
                .contact(new Contact()
                    .name("ST2I — Société Tunisienne d'Ingénierie Informatique")
                    .email("contact@st2i.tn")))
            // Documentation only (real enforcement is SecurityConfig): applies to every endpoint
            // globally, so login/refresh show as "protected" too even though they need no token.
            .addSecurityItem(new SecurityRequirement().addList(schemeName))
            .components(new Components()
                .addSecuritySchemes(schemeName, new SecurityScheme()
                    .name(schemeName)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")));
    }
}
