-- cleanup.sql: removes the throwaway accounts created by the e2e suite (e2e-sec-*@example.test)
-- and every family that only such accounts belong to. Run as the database superuser (the
-- owner role is subject to FORCEd RLS), e.g. `make e2e-clean` locally or, on the server:
--   docker compose -p eldering exec -T db psql -X -q -v ON_ERROR_STOP=1 -U postgres -d eldering < e2e/cleanup.sql
-- Deleting a user cascades to memberships, grants, settings, locations, incidents, trails,
-- acks and refresh tokens.
BEGIN;
CREATE TEMP TABLE e2e_users ON COMMIT DROP AS
  SELECT id FROM users WHERE email LIKE 'e2e-sec-%@example.test';
DELETE FROM families f
WHERE EXISTS (SELECT 1 FROM family_members m WHERE m.family_id = f.id AND m.user_id IN (SELECT id FROM e2e_users))
  AND NOT EXISTS (SELECT 1 FROM family_members m WHERE m.family_id = f.id AND m.user_id NOT IN (SELECT id FROM e2e_users));
DELETE FROM families f
WHERE f.created_by IN (SELECT id FROM e2e_users)
  AND NOT EXISTS (SELECT 1 FROM family_members m WHERE m.family_id = f.id);
DELETE FROM users WHERE id IN (SELECT id FROM e2e_users);
COMMIT;
