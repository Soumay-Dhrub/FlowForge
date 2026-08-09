-- ═══════════════════════════════════════════════════
-- FlowForge V1 — Initial Schema
-- ═══════════════════════════════════════════════════

-- Enable UUID generation extension
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ═══════════════════════════════════════════════════
-- TABLE: roles
-- ═══════════════════════════════════════════════════
CREATE TABLE roles (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50) NOT NULL UNIQUE,
    permissions JSONB       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: departments
-- ═══════════════════════════════════════════════════
CREATE TABLE departments (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(150) NOT NULL,
    manager_id UUID,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: users
-- ═══════════════════════════════════════════════════
CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(150) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role_id       UUID         NOT NULL REFERENCES roles(id),
    department_id UUID         REFERENCES departments(id),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Add circular reference after users table exists
ALTER TABLE departments ADD CONSTRAINT fk_departments_manager 
    FOREIGN KEY (manager_id) REFERENCES users(id) ON DELETE SET NULL;

-- ═══════════════════════════════════════════════════
-- TABLE: refresh_tokens
-- ═══════════════════════════════════════════════════
CREATE TABLE refresh_tokens (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token      VARCHAR(512) NOT NULL UNIQUE,
    expires_at TIMESTAMP    NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: password_reset_tokens
-- ═══════════════════════════════════════════════════
CREATE TABLE password_reset_tokens (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token      VARCHAR(512) NOT NULL UNIQUE,
    expires_at TIMESTAMP    NOT NULL,
    used       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: workflows
-- ═══════════════════════════════════════════════════
CREATE TABLE workflows (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(150) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' 
        CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    created_by  UUID         NOT NULL REFERENCES users(id),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: workflow_versions
-- ═══════════════════════════════════════════════════
CREATE TABLE workflow_versions (
    id             UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id    UUID      NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    version_number INTEGER   NOT NULL,
    graph_json     JSONB     NOT NULL DEFAULT '{"nodes":[],"edges":[]}',
    is_published   BOOLEAN   NOT NULL DEFAULT FALSE,
    is_current     BOOLEAN   NOT NULL DEFAULT FALSE,
    published_at   TIMESTAMP,
    published_by   UUID      REFERENCES users(id),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (workflow_id, version_number)
);

-- ═══════════════════════════════════════════════════
-- TABLE: workflow_nodes
-- ═══════════════════════════════════════════════════
CREATE TABLE workflow_nodes (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id  UUID        NOT NULL REFERENCES workflow_versions(id) ON DELETE CASCADE,
    type        VARCHAR(30) NOT NULL 
        CHECK (type IN ('START', 'TASK', 'APPROVAL', 'CONDITION', 'NOTIFICATION', 'END', 'AND_JOIN')),
    config_json JSONB       NOT NULL DEFAULT '{}',
    position_x  INTEGER     NOT NULL DEFAULT 0,
    position_y  INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: workflow_edges
-- ═══════════════════════════════════════════════════
CREATE TABLE workflow_edges (
    id             UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id     UUID      NOT NULL REFERENCES workflow_versions(id) ON DELETE CASCADE,
    source_node_id UUID      NOT NULL REFERENCES workflow_nodes(id) ON DELETE CASCADE,
    target_node_id UUID      NOT NULL REFERENCES workflow_nodes(id) ON DELETE CASCADE,
    condition_expr TEXT,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: workflow_instances
-- ═══════════════════════════════════════════════════
CREATE TABLE workflow_instances (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_version_id UUID        NOT NULL REFERENCES workflow_versions(id),
    initiated_by        UUID        NOT NULL REFERENCES users(id),
    current_node_id     UUID        REFERENCES workflow_nodes(id),
    status              VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'COMPLETED', 'REJECTED', 'ERROR', 'CANCELLED')),
    request_data        JSONB       NOT NULL DEFAULT '{}',
    branch_status       JSONB       NOT NULL DEFAULT '{}',
    started_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: tasks
-- ═══════════════════════════════════════════════════
CREATE TABLE tasks (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID        NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    node_id     UUID        NOT NULL REFERENCES workflow_nodes(id),
    assigned_to UUID        NOT NULL REFERENCES users(id),
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'COMPLETED', 'DELEGATED', 'ESCALATED', 'CANCELLED')),
    due_at      TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: approvals
-- ═══════════════════════════════════════════════════
CREATE TABLE approvals (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id     UUID        NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,
    approver_id UUID        NOT NULL REFERENCES users(id),
    decision    VARCHAR(20) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    comment     TEXT,
    decided_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: comments
-- ═══════════════════════════════════════════════════
CREATE TABLE comments (
    id          UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID      NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    author_id   UUID      NOT NULL REFERENCES users(id),
    body        TEXT      NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: attachments
-- ═══════════════════════════════════════════════════
CREATE TABLE attachments (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id  UUID         NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    uploaded_by  UUID         NOT NULL REFERENCES users(id),
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(127) NOT NULL,
    file_size    BIGINT       NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: notifications
-- ═══════════════════════════════════════════════════
CREATE TABLE notifications (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    payload    JSONB       NOT NULL DEFAULT '{}',
    is_read    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: notification_preferences
-- ═══════════════════════════════════════════════════
CREATE TABLE notification_preferences (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type    VARCHAR(50) NOT NULL,
    email_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, event_type)
);

-- ═══════════════════════════════════════════════════
-- TABLE: delegations
-- ═══════════════════════════════════════════════════
CREATE TABLE delegations (
    id           UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    delegator_id UUID      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    delegate_id  UUID      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    start_at     TIMESTAMP NOT NULL,
    end_at       TIMESTAMP NOT NULL,
    is_active    BOOLEAN   NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- TABLE: audit_logs
-- ═══════════════════════════════════════════════════
CREATE TABLE audit_logs (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id     UUID        REFERENCES users(id) ON DELETE SET NULL,
    action       VARCHAR(50) NOT NULL,
    entity_type  VARCHAR(50) NOT NULL,
    entity_id    UUID        NOT NULL,
    before_state JSONB,
    after_state  JSONB,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════
-- INDEXES
-- ═══════════════════════════════════════════════════

-- Users indexes
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role_id ON users(role_id);
CREATE INDEX idx_users_dept_id ON users(department_id);
CREATE INDEX idx_users_is_active ON users(is_active);

-- Refresh tokens indexes
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);

-- Password reset tokens indexes
CREATE INDEX idx_prt_user_id ON password_reset_tokens(user_id);
CREATE INDEX idx_prt_token ON password_reset_tokens(token);

-- Workflows indexes
CREATE INDEX idx_workflows_created_by ON workflows(created_by);
CREATE INDEX idx_workflows_status ON workflows(status);

-- Workflow versions indexes
CREATE INDEX idx_wv_workflow_id ON workflow_versions(workflow_id);
CREATE INDEX idx_wv_is_current ON workflow_versions(workflow_id, is_current);

-- Workflow nodes indexes
CREATE INDEX idx_wn_version_id ON workflow_nodes(version_id);

-- Workflow edges indexes
CREATE INDEX idx_we_version_id ON workflow_edges(version_id);
CREATE INDEX idx_we_source_node_id ON workflow_edges(source_node_id);
CREATE INDEX idx_we_target_node_id ON workflow_edges(target_node_id);

-- Workflow instances indexes
CREATE INDEX idx_wi_version_id ON workflow_instances(workflow_version_id);
CREATE INDEX idx_wi_initiated_by ON workflow_instances(initiated_by);
CREATE INDEX idx_wi_status ON workflow_instances(status);
CREATE INDEX idx_wi_status_ver ON workflow_instances(status, workflow_version_id);

-- Tasks indexes
CREATE INDEX idx_tasks_instance_id ON tasks(instance_id);
CREATE INDEX idx_tasks_assigned_to ON tasks(assigned_to);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_assigned_status ON tasks(assigned_to, status);
CREATE INDEX idx_tasks_due_at ON tasks(due_at) WHERE status = 'PENDING';

-- Approvals indexes
CREATE INDEX idx_approvals_task_id ON approvals(task_id);
CREATE INDEX idx_approvals_approver_id ON approvals(approver_id);

-- Comments indexes
CREATE INDEX idx_comments_instance_id ON comments(instance_id);

-- Attachments indexes
CREATE INDEX idx_attachments_instance_id ON attachments(instance_id);

-- Notifications indexes
CREATE INDEX idx_notifications_user_id ON notifications(user_id);
CREATE INDEX idx_notifications_user_is_read ON notifications(user_id, is_read);

-- Notification preferences indexes
CREATE INDEX idx_notif_prefs_user_id ON notification_preferences(user_id);

-- Delegations indexes
CREATE INDEX idx_delegations_delegator_id ON delegations(delegator_id);
CREATE INDEX idx_delegations_delegate_id ON delegations(delegate_id);
CREATE INDEX idx_delegations_active ON delegations(delegator_id, is_active, end_at);

-- Audit logs indexes
CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_entity_time ON audit_logs(entity_type, entity_id, created_at);
