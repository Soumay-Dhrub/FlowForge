# Requirements Document

## Introduction

FlowForge is a configurable, low-code workflow orchestration platform that enables organizations to design, execute, monitor, and continuously optimize business workflows without modifying backend code. The platform provides a drag-and-drop canvas for building workflows, a robust execution engine for running them, task and approval management, notifications, audit logging, and analytics dashboards.

**Tech Stack:**
- Backend: Spring Boot (Java), PostgreSQL, JPA/Hibernate, Spring Security + JWT
- Frontend: Next.js, TypeScript, Tailwind CSS, React Flow, React Query
- Infrastructure: Docker, Docker Compose, GitHub Actions CI/CD

## Glossary

- **FlowForge**: The overall platform being built.
- **Workflow**: A directed acyclic graph of nodes representing a business process.
- **WorkflowVersion**: An immutable snapshot of a Workflow at publish time; assigned a monotonically increasing version number.
- **WorkflowInstance**: A running execution of a specific WorkflowVersion, created when a user submits a request.
- **Node**: A single step in a workflow; one of: Start, Task, Approval, Condition, Notification, End.
- **Edge**: A directed connection between two nodes, optionally carrying a conditional expression.
- **Task**: An action item assigned to a user as part of a WorkflowInstance execution.
- **Approval**: A specialised Task requiring an approve or reject decision.
- **User**: A registered platform participant with a name, email, hashed password, role, and department.
- **Role**: A named set of permissions (e.g., ADMIN, MANAGER, STAFF).
- **Department**: An organisational unit grouping users.
- **JWT**: JSON Web Token used for stateless authentication (access token + refresh token pair).
- **RBAC**: Role-Based Access Control — permission checks enforced per endpoint.
- **Audit Log**: An immutable, append-only record of every state-changing action on the platform.
- **Dashboard**: A personalised or aggregate view of workflow metrics and pending work items.
- **Delegation**: Transferring pending approvals from one user to another for a defined period.
- **Escalation**: Automatic reassignment of a task after a configurable timeout duration.
- **AND-join**: A synchronisation point where all parallel branches must complete before the workflow advances.
- **OpenAPI**: Machine-readable REST API description standard; exposed at `/api/docs`.
- **JSONB**: PostgreSQL binary JSON column type used for flexible node configuration and form data.

---

## Requirements

### Requirement 1: Project Foundation & Infrastructure

**User Story:** As a developer, I want a fully containerised project scaffold with CI/CD, so that the team can build and ship consistently from the first commit.

#### Acceptance Criteria

1. THE Platform SHALL expose all REST endpoints under the `/api` path prefix.
2. THE Platform SHALL provide an OpenAPI/Swagger specification at `/api/docs`.
3. THE Platform SHALL be runnable via a single `docker compose up` command that starts the backend, frontend, and PostgreSQL database.
4. WHEN a GitHub Actions workflow is triggered on push or pull request, THE CI_Pipeline SHALL build the project and run all automated tests.
5. THE Backend SHALL use a layered package structure: `com.flowforge.auth`, `com.flowforge.user`, `com.flowforge.workflow`, `com.flowforge.engine`, `com.flowforge.task`, `com.flowforge.notification`, `com.flowforge.audit`, `com.flowforge.report`, and `com.flowforge.common`.
6. THE Backend SHALL use Flyway or Liquibase for database schema migrations, applied automatically on startup.

---

### Requirement 2: Authentication & Token Management

**User Story:** As a user, I want to log in with my email and password and receive a secure token, so that I can access protected resources without re-authenticating on every request.

#### Acceptance Criteria

1. WHEN a user submits valid credentials, THE Auth_Service SHALL return a signed JWT access token and a refresh token.
2. WHEN a user submits invalid credentials, THE Auth_Service SHALL return an HTTP 401 response with a descriptive error message and SHALL NOT reveal whether the email or password was incorrect.
3. WHEN an access token has expired and a valid refresh token is presented, THE Auth_Service SHALL issue a new access token without requiring re-login.
4. WHEN an invalid or expired refresh token is presented, THE Auth_Service SHALL return an HTTP 401 response and invalidate the refresh token.
5. THE Auth_Service SHALL sign JWT access tokens with a configurable secret and set an expiry of no more than 60 minutes.
6. WHEN a user logs out, THE Auth_Service SHALL invalidate the refresh token so it cannot be reused.
7. THE Auth_Service SHALL store refresh tokens in the database with a one-to-one association to the User record.

---

### Requirement 3: User Management & RBAC

**User Story:** As an administrator, I want to register and manage user accounts with defined roles, so that access to platform features is controlled and auditable.

#### Acceptance Criteria

1. WHEN an administrator creates a new user with name, email, password, role, and department, THE User_Service SHALL persist the user with the password stored as a BCrypt hash.
2. WHEN a duplicate email is submitted, THE User_Service SHALL return an HTTP 409 response.
3. WHEN an administrator deactivates a user account, THE User_Service SHALL prevent that user from authenticating until the account is reactivated.
4. WHEN a deactivated user attempts to authenticate, THE Auth_Service SHALL return an HTTP 403 response.
5. THE RBAC_Filter SHALL enforce role-based access control on every protected API endpoint.
6. WHEN a request is made to a protected endpoint without a valid JWT, THE RBAC_Filter SHALL return an HTTP 401 response.
7. WHEN a request is made to a protected endpoint by a user whose role lacks the required permission, THE RBAC_Filter SHALL return an HTTP 403 response.
8. WHEN an administrator initiates a password reset for a user, THE User_Service SHALL generate a single-use, time-limited reset token (valid for no more than 24 hours) and SHALL send it to the user's registered email.
9. WHEN a valid password reset token is presented with a new password, THE User_Service SHALL update the password hash and invalidate the token.
10. WHEN an expired or already-used password reset token is presented, THE User_Service SHALL return an HTTP 400 response.

---

### Requirement 4: Workflow Builder

**User Story:** As a process designer, I want to create and publish versioned workflow definitions using a visual canvas, so that business processes can be configured without code changes.

#### Acceptance Criteria

1. WHEN a designer creates a new workflow, THE Workflow_Service SHALL persist it with status `DRAFT` and version `0`.
2. THE Workflow_Service SHALL support the following node types on the canvas: `START`, `TASK`, `APPROVAL`, `CONDITION`, `NOTIFICATION`, and `END`.
3. WHEN a designer saves changes to a draft workflow, THE Workflow_Service SHALL persist the updated node and edge graph without creating a new version.
4. WHEN a designer requests to publish a workflow, THE Workflow_Service SHALL validate the graph against the following rules before publishing: exactly one `START` node exists, every node is reachable from the `START` node, no orphaned edges exist, and at least one `END` node exists.
5. IF a workflow fails graph validation, THEN THE Workflow_Service SHALL return an HTTP 422 response listing each validation error.
6. WHEN a workflow passes validation and is published, THE Workflow_Service SHALL create an immutable WorkflowVersion with a monotonically increasing version number, storing the full node and edge graph as JSONB.
7. WHEN a previously published WorkflowVersion exists, THE Workflow_Service SHALL preserve it unchanged after a new version is published.
8. WHEN a designer requests to clone a workflow or a specific version, THE Workflow_Service SHALL create a new draft workflow with all nodes and edges copied and a name suffix of `(Copy)`.
9. THE Workflow_Service SHALL support conditional expressions on edges, evaluated at runtime against fields in the WorkflowInstance request payload.
10. WHEN a designer views the workflow list, THE Workflow_Service SHALL return all workflows with their latest version metadata (version number, status, last modified date).

---

### Requirement 5: Workflow Execution Engine

**User Story:** As a requester, I want to submit a workflow request and have the system automatically progress it through each step, so that business approvals happen without manual coordination.

#### Acceptance Criteria

1. WHEN a user submits a workflow request against a published WorkflowVersion, THE Execution_Engine SHALL create a WorkflowInstance with status `IN_PROGRESS` and set the current node to the `START` node.
2. WHEN the Execution_Engine evaluates a node, THE Execution_Engine SHALL perform the action corresponding to that node's type: advance automatically for `START` and `NOTIFICATION` nodes, create a Task for `TASK` and `APPROVAL` nodes, evaluate the conditional expression for `CONDITION` nodes, and set status to `COMPLETED` for `END` nodes.
3. AFTER every node transition, THE Execution_Engine SHALL persist the current node ID and instance status to the database before performing the next action.
4. WHEN a WorkflowInstance is recovered after a crash, THE Execution_Engine SHALL resume execution from the last persisted node without re-executing completed steps.
5. WHEN a `CONDITION` node is evaluated, THE Execution_Engine SHALL traverse the outgoing edge whose expression evaluates to `true` against the instance's request payload.
6. IF no outgoing edge expression evaluates to `true` on a `CONDITION` node, THEN THE Execution_Engine SHALL set the WorkflowInstance status to `FAILED` and record the error.
7. WHEN a workflow definition includes parallel `APPROVAL` branches, THE Execution_Engine SHALL wait for all branches to complete (AND-join) before advancing to the next node.
8. WHEN a Task has a configured timeout duration and that duration elapses without completion, THE Execution_Engine SHALL reassign the Task to the configured escalation recipient and record an escalation event.
9. WHEN all required approvals are obtained, THE Execution_Engine SHALL advance the WorkflowInstance to the `END` node and set its status to `COMPLETED`.
10. WHEN any approval is rejected, THE Execution_Engine SHALL set the WorkflowInstance status to `REJECTED` and stop further execution.

---

### Requirement 6: Task Management & Approvals

**User Story:** As an assignee, I want to view, act on, and comment on my tasks, so that I can fulfil my responsibilities in a workflow process.

#### Acceptance Criteria

1. WHEN a Task is created, THE Task_Service SHALL assign it to the user or role specified in the node configuration and set its status to `PENDING`.
2. THE Task_Service SHALL provide a filtered task list per user, supporting filters for status, workflow name, and date range.
3. WHEN a manager approves a Task, THE Task_Service SHALL set the Task status to `APPROVED` and notify the Execution_Engine to advance the WorkflowInstance.
4. WHEN a manager rejects a Task, THE Task_Service SHALL require a non-empty rejection comment and set the Task status to `REJECTED`.
5. WHEN a rejection comment is absent on a reject action, THE Task_Service SHALL return an HTTP 400 response.
6. THE Task_Service SHALL support file attachments on WorkflowInstance requests and individual Tasks, storing file metadata in the database and file content in a configured storage location.
7. THE Task_Service SHALL support threaded comments on a WorkflowInstance, visible to all participants of that instance.
8. WHEN a user delegates pending approvals, THE Task_Service SHALL reassign all current `PENDING` Tasks from the delegating user to the delegate user for the specified delegation period.
9. WHEN the delegation period expires, THE Task_Service SHALL reassign remaining `PENDING` Tasks back to the original user.
10. WHEN a Task is delegated, THE Task_Service SHALL record the delegation event in the audit log.

---

### Requirement 7: Notifications

**User Story:** As a user, I want to be notified in-app and by email when tasks are assigned, approved, rejected, or escalated, so that I can take timely action.

#### Acceptance Criteria

1. WHEN a Task is assigned, approved, rejected, or escalated, THE Notification_Service SHALL create an in-app notification for the relevant user.
2. WHEN an in-app notification is created, THE Notification_Service SHALL deliver it without requiring a page refresh (near-real-time delivery via polling or WebSocket).
3. WHEN a user marks a notification as read, THE Notification_Service SHALL update its status to `READ` and reflect the change immediately.
4. WHEN a Task event occurs and the user's email notification preference is enabled, THE Notification_Service SHALL send an email using the configurable template for that event type.
5. THE Notification_Service SHALL support configurable email templates per event type (task assigned, approved, rejected, escalated).
6. WHEN a user updates their email notification preference, THE Notification_Service SHALL respect the updated preference for all subsequent events.

---

### Requirement 8: Audit Logging

**User Story:** As a compliance officer, I want an immutable record of every state-changing action, so that I can investigate incidents and satisfy regulatory requirements.

#### Acceptance Criteria

1. WHEN any create, update, approve, reject, or delete action occurs on the platform, THE Audit_Service SHALL append an immutable audit entry containing the actor's user ID, action type, entity type, entity ID, timestamp, and a before/after snapshot of changed fields.
2. THE Audit_Service SHALL prevent modification or deletion of audit entries through the application layer.
3. THE Audit_Service SHALL support search and filtering of audit entries by user, entity type, date range, and action type.
4. WHEN an audit search is performed, THE Audit_Service SHALL return paginated results.
5. WHEN an administrator requests an audit export, THE Audit_Service SHALL generate a CSV file containing all matching audit entries.

---

### Requirement 9: Dashboard & Analytics

**User Story:** As a user or administrator, I want a dashboard showing my pending work and aggregate workflow metrics, so that I can monitor operational health and identify bottlenecks.

#### Acceptance Criteria

1. THE Dashboard_Service SHALL provide each authenticated user with a personal dashboard containing: count of pending tasks, list of submitted requests with status, and a recent activity feed.
2. THE Dashboard_Service SHALL provide administrators and managers with aggregate metrics including: average approval time per workflow, volume of instances by workflow, bottleneck stages (nodes with highest average dwell time), and rejection rate per workflow.
3. THE Dashboard_Service SHALL support filtering aggregate metrics by department, workflow, and date range.
4. WHEN a user or administrator requests a data export, THE Dashboard_Service SHALL generate a CSV file of the filtered metrics.
5. THE Dashboard_Service SHALL support chart-ready data responses (arrays of labelled numeric series) suitable for rendering by the frontend charting library.
