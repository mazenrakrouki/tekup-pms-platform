-- V20: removes two permission rows that nothing enforced, MANAGE_ROLES and VIEW_AUDIT_LOG (audit point N-2),
-- seeded by V2 but never named by any @PreAuthorize - so they protected nothing while promising screens
-- (role admin, audit log) the application didn't have, including in the Angular menu built from them.
--
-- MANAGE_ROLES came back in V25 once the admin screens really existed. VIEW_AUDIT_LOG was never restored -
-- there is no audit-log screen; the created_by/updated_by columns (V19) are the audit trail, read in the
-- database directly. So V20/V25 together are the traceable history of that decision, not a mistake fixed later.
--
-- No token_version bump here (unlike V13/V25): those migrations GRANT a new authority and want it visible at
-- once; here nothing was enforcing the two permissions anyway, so no session was worth cutting - the change
-- reaches an already-signed-in user within five minutes anyway, via JwtAuthenticationFilter's permission cache.

-- Link rows deleted first and explicitly, even though fk_rp_permission's ON DELETE CASCADE (V1) would remove
-- them anyway - keeps the intent readable and the script correct if that cascade were ever dropped.
-- Matched by CODE, not id: ids come from a BIGSERIAL counter and differ per database; the code is the stable
-- key that hasAuthority(...) itself compares against.
-- A real DELETE (not soft): permissions/role_permissions are migration-seeded reference data, nothing points
-- at them from a timesheet or invoice, and role_permissions has no "deleted" column at all.
DELETE FROM role_permissions WHERE permission_id IN (
    SELECT id FROM permissions WHERE code IN ('MANAGE_ROLES', 'VIEW_AUDIT_LOG')
);
-- The permission rows themselves. After this line, hasAuthority('MANAGE_ROLES')
-- can never be true for anybody, including ADMIN, until V25 puts the row back.
DELETE FROM permissions WHERE code IN ('MANAGE_ROLES', 'VIEW_AUDIT_LOG');
