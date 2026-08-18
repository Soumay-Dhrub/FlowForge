-- ═══════════════════════════════════════════════════
-- FlowForge V4 — audit_logs is append-only
-- ═══════════════════════════════════════════════════
--
-- Requirement 19.2: the Audit_Log_System SHALL prevent modification or deletion of any Audit_Log entry.
--
-- Why this lives in the database and not only in Java. The application's guards are real but they are
-- all reachable around: AuditLog maps every column `updatable = false`, AuditLogRepository exposes no
-- delete, and no service or controller method modifies an entry — yet any of that can be undone by the
-- next person to add a method, and none of it constrains psql, a migration, a reporting job, or another
-- process pointed at the same database. "Nothing in our code calls delete" is a statement about our
-- code. Requirement 19.2 is a statement about the entries.
--
-- So the rule is enforced where the rows live. BEFORE triggers raise, which aborts the statement and
-- its transaction: the row cannot be changed or removed by anything speaking SQL to this table,
-- whatever privileges it holds.
--
-- TRUNCATE gets its own statement-level trigger because it is not a DELETE and would otherwise empty
-- the table without firing a row-level trigger. Emptying the audit trail is the most valuable single
-- thing an attacker who reaches the database could do.
--
-- Honest limits. A superuser can still `ALTER TABLE audit_logs DISABLE TRIGGER ALL`, set
-- `session_replication_role = replica`, or drop the triggers — no in-database rule survives someone who
-- can rewrite the rules. What this buys is that every ordinary path, including a future mistake in
-- FlowForge itself, is refused loudly instead of silently succeeding, and that going around it takes a
-- deliberate DDL statement. Tamper-evident, which is what Requirement 19 asks for, rather than
-- tamper-proof, which no application-side design can deliver.

-- ── UPDATE: refused, with exactly one exception ──────────────────────────────────────────────────
--
-- audit_logs.actor_id is a foreign key declared ON DELETE SET NULL (V1), so deleting a user makes
-- PostgreSQL write NULL into that column on every entry they authored — and a referential action fires
-- row-level triggers like any other write. A blanket "no UPDATE" rule would therefore make users
-- undeletable, turning an audit guarantee into a user-management bug.
--
-- The guard is narrowed to exactly that transition instead: an UPDATE is allowed only when actor_id
-- goes from a value to NULL and every recorded fact — action, entity, before/after state, timestamp,
-- and the id itself — is unchanged. So attribution can be dropped by deleting the actor, which is the
-- documented consequence of ON DELETE SET NULL and is visible as such (a NULL actor). What no statement
-- can do is alter what was recorded, re-attribute an entry to somebody else, or restore an actor.

CREATE OR REPLACE FUNCTION audit_logs_guard_update() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.actor_id IS NOT NULL
        AND NEW.actor_id IS NULL
        AND NEW.id = OLD.id
        AND NEW.action = OLD.action
        AND NEW.entity_type = OLD.entity_type
        AND NEW.entity_id = OLD.entity_id
        AND NEW.before_state IS NOT DISTINCT FROM OLD.before_state
        AND NEW.after_state IS NOT DISTINCT FROM OLD.after_state
        AND NEW.created_at = OLD.created_at
    THEN
        -- The ON DELETE SET NULL referential action, and nothing else.
        RETURN NEW;
    END IF;

    RAISE EXCEPTION
        'audit_logs is append-only: entry % cannot be modified (Requirement 19.2)', OLD.id
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION audit_logs_guard_update() IS
    'Refuses every UPDATE on audit_logs except the actor_id FK''s ON DELETE SET NULL (Requirement 19.2).';

-- ── DELETE and TRUNCATE: always refused ─────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION audit_logs_reject_removal() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'audit_logs is append-only: % is not permitted on this table (Requirement 19.2)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION audit_logs_reject_removal() IS
    'Refuses any DELETE or TRUNCATE against audit_logs (Requirement 19.2).';

CREATE TRIGGER audit_logs_no_update
    BEFORE UPDATE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION audit_logs_guard_update();

CREATE TRIGGER audit_logs_no_delete
    BEFORE DELETE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION audit_logs_reject_removal();

CREATE TRIGGER audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION audit_logs_reject_removal();

COMMENT ON COLUMN audit_logs.actor_id IS
    'Actor, or NULL for system actions and for actors since deleted (FK is ON DELETE SET NULL).';

-- Search and export filter on (action) and (entity_type, created_at), both already indexed in V1.
-- What is missing for Requirement 19.3 is the actor-plus-time combination the admin search uses most:
-- "everything this person did last week" currently scans every entry they ever authored.
CREATE INDEX idx_audit_logs_actor_time ON audit_logs(actor_id, created_at DESC);
