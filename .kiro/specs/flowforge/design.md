# Design Document — FlowForge

## Overview

FlowForge is a configurable workflow orchestration platform. The system is divided into two runnable applications:

1. **Backend** — a Spring Boot (Java 21) REST API backed by PostgreSQL, using JPA/Hibernate for persistence, Spring Security + JWT for authentication, MapStruct for DTO mapping, Lombok for boilerplate reduction, and Flyway for database migrations.
2. **Frontend** — a Next.js 14 (TypeScript) application using Tailwind CSS for styling, React Flow for the drag-and-drop workflow canvas, and React Query for server-state management.

Both applications are containerized with Docker and orchestrated locally with docker-compose. A GitHub Actions pipeline handles CI (lint, test, build) and CD (Docker image push).

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                          Browser / Client                       │
│  Next.js 14 (TypeScript)  ·  React Flow  ·  React Query        │
│  Tailwind CSS                                                   │
└──────────────────────┬──────────────────────────────────────────┘
                       │ HTTPS  REST (JSON)
┌──────────────────────▼──────────────────────────────────────────┐
│                    Spring Boot API (Java 21)                     │
│  Spring Security (JWT)  ·  Spring Web MVC                       │
│  Spring Data JPA / Hibernate  ·  MapStruct  ·  Lombok           │
│  Spring Mail  ·  Spring Scheduling (escalation timers)          │
└──────┬───────────────┬───────────────┬───────────────┬──────────┘
       │               │               │               │
┌──────▼──┐      ┌─────▼────┐   ┌──────▼──┐    ┌──────▼────────┐
│PostgreSQL│      │File Store│   │SMTP/Mail│    │Spring @Async  │
│(primary) │      │(local FS │   │ relay   │    │task executor  │
│          │      │ / S3)    │   │         │    │(notifications,│
└──────────┘      └──────────┘   └─────────┘    │ escalation)   │
                                                 └───────────────┘
```

### Key Design Decisions

- **Stateless JWT authentication**: The API is stateless; authorization decisions rely solely on the signed token claims plus a revocation check (database flag for deactivated users / invalidated refresh tokens).
- **AOP-based audit logging**: A Spring AOP aspect intercepts all service-layer write operations and emits `AuditLog` entries, so audit coverage is guaranteed without polluting business logic.
- **Engine as a synchronous state machine**: Each `WorkflowEngineService.advance()` call executes within a single database transaction — read current node → execute node action → persist new state. This guarantees crash-safety via PostgreSQL's ACID properties.
- **AND-Join via branch completion counter**: Parallel branches are tracked in a `branch_status` JSON column on `WorkflowInstance`; the AND-join node checks whether all expected branches are marked complete before advancing.
- **Flyway for schema migrations**: All DDL changes are versioned migration scripts under `src/main/resources/db/migration`.

---

## Components and Interfaces

### Backend Package Structure

```
com.flowforge
├── auth
│   ├── AuthController          POST /api/auth/*
│   ├── AuthService             login, refresh, logout, password-reset
│   ├── JwtTokenProvider        sign / parse / validate JWT
│   └── PasswordResetToken      entity + repository
├── user
│   ├── UserController          GET/POST/PATCH /api/users
│   ├── UserService             CRUD + deactivation
│   ├── UserRepository          Spring Data JPA
│   ├── User                    entity
│   ├── Role                    entity
│   └── Department              entity
├── workflow
│   ├── WorkflowController      /api/workflows
│   ├── WorkflowService         draft save, clone
│   ├── WorkflowVersionService  validate, publish, version list
│   ├── WorkflowNode            entity
│   ├── WorkflowEdge            entity
│   ├── WorkflowVersion         entity
│   └── Workflow                entity
├── engine
│   ├── WorkflowEngineService   instantiate, advance, AND-join
│   ├── NodeExecutorFactory     factory → NodeExecutor
│   └── executors
│       ├── StartNodeExecutor
│       ├── TaskNodeExecutor
│       ├── ApprovalNodeExecutor
│       ├── ConditionNodeExecutor
│       ├── NotificationNodeExecutor
│       └── EndNodeExecutor
├── task
│   ├── TaskController          /api/tasks, /api/instances
│   ├── TaskService             list, filter, decide, delegate
│   ├── ApprovalService         record decision
│   └── entities: Task, Approval, Comment, Attachment
├── notification
│   ├── NotificationService     create in-app, check prefs
│   ├── EmailSender             Spring Mail wrapper
│   └── NotificationTemplateResolver  Thymeleaf templates
├── audit
│   ├── AuditLogAspect          @Around service writes
│   ├── AuditLogService         persist, search, export CSV
│   └── AuditLogController      GET /api/audit-logs, export
├── report
│   ├── ReportController        /api/reports/*
│   └── ReportService           dashboard, performance metrics
└── common
    ├── exception               GlobalExceptionHandler, AppException
    ├── response                ApiResponse<T> wrapper
    └── mapper                  MapStruct base config
```

### REST API Surface

| Method | Path | Role | Description |
|--------|------|------|-------------|
| POST | /api/auth/login | public | Authenticate, issue JWT + refresh |
| POST | /api/auth/refresh | public | Rotate refresh token |
| POST | /api/auth/logout | any | Invalidate refresh token |
| POST | /api/auth/password-reset/request | public | Send reset email |
| POST | /api/auth/password-reset/confirm | public | Apply new password |
| GET/POST | /api/users | ADMIN | List / create users |
| GET/PATCH | /api/users/{id} | ADMIN | Get / update user |
| PATCH | /api/users/{id}/status | ADMIN | Activate / deactivate |
| GET | /api/users/me | any | Current user profile |
| GET/POST | /api/workflows | ADMIN,MANAGER | List / create workflow |
| GET | /api/workflows/{id} | ADMIN,MANAGER | Get workflow with versions |
| PUT | /api/workflows/{id}/versions/{vId} | ADMIN,MANAGER | Save draft version |
| POST | /api/workflows/{id}/versions/{vId}/publish | ADMIN | Publish version |
| POST | /api/workflows/{id}/clone | ADMIN,MANAGER | Clone workflow |
| POST | /api/workflows/{id}/instances | any | Start workflow instance |
| GET | /api/instances/{id} | participant | Get instance details |
| POST | /api/instances/{id}/cancel | ADMIN,initiator | Cancel instance |
| GET | /api/tasks | any | List my tasks (filtered) |
| PATCH | /api/tasks/{id}/decision | MANAGER,ADMIN | Approve / reject |
| POST | /api/tasks/{id}/delegate | any | Delegate tasks |
| POST | /api/instances/{id}/comments | participant | Post comment |
| POST | /api/instances/{id}/attachments | participant | Upload attachment |
| GET | /api/notifications | any | List my notifications |
| PATCH | /api/notifications/{id}/read | any | Mark read |
| GET | /api/audit-logs | ADMIN | Search audit logs |
| GET | /api/audit-logs/export | ADMIN | Download CSV |
| GET | /api/reports/dashboard | any | Personal dashboard |
| GET | /api/reports/workflow/{id}/performance | ADMIN,MANAGER | Performance metrics |

---

## Data Models

### Entity Relationship (abbreviated)

```
users ──< roles            (FK: users.role_id → roles.id)
users ──< departments      (FK: users.department_id → departments.id)

workflows ──< workflow_versions
workflow_versions ──< workflow_nodes
workflow_versions ──< workflow_edges

workflow_instances >── workflow_versions
workflow_instances >── users  (initiated_by)

tasks >── workflow_instances
tasks >── workflow_nodes
tasks >── users  (assigned_to)

approvals >── tasks   (UNIQUE: one approval per task)
approvals >── users   (approver_id)

comments >── workflow_instances
comments >── users

attachments >── workflow_instances

notifications >── users

audit_logs >── users  (actor_id)
              entity_type + entity_id  (polymorphic)
```

### Key Table Schemas

```sql
-- users
id UUID PK, name VARCHAR, email VARCHAR UNIQUE, password_hash VARCHAR,
role_id UUID FK, department_id UUID FK, is_active BOOLEAN DEFAULT true,
created_at TIMESTAMP, updated_at TIMESTAMP

-- workflow_versions
id UUID PK, workflow_id UUID FK, version_number INT,
graph_json JSONB,   -- serialized nodes + edges for snapshot
published_at TIMESTAMP, published_by UUID FK, is_current BOOLEAN

-- workflow_instances
id UUID PK, workflow_version_id UUID FK, initiated_by UUID FK,
current_node_id UUID, status VARCHAR(32),  -- RUNNING | COMPLETED | REJECTED | ERROR | CANCELLED
request_data JSONB, branch_status JSONB,
created_at TIMESTAMP, updated_at TIMESTAMP

-- tasks
id UUID PK, instance_id UUID FK, node_id UUID FK,
assigned_to UUID FK, status VARCHAR(32),  -- PENDING | COMPLETED | DELEGATED | ESCALATED
due_at TIMESTAMP, created_at TIMESTAMP

-- approvals
id UUID PK, task_id UUID FK UNIQUE, approver_id UUID FK,
decision VARCHAR(16),  -- APPROVED | REJECTED
comment TEXT, decided_at TIMESTAMP

-- audit_logs
id UUID PK, actor_id UUID FK, action VARCHAR(64),
entity_type VARCHAR(64), entity_id UUID,
before_state JSONB, after_state JSONB,
created_at TIMESTAMP  -- NO updated_at, NO soft-delete
```

### DTO Layer (MapStruct)

Every entity has a corresponding request DTO (input), response DTO (output), and a MapStruct mapper interface. DTOs are immutable Java records where possible (Java 21). Validation annotations (`@NotBlank`, `@Email`, `@Size`) are placed on request DTOs and enforced via `@Valid` in controllers.

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Valid Registration Always Creates a User

*For any* valid registration payload (distinct non-empty name, valid email, password ≥ 8 chars, valid role, valid department), submitting it to the registration endpoint SHALL result in a User record being persisted with a non-plaintext password_hash.

**Validates: Requirements 1.1, 1.4**

---

### Property 2: Registration Rejects Payloads with Missing Required Fields

*For any* registration payload that is missing at least one required field (name, email, password, role, or department), the system SHALL return a 400 Bad Request response and NOT create a User record.

**Validates: Requirements 1.3**

---

### Property 3: JWT Claims Match Issuing User

*For any* registered, active user who successfully authenticates, the issued JWT SHALL contain claims whose `sub` equals the user's ID and whose `roles` claim contains exactly the user's assigned role.

**Validates: Requirements 2.1, 2.3**

---

### Property 4: Refresh Token Single-Use Enforcement

*For any* valid refresh token, using it once SHALL produce a new access token and invalidate the original refresh token, so that a second use of the same refresh token SHALL return a 401 Unauthorized response.

**Validates: Requirements 2.4**

---

### Property 5: RBAC Enforcement Across All Endpoints

*For any* (role, endpoint) combination where the role is not permitted, a request with a valid JWT for that role SHALL receive a 403 Forbidden response, and for any missing or invalid JWT on a protected endpoint, the response SHALL be 401 Unauthorized.

**Validates: Requirements 3.1, 3.3**

---

### Property 6: Deactivation Immediately Blocks Access

*For any* active user whose account is subsequently deactivated, any API request using that user's JWT or refresh token SHALL be rejected with 401 Unauthorized until the account is reactivated.

**Validates: Requirements 4.1, 4.2, 4.3**

---

### Property 7: Password Reset Token is Single-Use

*For any* valid password reset token, submitting it once with a new password SHALL succeed and subsequently submitting the same token again SHALL return a 400 Bad Request response.

**Validates: Requirements 5.3**

---

### Property 8: Workflow Graph Validation is Exhaustive

*For any* workflow graph that violates at least one of the four structural rules (exactly one Start, all nodes reachable, no orphaned edges, at least one End), the publish endpoint SHALL return a 422 Unprocessable Entity response listing each violated rule, and SHALL NOT create a new Workflow_Version.

**Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5**

---

### Property 9: Published Version Immutability

*For any* Workflow_Version that has been published, subsequent publish operations on the same workflow SHALL create a new Workflow_Version with an incremented version number and SHALL NOT modify the existing published version's `graph_json`.

**Validates: Requirements 7.6, 7.7**

---

### Property 10: Condition Evaluation Routes to Correct Edge

*For any* Condition node with N outgoing edges each bearing a distinct predicate, and for any request data payload, the Engine SHALL follow exactly the first edge whose predicate evaluates to true against that payload, and SHALL mark the instance as ERROR if no predicate matches.

**Validates: Requirements 9.4, 9.5**

---

### Property 11: AND-Join Requires All Branches Before Advancing

*For any* workflow with K parallel branches converging at an AND-Join node, the Engine SHALL NOT advance past the AND-Join until exactly K branches have reported completion; completing K-1 branches SHALL leave the instance in RUNNING status at the join node.

**Validates: Requirements 10.2, 10.3**

---

### Property 12: Task Rejection Requires Non-Empty Comment

*For any* task rejection decision, the system SHALL refuse the request and return a 400 Bad Request response if the comment field is absent or contains only whitespace.

**Validates: Requirements 13.2**

---

### Property 13: File Upload Enforces Size and Type Limits

*For any* file upload request, if the file size exceeds the configured limit the system SHALL return 413, and if the MIME type is not on the allowlist the system SHALL return 415, regardless of the uploader's identity or role.

**Validates: Requirements 14.1, 14.2, 14.3**

---

### Property 14: Audit Log Completeness

*For any* create, update, approve, reject, or delete operation performed on any entity, exactly one Audit_Log entry SHALL be written containing the correct actor, action type, entity type, entity ID, and timestamp.

**Validates: Requirements 19.1**

---

### Property 15: Audit Log Immutability

*For any* Audit_Log entry, no API endpoint or service method SHALL permit modification or deletion of that entry after it is written.

**Validates: Requirements 19.2**

---

### Property 16: Metrics Computation Correctness

*For any* set of completed Workflow_Instances with known start and end timestamps, the Report_Service SHALL compute average approval time values that match the arithmetic mean calculated from those timestamps, and SHALL correctly identify the node with the highest mean dwell time as the bottleneck.

**Validates: Requirements 21.1, 21.2**

---

## Error Handling

### Global Exception Handler

`GlobalExceptionHandler` (annotated `@RestControllerAdvice`) maps exceptions to standard `ApiResponse<Void>` error envelopes:

| Exception | HTTP Status |
|-----------|------------|
| `EntityNotFoundException` | 404 Not Found |
| `AccessDeniedException` | 403 Forbidden |
| `AuthenticationException` | 401 Unauthorized |
| `ValidationException` (`@Valid` failures) | 400 Bad Request (with field errors) |
| `DuplicateResourceException` | 409 Conflict |
| `WorkflowValidationException` | 422 Unprocessable Entity (with rule violations list) |
| `FileSizeLimitException` | 413 Payload Too Large |
| `UnsupportedMediaTypeException` | 415 Unsupported Media Type |
| `RuntimeException` / `Exception` (fallback) | 500 Internal Server Error |

### Response Envelope

```java
public record ApiResponse<T>(
    boolean success,
    String message,
    T data,
    List<FieldError> errors   // non-null only on 400
) {}
```

### Engine Error Handling

- Any unrecoverable error during node execution sets `WorkflowInstance.status = ERROR` and writes an audit entry before re-throwing.
- Escalation failures are logged but do not crash the main execution path.

---

## Testing Strategy

### Backend

**Unit Tests (JUnit 5 + Mockito)**

- Service-layer classes are tested in isolation with mocked repositories and collaborators.
- Focus: business logic (engine transitions, condition evaluation, RBAC decisions, graph validation algorithms).
- Run on every PR via GitHub Actions; must pass before merge.

**Property-Based Tests (jqwik)**

- [jqwik](https://jqwik.net/) is the property-based testing library for Java (JUnit 5 native).
- Each correctness property (Properties 1–16 above) has a corresponding `@Property` test class.
- Minimum 100 tries per property (`@Property(tries = 100)`).
- Tag format: `@Tag("flowforge") @Label("Property N: <property_text>")` on each test method.
- Generators: `@ForAll` parameters with `@StringLength`, `@IntRange`, `@From` custom arbitraries as needed.

**Integration Tests (Testcontainers + Spring Boot Test)**

- Full Spring context loaded against a real PostgreSQL container (`@Testcontainers`).
- Cover: authentication flows end-to-end, workflow instance creation, approval decisions, audit log writes.
- Run on `main` branch merges and nightly.

**Contract Tests**

- Spring MVC Test (`MockMvc`) for all controller endpoints, verifying HTTP status codes, response shape, and security constraints without a full container.

### Frontend

**Unit / Component Tests (Jest + React Testing Library)**

- All React components tested in isolation with mocked API clients.
- Focus: form validation, workflow canvas interactions (node add/remove, edge connect), notification badge counts.

**Integration Tests (Jest + MSW)**

- Mock Service Worker intercepts API calls; tests drive full page flows (login → create workflow → publish → start instance).

### CI/CD (GitHub Actions)

```yaml
Triggers: push to any branch, pull_request to main

Jobs:
  backend-test:
    - mvn verify  (unit + jqwik property tests)
  backend-integration:
    - mvn verify -P integration  (Testcontainers)
  frontend-test:
    - pnpm test --ci --passWithNoTests
  build:
    - docker build backend / frontend
    - push images (main branch only)
```

**Property Test Configuration**

```java
// Example — applied to all property tests
@Property(tries = 100)
@Tag("flowforge")
@Label("Property N: <property description>")
void property_name(@ForAll ... inputs) {
    // arrange → act → assert
}
```
