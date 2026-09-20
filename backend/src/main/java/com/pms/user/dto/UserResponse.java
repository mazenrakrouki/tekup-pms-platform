package com.pms.user.dto;

// Read-only view of one user account for the browser. Declares neither passwordHash nor
// tokenVersion, so MapStruct never copies them into a JSON response, and flattens the entity
// while the transaction is still open (open-in-view: false) to avoid LazyInitializationException.

/**
 * One user account, as the client sees it.
 *
 * <p>Filled by UserMapper.toResponse(); only roleName needs an explicit mapping rule
 * ({@code @Mapping(target = "roleName", source = "role.name")}).
 */
public record UserResponse(
        // Primary key, reused by PUT/PATCH endpoints and by assignment screens.
        Long id,

        // Kept as separate parts (not pre-joined) because the list sorts on lastName and the
        // forms edit each part independently. The joined form exists as User.getFullName() for
        // screens that only need a label.
        String firstName,
        String lastName,

        // The login e-mail; shown as the row's real key rather than the id.
        String email,

        // The role NAME, a label only — no authorization decision reads this string; access is
        // always decided on permission codes via @PreAuthorize. The full Role object is
        // deliberately not sent, since it would repeat the whole permission set on every row.
        String roleName,

        // false = account switched off; login is refused via accountLocked(!active), and
        // deactivating also revokes open sessions.
        boolean active,

        // true = still on the admin-generated password; FirstLoginFilter refuses every endpoint
        // except change-password/logout while this holds. Lets the admin spot who never signed in.
        boolean firstLogin
) {}
