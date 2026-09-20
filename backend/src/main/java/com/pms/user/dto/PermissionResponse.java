package com.pms.user.dto;

// Read-only shape sent for one RBAC permission. A DTO, not the entity, so the JSON never drags
// along BaseEntity's audit fields or breaks on a lazy relation after the transaction closes.

/**
 * One permission of the RBAC catalogue (ADR-001). A record so nothing can mutate it between
 * being built and being serialized to JSON.
 */
public record PermissionResponse(
        // The id, not just the code, so a role editor's checkbox selection survives a code rename.
        Long id,
        // The exact string @PreAuthorize("hasAuthority('X')") compares against — the real key of
        // the authorization system.
        String code,
        // Functional family (e.g. "PROJET"), used only to group rows on screen.
        String module,
        // Nullable free text; the Angular interface declares it optional for the same reason.
        String description
) {}
