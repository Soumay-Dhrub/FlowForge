-- ═══════════════════════════════════════════════════════════════
-- V3: comment threading (Requirement 15.1)
--
-- Requirement 15 asks for "threaded comments" but V1 gave `comments` no parent
-- link, so the feature shipped as a flat chronological log. This closes that gap.
--
-- Nullable on purpose: a null parent is a top-level comment, which is what every
-- existing row is. That makes this migration safe on a populated database — no
-- backfill, no default to invent, and the flat threads already stored stay valid
-- as threads with no replies.
--
-- ON DELETE CASCADE matches the instance_id column above it. A reply cannot outlive
-- what it is replying to: orphaned rows would render as top-level comments and
-- silently change what somebody appeared to say.
-- ═══════════════════════════════════════════════════════════════

ALTER TABLE comments
    ADD COLUMN parent_comment_id UUID NULL REFERENCES comments(id) ON DELETE CASCADE;

-- Reading a thread means fetching a parent's replies, so the lookup is by parent.
-- created_at is included because replies are always read in the order they were
-- written, letting the index serve the ordering too.
CREATE INDEX idx_comments_parent ON comments(parent_comment_id, created_at);

-- A comment cannot reply to itself. The service also refuses cross-instance and
-- deeper-than-one-level replies, which the schema cannot express, but self-reference
-- is cheap to rule out here and would otherwise produce a cycle of length one.
ALTER TABLE comments
    ADD CONSTRAINT chk_comments_no_self_reply CHECK (parent_comment_id IS NULL OR parent_comment_id <> id);
