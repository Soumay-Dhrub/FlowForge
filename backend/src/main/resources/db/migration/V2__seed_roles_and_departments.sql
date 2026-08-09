-- ═══════════════════════════════════════════════════
-- FlowForge V2 — Seed Roles and Default Department
-- ═══════════════════════════════════════════════════

-- Insert 3 default roles with proper permissions
INSERT INTO roles (id, name, permissions) VALUES
    (gen_random_uuid(), 'ADMIN', '{
        "manageUsers": true,
        "manageWorkflows": true,
        "viewAuditLogs": true,
        "viewReports": true,
        "submitRequests": true,
        "approveTasks": true
    }'::jsonb),
    (gen_random_uuid(), 'MANAGER', '{
        "manageUsers": false,
        "manageWorkflows": false,
        "viewAuditLogs": false,
        "viewReports": true,
        "submitRequests": true,
        "approveTasks": true
    }'::jsonb),
    (gen_random_uuid(), 'EMPLOYEE', '{
        "manageUsers": false,
        "manageWorkflows": false,
        "viewAuditLogs": false,
        "viewReports": false,
        "submitRequests": true,
        "approveTasks": false
    }'::jsonb);

-- Insert default department
INSERT INTO departments (id, name) VALUES
    (gen_random_uuid(), 'General');
