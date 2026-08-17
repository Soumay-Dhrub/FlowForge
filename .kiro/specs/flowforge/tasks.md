# Implementation Plan: FlowForge

## Overview

Convert the FlowForge design into a series of incremental coding steps. Each task represents a single focused commit. Tasks are organized in phases that match the dependency graph: infrastructure must exist before features, and features must exist before the frontend consumes them. Property-based tests (jqwik) are included as optional sub-tasks adjacent to each unit of logic they validate.

---

## Tasks

### Phase 1 — Project Scaffolding

- [x] 1. Initialize Spring Boot backend project
  - Create a Maven multi-module project using Spring Initializr with dependencies: Spring Web, Spring Data JPA, Spring Security, Spring Mail, Spring Validation, Lombok, MapStruct, Flyway, PostgreSQL Driver, jqwik, Testcontainers.
  - Set up `application.yml` (datasource, JWT secret placeholders, mail SMTP placeholders) and `application-test.yml`.
  - Add `.gitignore`, `README.md`, and root `pom.xml` with dependency-management versions pinned.
  - _Requirements: all_

- [x] 2. Initialize Next.js frontend project
  - Scaffold Next.js 14 app router project with TypeScript, Tailwind CSS, ESLint, and Prettier.
  - Install and configure: `react-flow-renderer` (or `@xyflow/react`), `@tanstack/react-query`, `axios`, `react-hook-form`, `zod`, `lucide-react`.
  - Set up `next.config.mjs`, `tailwind.config.ts`, global CSS, and path aliases.
  - _Requirements: FR-WFB-01_

- [x] 3. Set up Docker and docker-compose
  - Write `Dockerfile` for the Spring Boot backend (multi-stage: build with Maven, run with JRE 21).
  - Write `Dockerfile` for the Next.js frontend (multi-stage: build with Node 20, serve with nginx).
  - Write `docker-compose.yml` with services: `postgres`, `backend`, `frontend`, and a `pgadmin` service for local dev.
  - Add environment variable documentation in `.env.example`.
  - _Requirements: all_

- [x] 4. Create initial Flyway migration — database schema
  - Write `V1__initial_schema.sql` creating all tables: `users`, `roles`, `departments`, `password_reset_tokens`, `refresh_tokens`, `workflows`, `workflow_versions`, `workflow_nodes`, `workflow_edges`, `workflow_instances`, `tasks`, `approvals`, `comments`, `attachments`, `notifications`, `audit_logs`.
  - Add indexes on all FK columns, `email` (unique), `entity_type + entity_id` (audit), and `assigned_to + status` (tasks).
  - Write `V2__seed_roles_and_departments.sql` seeding three roles (ADMIN, MANAGER, EMPLOYEE) and a default department.
  - _Requirements: all_

- [x] 5. Set up GitHub Actions CI pipeline
  - Create `.github/workflows/ci.yml` with jobs: `backend-test` (`mvn verify`), `frontend-test` (`pnpm test --ci`), `docker-build`.
  - Configure job dependencies so `docker-build` runs only after both test jobs pass.
  - Add branch protection rule documentation in `README.md`.
  - _Requirements: all_

---

### Phase 2 — Authentication & User Management

- [x] 6. Implement JWT token provider and Spring Security configuration
  - Implement `JwtTokenProvider`: `generateAccessToken(User)`, `generateRefreshToken(User)`, `validateToken(String)`, `extractClaims(String)`.
  - Configure `SecurityFilterChain`: permit `/api/auth/**`, require authentication on all other paths, add `JwtAuthenticationFilter` to the filter chain.
  - Implement `JwtAuthenticationFilter` that reads the `Authorization` header, validates the token, and sets `SecurityContextHolder`.
  - _Requirements: 2.1, 2.3, 3.1, 3.3_

  - [x]* 6.1 Write property test: JWT claims match issuing user (Property 3)
    - **Property 3: JWT Claims Match Issuing User**
    - **Validates: Requirements 2.1, 2.3**

- [x] 7. Implement User, Role, Department entities and repositories
  - Create JPA entities: `User`, `Role`, `Department` with all columns defined in the data model.
  - Create Spring Data JPA repositories: `UserRepository` (with `findByEmail`, `findByIdAndIsActiveTrue`), `RoleRepository`, `DepartmentRepository`.
  - Create MapStruct mappers: `UserMapper` (entity ↔ `UserResponse`, `CreateUserRequest`).
  - _Requirements: 1.1, 1.4_

- [x] 8. Implement authentication endpoints (login, refresh, logout)
  - Implement `AuthService.login(email, password)`: verify credentials, check account active status, issue JWT + refresh token, persist refresh token record.
  - Implement `AuthService.refreshToken(token)`: validate, rotate (invalidate old, issue new), return new access token.
  - Implement `AuthService.logout(token)`: invalidate refresh token record.
  - Implement `AuthController` with `POST /api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`.
  - _Requirements: 2.1, 2.2, 2.4, 2.5, 4.1_

  - [x]* 8.1 Write property test: refresh token is single-use (Property 4)
    - **Property 4: Refresh Token Single-Use Enforcement**
    - **Validates: Requirements 2.4**

- [x] 9. Implement user registration, profile, and RBAC enforcement
  - Implement `UserService.createUser(request)`: validate unique email, hash password (bcrypt, strength 12), persist user, emit audit log.
  - Implement `UserService.updateUser`, `UserService.setAccountStatus` (deactivate/reactivate + invalidate all refresh tokens).
  - Implement `UserController` with `GET/POST /api/users`, `GET/PATCH /api/users/{id}`, `PATCH /api/users/{id}/status`, `GET /api/users/me`.
  - Add `@PreAuthorize` annotations on controller methods per the RBAC table in the design.
  - _Requirements: 1.1, 1.2, 1.3, 3.1, 3.2, 4.1, 4.2, 4.3_

  - [x]* 9.1 Write property test: valid registration always creates a user (Property 1)
    - **Property 1: Valid Registration Always Creates a User**
    - **Validates: Requirements 1.1, 1.4**

  - [x]* 9.2 Write property test: missing fields rejected (Property 2)
    - **Property 2: Registration Rejects Payloads with Missing Required Fields**
    - **Validates: Requirements 1.3**

  - [x]* 9.3 Write property test: RBAC enforcement (Property 5)
    - **Property 5: RBAC Enforcement Across All Endpoints**
    - **Validates: Requirements 3.1, 3.3**

  - [x]* 9.4 Write property test: deactivation blocks access (Property 6)
    - **Property 6: Deactivation Immediately Blocks Access**
    - **Validates: Requirements 4.1, 4.2, 4.3**

- [ ] 10. Implement password reset flow
  - Implement `AuthService.requestPasswordReset(email)`: generate single-use UUID token, persist with expiry (24 h), send email via `EmailSender`.
  - Implement `AuthService.confirmPasswordReset(token, newPassword)`: validate token unexpired and unused, hash and update password, mark token used, invalidate all refresh tokens.
  - Implement `AuthController` endpoints: `POST /api/auth/password-reset/request`, `POST /api/auth/password-reset/confirm`.
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x]* 10.1 Write property test: reset token is single-use (Property 7)
    - **Property 7: Password Reset Token is Single-Use**
    - **Validates: Requirements 5.3**

- [x] 11. Checkpoint — Auth & User phase
  - Ensure all unit and property tests in the `auth` and `user` packages pass: `mvn verify -pl backend -Dtest="*Auth*,*User*,*Jwt*"`.
  - Verify docker-compose starts cleanly and Flyway migrations apply without errors.
  - Ask the user if questions arise before proceeding.

---

### Phase 3 — Workflow Builder (Backend)

- [x] 12. Implement Workflow, WorkflowVersion, Node, and Edge entities
  - Create JPA entities: `Workflow`, `WorkflowVersion` (with `graph_json JSONB`), `WorkflowNode`, `WorkflowEdge`.
  - Create repositories: `WorkflowRepository`, `WorkflowVersionRepository`, `WorkflowNodeRepository`, `WorkflowEdgeRepository`.
  - Create MapStruct mappers for `WorkflowResponse`, `WorkflowVersionResponse`, `SaveDraftRequest`, `PublishRequest`.
  - _Requirements: 6.4, 6.5, 7.6, 7.7, 8.1, 8.2, 8.3_

- [x] 13. Implement workflow CRUD and draft save
  - Implement `WorkflowService.createWorkflow(request)`: persist new `Workflow` and a blank draft `WorkflowVersion`.
  - Implement `WorkflowService.saveDraft(workflowId, versionId, nodes, edges)`: update the draft version's `graph_json` without publishing.
  - Implement `WorkflowService.cloneWorkflow(workflowId, sourceVersionId)`: deep-copy nodes and edges into a new `Workflow` entity.
  - Implement `WorkflowController`: `GET/POST /api/workflows`, `GET /api/workflows/{id}`, `PUT /api/workflows/{id}/versions/{vId}`, `POST /api/workflows/{id}/clone`.
  - _Requirements: 6.2, 6.4, 6.5, 8.1, 8.2_

- [x] 14. Implement workflow graph validation and publishing
  - Implement `WorkflowVersionService.validate(versionId)`: run all four graph rules (single Start, all nodes reachable via BFS, no orphaned edges, at least one End); return a `ValidationResult` with a list of violations.
  - Implement `WorkflowVersionService.publish(versionId)`: call `validate`, throw `WorkflowValidationException` on any violation, otherwise create an immutable snapshot (freeze `graph_json`, set `published_at`, mark as current version).
  - Implement `WorkflowController`: `POST /api/workflows/{id}/versions/{vId}/publish`.
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7_

  - [x]* 14.1 Write property test: graph validation is exhaustive (Property 8)
    - **Property 8: Workflow Graph Validation is Exhaustive**
    - **Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5**

  - [x]* 14.2 Write property test: published version immutability (Property 9)
    - **Property 9: Published Version Immutability**
    - **Validates: Requirements 7.6, 7.7**

- [x] 15. Checkpoint — Workflow Builder phase
  - Ensure all workflow service tests pass. Verify that publishing a malformed graph returns 422 with all violations listed.
  - Ask the user if questions arise before proceeding.

---

### Phase 4 — Workflow Execution Engine

- [-] 16. Implement WorkflowEngineService and NodeExecutorFactory
  - Define `NodeExecutor` interface: `void execute(WorkflowInstance instance, WorkflowNode node)`.
  - Implement `NodeExecutorFactory`: Spring `@Component` that maps `NodeType` enum to the corresponding executor bean.
  - Implement `WorkflowEngineService.createInstance(workflowId, userId, requestData)`: look up currently published version, create `WorkflowInstance` with `status = RUNNING`, persist, call `advance()`.
  - Implement `WorkflowEngineService.advance(instance)`: load current node → delegate to executor → persist updated state within a single `@Transactional` method.
  - _Requirements: 9.1, 9.2, 9.3_

- [ ] 17. Implement node executors (Start, End, Task, Notification)
  - `StartNodeExecutor`: log entry, advance to next node.
  - `EndNodeExecutor`: set `WorkflowInstance.status = COMPLETED`, write audit entry.
  - `TaskNodeExecutor`: create a `Task` record, assign to configured user/role, set `due_at` from timeout config, pause execution (instance stays RUNNING, waiting for task decision).
  - `NotificationNodeExecutor`: call `NotificationService.createNotification(...)` for the configured recipients, then advance.
  - _Requirements: 9.2, 11.1_

  - [ ]* 17.1 Write property test: instance references published version (Property — requirement 9.1)
    - Validate that for any workflow, the created instance's `workflowVersionId` matches the currently published version at the time of submission.
    - **Validates: Requirements 9.1**

- [ ] 18. Implement ConditionNodeExecutor and ApprovalNodeExecutor
  - `ConditionNodeExecutor`: iterate outgoing edges in order, evaluate each edge's condition expression (SpEL expression against `instance.requestData`), follow first matching edge; if none match, set `status = ERROR` and audit.
  - `ApprovalNodeExecutor`: create an `Approval`-type `Task`, assign to approver role/user, pause execution.
  - _Requirements: 9.4, 9.5, 13.1, 13.2, 13.3_

  - [ ]* 18.1 Write property test: condition evaluation routes to correct edge (Property 10)
    - **Property 10: Condition Evaluation Routes to Correct Edge**
    - **Validates: Requirements 9.4, 9.5**

- [ ] 19. Implement parallel branches and AND-Join
  - Extend `WorkflowEngineService` to detect multi-edge outgoing transitions: for each target node, create a branch entry in `instance.branch_status`.
  - Implement `AndJoinNodeExecutor`: check `branch_status` for all expected branches; only call `advance()` when all are marked complete; otherwise, return without advancing.
  - Persist branch completion atomically within the existing `@Transactional` `advance()` method.
  - _Requirements: 10.1, 10.2, 10.3_

  - [ ]* 19.1 Write property test: AND-join requires all branches (Property 11)
    - **Property 11: AND-Join Requires All Branches Before Advancing**
    - **Validates: Requirements 10.2, 10.3**

- [ ] 20. Implement timeout and escalation scheduler
  - Add a `@Scheduled(fixedDelay = 60_000)` method `EscalationScheduler.checkTimeouts()` that queries for tasks where `due_at < NOW()` and `status = PENDING`.
  - For each overdue task: reassign `assigned_to` to escalation target (from node config), update task status to `ESCALATED`, notify both previous and new assignee, write audit entry.
  - _Requirements: 11.1, 11.2, 11.3, 11.4_

- [ ] 21. Implement workflow instance and task API endpoints
  - Implement `TaskService.listTasks(userId, filters)`: query with optional `status`, `workflowId`, `dateRange` filter parameters, return sorted results.
  - Implement `TaskService.recordDecision(taskId, userId, decision, comment)`: validate ownership + role, persist `Approval`, call `engine.advance()`.
  - Implement `TaskController`: `GET /api/tasks`, `PATCH /api/tasks/{id}/decision`.
  - Implement `InstanceController`: `POST /api/workflows/{id}/instances`, `GET /api/instances/{id}`, `POST /api/instances/{id}/cancel`.
  - _Requirements: 9.1, 12.1, 12.2, 12.3, 13.1, 13.2, 13.3, 13.4_

  - [ ]* 21.1 Write property test: task list filtering (requirement 12.1–12.2)
    - For any set of tasks with varying statuses/workflows/dates, filtering must return exactly the matching subset.
    - **Validates: Requirements 12.1, 12.2**

  - [ ]* 21.2 Write property test: reject without comment returns 400 (Property 12)
    - **Property 12: Task Rejection Requires Non-Empty Comment**
    - **Validates: Requirements 13.2**

- [ ] 22. Checkpoint — Execution Engine phase
  - Run integration tests: create a 3-node workflow (Start → Approval → End), publish, submit instance, approve task, verify instance reaches COMPLETED.
  - Verify the condition engine with a branch workflow using Testcontainers.
  - Ask the user if questions arise before proceeding.

---

### Phase 5 — Task Management & Approvals

- [ ] 23. Implement file attachments
  - Add `Attachment` entity and `AttachmentRepository`.
  - Implement `AttachmentService.upload(instanceId, file, userId)`: validate file size against `app.attachment.max-size-bytes` and MIME type against `app.attachment.allowed-types` list; persist metadata, write file to configured storage path.
  - Implement `POST /api/instances/{id}/attachments` endpoint.
  - _Requirements: 14.1, 14.2, 14.3_

  - [ ]* 23.1 Write property test: file size and type enforcement (Property 13)
    - **Property 13: File Upload Enforces Size and Type Limits**
    - **Validates: Requirements 14.1, 14.2, 14.3**

- [ ] 24. Implement threaded comments
  - Add `Comment` entity (`id`, `instance_id`, `author_id`, `body`, `created_at`) and `CommentRepository`.
  - Implement `CommentService.addComment(instanceId, userId, body)`: verify caller is a participant of the instance (initiator or assignee of any task); persist comment.
  - Implement `CommentService.listComments(instanceId, userId)`: same participant check; return ordered by `created_at ASC`.
  - Implement `POST /api/instances/{id}/comments` and `GET /api/instances/{id}/comments` endpoints.
  - _Requirements: 15.1, 15.2, 15.3_

- [ ] 25. Implement task delegation
  - Add `Delegation` entity (`id`, `delegator_id`, `delegate_id`, `start_at`, `end_at`) and `DelegationRepository`.
  - Implement `TaskService.delegateTasks(userId, delegateId, startAt, endAt)`: reassign all current PENDING tasks to `delegateId`; persist delegation record.
  - Extend `TaskNodeExecutor` to check for active delegation on the assignee before setting `assigned_to`.
  - Implement `POST /api/tasks/{id}/delegate` endpoint.
  - Add a `@Scheduled` job `DelegationExpiryJob` that restores routing when `end_at < NOW()`.
  - _Requirements: 16.1, 16.2, 16.3_

---

### Phase 6 — Notifications & Audit

- [ ] 26. Implement in-app notification service
  - Add `Notification` entity and `NotificationRepository`.
  - Implement `NotificationService.notify(userId, eventType, payload)`: create `Notification` record; if user has email enabled for that event type, enqueue email via `EmailSender`.
  - Implement `NotificationController`: `GET /api/notifications` (sorted: unread first, then by `created_at DESC`), `PATCH /api/notifications/{id}/read`.
  - Wire `NotificationService` calls into: `TaskNodeExecutor`, `ApprovalNodeExecutor`, `EscalationScheduler`.
  - _Requirements: 17.1, 17.2, 17.3, 18.1, 18.3_

- [ ] 27. Implement email notifications and preference management
  - Implement `EmailSender.send(to, subject, templateName, variables)` using Spring Mail and Thymeleaf templates.
  - Create Thymeleaf email templates for: `task-assigned`, `task-approved`, `task-rejected`, `task-escalated`.
  - Add `NotificationPreference` entity (user_id, event_type, email_enabled) and repository.
  - Expose `GET/PUT /api/users/me/notification-preferences` to read and update preferences.
  - _Requirements: 17.4, 17.5, 18.2_

- [ ] 28. Implement AOP-based audit logging
  - Implement `AuditLogAspect` as a Spring `@Aspect` with a `@Around` pointcut matching all `*Service.create*`, `*Service.update*`, `*Service.delete*`, `*Service.approve*`, `*Service.reject*` methods.
  - Capture before/after state as JSONB diffs using Jackson; persist `AuditLog` via `AuditLogService.record(...)`.
  - Implement `AuditLogController`: `GET /api/audit-logs` with query params `userId`, `entityType`, `dateFrom`, `dateTo`, `action`; `GET /api/audit-logs/export` streaming CSV.
  - _Requirements: 19.1, 19.2, 19.3, 19.4_

  - [ ]* 28.1 Write property test: audit log completeness (Property 14)
    - **Property 14: Audit Log Completeness**
    - **Validates: Requirements 19.1**

  - [ ]* 28.2 Write property test: audit log immutability (Property 15)
    - **Property 15: Audit Log Immutability**
    - **Validates: Requirements 19.2**

- [ ] 29. Checkpoint — Notifications & Audit phase
  - Run integration test: submit a workflow, approve a task, verify exactly one `TASK_ASSIGNED` and one `TASK_APPROVED` notification exist, and that AuditLog contains entries for both events.
  - Ask the user if questions arise before proceeding.

---

### Phase 7 — Dashboard & Reports

- [ ] 30. Implement personal dashboard endpoint
  - Implement `ReportService.getDashboard(userId)`: query pending tasks count + list (for the user), submitted instances list (with status), and last 20 audit events for the user.
  - Implement `GET /api/reports/dashboard` endpoint.
  - _Requirements: 20.1, 20.2, 20.3_

- [ ] 31. Implement workflow performance metrics endpoint
  - Implement `ReportService.getWorkflowPerformance(workflowId, filters)`: compute average approval time per workflow and per node, identify bottleneck node (max avg dwell time), compute total volume and rejection rate.
  - Support `department`, `workflowId`, `dateFrom`, `dateTo` filter params.
  - Implement export: when `?format=csv` is provided, stream CSV; when `?format=json`, return JSON.
  - Implement `GET /api/reports/workflow/{id}/performance` endpoint.
  - _Requirements: 21.1, 21.2, 21.3, 21.4, 21.5_

  - [ ]* 31.1 Write property test: metrics computation correctness (Property 16)
    - **Property 16: Metrics Computation Correctness**
    - **Validates: Requirements 21.1, 21.2**

---

### Phase 8 — Frontend Foundation

- [ ] 32. Implement API client, auth context, and route protection
  - Create an Axios instance (`lib/api.ts`) with base URL, request interceptor that attaches the JWT, and response interceptor that calls the refresh endpoint on 401 and retries.
  - Implement `AuthContext` + `AuthProvider` (React Context) managing access token, user profile, login, logout.
  - Implement `ProtectedRoute` HOC / middleware that redirects unauthenticated users to `/login`.
  - _Requirements: 2.1, 2.4, 3.1_

- [ ] 33. Implement login page and authentication forms
  - Build `/login` page: email + password form with React Hook Form + Zod validation, calls `POST /api/auth/login`, stores tokens, redirects to dashboard.
  - Build `/forgot-password` page: email input, calls `POST /api/auth/password-reset/request`.
  - Build `/reset-password` page: token from URL query param + new password form, calls `POST /api/auth/password-reset/confirm`.
  - _Requirements: 2.1, 5.1, 5.3_

- [ ] 34. Implement shared UI shell (layout, navigation, notifications)
  - Build root layout: sidebar navigation with links to Dashboard, Workflows, Tasks, Users (ADMIN), Audit Logs (ADMIN).
  - Build `NotificationBell` component: polls `GET /api/notifications` every 30 s (React Query), shows unread badge count, dropdown with notification list, marks read on click.
  - Build `UserAvatar` / profile menu with logout.
  - _Requirements: 17.1, 18.1_

---

### Phase 9 — Frontend Features

- [ ] 35. Implement workflow builder canvas
  - Build `/workflows/[id]/edit` page using `@xyflow/react`.
  - Implement node palette (drag from sidebar to canvas) for: Start, Task, Approval, Condition, Notification, End node types.
  - Implement edge connection with optional condition expression modal.
  - Wire save-draft button to `PUT /api/workflows/{id}/versions/{vId}` via React Query mutation.
  - Wire publish button to `POST /api/workflows/{id}/versions/{vId}/publish`; display 422 validation errors inline.
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 7.1–7.5_

- [ ] 36. Implement workflow list and version history pages
  - Build `/workflows` page: data table listing all workflows with React Query, search/filter by name, create-new and clone buttons.
  - Build workflow detail page: version history list with publish timestamps and author, "view version" and "set as current draft" actions.
  - _Requirements: 8.1, 8.2, 8.3_

- [ ] 37. Implement task list and approval pages
  - Build `/tasks` page: data table with React Query, filter controls (status, workflow, date range).
  - Build task detail page: task metadata, approve/reject form (reject requires comment), file attachment upload, threaded comments section.
  - Implement delegation modal: date-range picker, delegate user selector, calls `POST /api/tasks/{id}/delegate`.
  - _Requirements: 12.1, 12.2, 13.1, 13.2, 14.1, 15.1, 15.2, 16.1_

- [ ] 38. Implement user management pages (ADMIN)
  - Build `/users` page (ADMIN only): data table with create-user modal, deactivate/reactivate toggle.
  - Build user profile edit form: name, department, role selectors.
  - _Requirements: 1.1, 4.1, 4.3_

- [ ] 39. Implement dashboard and analytics pages
  - Build `/dashboard` page: pending tasks widget, submitted requests widget, recent activity feed; data from `GET /api/reports/dashboard`.
  - Build `/reports/[workflowId]` page (ADMIN/MANAGER): KPI cards (avg approval time, rejection rate, volume), bottleneck stage highlight, department/date filters, export CSV/JSON button.
  - _Requirements: 20.1, 20.2, 20.3, 21.1–21.5_

- [ ] 40. Implement audit log viewer (ADMIN)
  - Build `/audit-logs` page: server-side paginated data table with filter controls (user, entity type, date range, action).
  - Add "Export CSV" button that calls `GET /api/audit-logs/export` and triggers browser download.
  - _Requirements: 19.3, 19.4_

---

### Phase 10 — DevOps & Testing

- [ ] 41. Write Testcontainers integration test suite
  - Create `IntegrationTestBase` with `@Testcontainers` and a shared `PostgreSQLContainer`.
  - Write end-to-end integration tests:
    - Full auth flow: register → login → refresh → logout.
    - Full workflow flow: create → save draft → publish → submit instance → approve → verify COMPLETED.
    - Audit log: verify AuditLog entries exist after each action in the workflow flow.
  - Annotate with `@Tag("integration")` and bind to the `integration` Maven profile.
  - _Requirements: all_

- [ ] 42. Write frontend component and integration tests
  - Write Jest + React Testing Library tests for:
    - `LoginForm`: valid submit, empty field validation, API error display.
    - `WorkflowCanvas`: adding a node, connecting two nodes, attempting to publish with no End node shows error.
    - `TaskDecisionForm`: reject without comment shows error, approve advances step.
  - Write MSW-based page integration test for the full login → dashboard flow.
  - _Requirements: all_

- [ ] 43. Harden CI pipeline and finalize Docker configuration
  - Add a `docker-compose.test.yml` that runs Testcontainers integration tests inside Docker.
  - Add `CODEOWNERS` file and branch protection documentation.
  - Set up image tagging: `git sha` for all builds, `latest` for `main` branch.
  - Write deployment `README.md` section covering: env vars, first-run Flyway migration, health check endpoints.
  - _Requirements: all_

- [ ] 44. Final checkpoint — all tests pass
  - Run the full test suite: `mvn verify` (unit + jqwik property tests + integration via Testcontainers), `pnpm test --ci`.
  - Confirm `docker-compose up` brings the full stack online and the frontend is accessible at `http://localhost:3000`.
  - Confirm at least 20 Git commits exist representing the tasks above, each with a focused commit message.
  - Ask the user if questions arise before closing.

---

## Notes

- Tasks marked with `*` are optional testing sub-tasks and can be deferred for a faster MVP.
- Every task references specific requirements for traceability.
- Property tests use jqwik (`@Property(tries = 100)`) and are tagged `@Tag("flowforge")`.
- Checkpoints (tasks 11, 15, 22, 29) are mandatory — do not skip them.
- The implementation language for all code is **Java 21 (Spring Boot)** for the backend and **TypeScript (Next.js 14)** for the frontend.
