# Requirements Document

## Introduction

FlowForge is a configurable workflow orchestration platform that enables organizations to model, publish, and execute multi-step approval workflows with branching logic, parallel approvals, escalation rules, and a rich audit trail. The system provides a drag-and-drop workflow builder (React Flow canvas), a crash-safe execution engine, task and approval management, in-app and email notifications, immutable audit logs, and an analytics dashboard. It is built on a Spring Boot / PostgreSQL backend with a Next.js / TypeScript frontend and is delivered as a containerized application via Docker and GitHub Actions CI/CD.

---

## Glossary

- **User**: An authenticated person registered in the system with a name, email, password, role, and department.
- **Role**: A named set of permissions (e.g., ADMIN, MANAGER, EMPLOYEE) assigned to a User.
- **Department**: An organizational unit to which a User belongs.
- **Workflow**: A named, versioned process definition composed of nodes and edges.
- **Workflow_Version**: An immutable snapshot of a Workflow that is created each time the workflow is published.
- **Node**: A single step in a Workflow_Version, typed as Start, Task, Approval, Condition, Notification, or End.
- **Edge**: A directed connection between two Nodes in a Workflow_Version; may carry a condition expression.
- **Workflow_Instance**: A running execution of a specific Workflow_Version, initiated by a User.
- **Task**: An action item assigned to a User as part of a Workflow_Instance execution.
- **Approval**: A decision record (approve or reject) linked to a Task.
- **Notification**: An in-system or email message delivered to a User when a relevant event occurs.
- **Audit_Log**: An immutable record of a create, update, approve, reject, or delete action performed on any entity.
- **JWT**: JSON Web Token used for stateless authentication.
- **Refresh_Token**: A long-lived token used to obtain a new JWT access token without re-authentication.
- **Engine**: The Workflow_Engine component responsible for evaluating Nodes and advancing Workflow_Instances.
- **RBAC**: Role-Based Access Control — every API endpoint enforces the caller's Role.
- **AND_Join**: A synchronization point where all parallel branches must complete before execution continues.
- **Delegation**: Transferring a pending Task approval from one User to another for a defined period.

---

## Requirements

### Requirement 1: User Registration and Provisioning

**User Story:** As an administrator, I want to register and provision users with name, email, password, role, and department, so that the platform has a managed user base with appropriate access levels.

#### Acceptance Criteria

1. WHEN an administrator submits a valid registration payload containing name, email, password, role, and department, THE Authentication_System SHALL create a new User record and return the created user representation.
2. IF the submitted email already exists in the system, THEN THE Authentication_System SHALL return a 409 Conflict response with a descriptive error message.
3. IF any required field (name, email, password, role, or department) is absent or blank, THEN THE Authentication_System SHALL return a 400 Bad Request response listing each missing field.
4. THE Authentication_System SHALL store passwords as a cryptographic hash using bcrypt with a work factor of at least 12.
5. WHEN a user account is created, THE Audit_Log_System SHALL record an immutable create-user audit entry referencing the creator's identity.

---

### Requirement 2: Authentication and Token Issuance

**User Story:** As a registered user, I want to authenticate with my email and password and receive a signed JWT access token and refresh token, so that I can securely access protected API resources.

#### Acceptance Criteria

1. WHEN a user submits a valid email and password combination, THE Authentication_System SHALL issue a signed JWT access token and a refresh token in the response body.
2. WHEN a user submits an invalid email or incorrect password, THE Authentication_System SHALL return a 401 Unauthorized response without revealing which field was incorrect.
3. THE Authentication_System SHALL sign JWT access tokens with a configurable RSA or HMAC-SHA256 secret and include expiry, subject (user ID), and roles claims.
4. WHEN a user presents a valid refresh token, THE Authentication_System SHALL issue a new JWT access token and invalidate the previous refresh token (rotation).
5. WHEN a user logs out, THE Authentication_System SHALL invalidate the user's current refresh token so it cannot be reused.
6. THE Authentication_System SHALL enforce a configurable JWT access token expiry of no more than 60 minutes and a configurable refresh token expiry of no more than 30 days.

---

### Requirement 3: Role-Based Access Control

**User Story:** As a system architect, I want every API endpoint to enforce RBAC, so that users can only perform actions permitted by their assigned role.

#### Acceptance Criteria

1. WHEN a request is received, THE Authorization_System SHALL verify the JWT signature and extract the caller's role before permitting access to any protected endpoint.
2. IF the caller's role does not have permission for the requested endpoint, THEN THE Authorization_System SHALL return a 403 Forbidden response.
3. IF the JWT is absent, expired, or malformed, THEN THE Authorization_System SHALL return a 401 Unauthorized response.
4. THE Authorization_System SHALL apply role permissions consistently across all API endpoints without exception.

---

### Requirement 4: Account Management and Session Revocation

**User Story:** As an administrator, I want to deactivate or reactivate user accounts and immediately revoke all active sessions, so that I can respond to security events or personnel changes.

#### Acceptance Criteria

1. WHEN an administrator deactivates a user account, THE User_Management_System SHALL mark the account as inactive and invalidate all active refresh tokens for that user.
2. WHEN an authenticated request is received for an inactive user, THE Authorization_System SHALL return a 401 Unauthorized response.
3. WHEN an administrator reactivates a user account, THE User_Management_System SHALL restore the account to active status, allowing the user to log in again.
4. WHEN account status changes, THE Audit_Log_System SHALL record an immutable status-change audit entry.

---

### Requirement 5: Password Reset

**User Story:** As a registered user, I want to reset my password via a time-limited single-use token sent to my email, so that I can regain access when I forget my credentials.

#### Acceptance Criteria

1. WHEN a user requests a password reset for a registered email, THE Authentication_System SHALL generate a single-use reset token and send it to the user's email address within 5 minutes.
2. THE Authentication_System SHALL expire password reset tokens after a configurable duration of no more than 24 hours.
3. WHEN a user presents a valid, unexpired reset token and a new password, THE Authentication_System SHALL update the password and invalidate the token immediately.
4. IF a reset token has already been used or has expired, THEN THE Authentication_System SHALL return a 400 Bad Request response.
5. WHEN a password reset is completed, THE Authentication_System SHALL invalidate all active refresh tokens for that user.

---

### Requirement 6: Workflow Builder Canvas

**User Story:** As a workflow designer, I want a drag-and-drop canvas with typed nodes (Start, Task, Approval, Condition, Notification, End) and configurable conditional edges, so that I can visually model business processes.

#### Acceptance Criteria

1. THE Workflow_Builder SHALL present a canvas supporting drag-and-drop placement of nodes of types: Start, Task, Approval, Condition, Notification, and End.
2. WHEN a user connects two nodes, THE Workflow_Builder SHALL create a directed Edge between them and optionally accept a condition expression on the Edge.
3. WHEN a user configures a Condition node's outgoing edges, THE Workflow_Builder SHALL allow each edge to carry a boolean expression referencing request field values.
4. THE Workflow_Builder SHALL persist in-progress canvas state as a draft without requiring the workflow to be published.
5. WHEN a user saves a draft, THE Workflow_Service SHALL store the current nodes and edges without creating a new immutable version.

---

### Requirement 7: Workflow Validation and Publishing

**User Story:** As a workflow designer, I want the system to validate the workflow graph before publishing and to create an immutable version on each publish, so that running instances always reference a stable definition.

#### Acceptance Criteria

1. WHEN a user initiates a publish action, THE Workflow_Service SHALL validate that the graph contains exactly one Start node.
2. WHEN a user initiates a publish action, THE Workflow_Service SHALL validate that every node is reachable from the Start node.
3. WHEN a user initiates a publish action, THE Workflow_Service SHALL validate that there are no orphaned edges (edges without valid source or target nodes).
4. WHEN a user initiates a publish action, THE Workflow_Service SHALL validate that the graph contains at least one End node.
5. IF any validation rule is violated, THEN THE Workflow_Service SHALL return a 422 Unprocessable Entity response listing each violation.
6. WHEN all validations pass, THE Workflow_Service SHALL create an immutable Workflow_Version snapshot and mark it as the currently published version.
7. THE Workflow_Service SHALL preserve all prior Workflow_Versions so that active instances continue to reference their original definition.

---

### Requirement 8: Workflow Versioning and Cloning

**User Story:** As a workflow designer, I want to clone an existing workflow or version as a starting point and browse prior versions, so that I can evolve workflows without starting from scratch.

#### Acceptance Criteria

1. WHEN a user clones a workflow, THE Workflow_Service SHALL create a new draft Workflow copying all nodes and edges from the specified source version.
2. THE Workflow_Service SHALL assign a new unique identifier to the cloned Workflow and reset its version history.
3. WHEN a user requests the version history of a workflow, THE Workflow_Service SHALL return an ordered list of all Workflow_Versions with their publish timestamps and author.

---

### Requirement 9: Workflow Instance Creation and Execution

**User Story:** As a user, I want to submit a request that starts a workflow instance, so that the defined business process is initiated and tracked from start to finish.

#### Acceptance Criteria

1. WHEN a user submits a request against a Workflow, THE Engine SHALL instantiate a Workflow_Instance referencing the currently published Workflow_Version.
2. THE Engine SHALL evaluate the type of the current Node and perform the corresponding action (create Task, evaluate Condition, send Notification, etc.).
3. WHEN the Engine completes a node transition, THE Engine SHALL persist the current node identifier and instance status before advancing, ensuring the instance is resumable after a crash.
4. WHEN the Engine encounters a Condition node, THE Engine SHALL evaluate each outgoing edge's condition expression against the instance's request data and follow the first matching edge.
5. IF no outgoing edge condition matches at a Condition node, THEN THE Engine SHALL mark the Workflow_Instance status as ERROR and record a descriptive audit entry.

---

### Requirement 10: Parallel Approval Branches (AND-Join)

**User Story:** As a workflow designer, I want to model parallel approval branches that must all complete before execution continues, so that I can require sign-off from multiple parties simultaneously.

#### Acceptance Criteria

1. WHEN a Node with multiple outgoing edges is traversed, THE Engine SHALL activate all target nodes simultaneously as parallel branches.
2. WHEN an AND_Join node is reached, THE Engine SHALL wait until all parallel incoming branches have completed before advancing.
3. THE Engine SHALL track per-branch completion status independently so that any branch can complete in any order.

---

### Requirement 11: Timeout and Escalation

**User Story:** As a workflow administrator, I want tasks to escalate or be reassigned automatically after a configurable timeout, so that workflows do not stall due to inaction.

#### Acceptance Criteria

1. WHEN a Task Node is configured with a timeout duration, THE Engine SHALL start a timer when the Task is created.
2. WHEN the timeout expires and the Task remains incomplete, THE Engine SHALL execute the configured escalation action: reassign the task to the escalation target user or role.
3. WHEN an escalation occurs, THE Notification_System SHALL send an in-app and email notification to the new assignee and the original assignee.
4. THE Engine SHALL record an escalation audit entry when a Task is escalated.

---

### Requirement 12: Task List and Filtering

**User Story:** As a user, I want to view my assigned tasks filtered by status, workflow, or date, so that I can efficiently manage my workload.

#### Acceptance Criteria

1. WHEN a user requests their task list, THE Task_Service SHALL return all Tasks assigned to that user.
2. WHEN filter parameters (status, workflow ID, date range) are supplied, THE Task_Service SHALL return only Tasks matching all supplied filters.
3. THE Task_Service SHALL return task results in reverse chronological order by default.

---

### Requirement 13: Task Decisions (Approve / Reject)

**User Story:** As a manager, I want to approve or reject tasks with a mandatory comment on rejection, so that decisions are recorded with justification.

#### Acceptance Criteria

1. WHEN a manager approves a Task, THE Task_Service SHALL record an Approval with decision APPROVED and advance the Workflow_Instance to the next node.
2. WHEN a manager rejects a Task, THE Task_Service SHALL require a non-empty comment and record an Approval with decision REJECTED.
3. WHEN a Task is rejected, THE Engine SHALL follow the configured rejection path in the workflow graph, or mark the Workflow_Instance as REJECTED if no rejection path exists.
4. IF a manager attempts to decide on a Task not assigned to them and they do not hold the ADMIN role, THEN THE Task_Service SHALL return a 403 Forbidden response.

---

### Requirement 14: File Attachments

**User Story:** As a user, I want to attach files to requests and tasks within configurable size and type limits, so that supporting documentation travels with the workflow.

#### Acceptance Criteria

1. WHEN a user uploads a file attachment to a Workflow_Instance, THE Attachment_Service SHALL accept the file if it is within the configured size limit and of an allowed MIME type.
2. IF the file exceeds the configured size limit, THEN THE Attachment_Service SHALL return a 413 Payload Too Large response.
3. IF the file MIME type is not on the allowed list, THEN THE Attachment_Service SHALL return a 415 Unsupported Media Type response.

---

### Requirement 15: Threaded Comments

**User Story:** As a workflow participant, I want to post and read threaded comments on a request, so that all stakeholders can communicate in context.

#### Acceptance Criteria

1. WHEN a participant posts a comment on a Workflow_Instance, THE Comment_Service SHALL store the comment with the author's identity and timestamp.
2. THE Comment_Service SHALL return all comments for a Workflow_Instance ordered chronologically.
3. WHEN a comment is posted, THE Comment_Service SHALL only return it to users who are participants of that Workflow_Instance.

---

### Requirement 16: Task Delegation

**User Story:** As a manager, I want to delegate my pending approval tasks to another user for a defined period, so that work continues during my absence.

#### Acceptance Criteria

1. WHEN a user delegates their pending Tasks to a delegate user for a defined period, THE Task_Service SHALL reassign all current pending Tasks and set a delegation record for the defined period.
2. WHEN a new Task is assigned to the delegating user during the active delegation period, THE Task_Service SHALL redirect assignment to the delegate user.
3. WHEN the delegation period expires, THE Task_Service SHALL restore assignment routing to the original user.

---

### Requirement 17: In-App and Email Notifications

**User Story:** As a user, I want to receive in-app and email notifications for task assignments, approvals, rejections, and escalations, so that I stay informed about workflow activity.

#### Acceptance Criteria

1. WHEN a Task is assigned to a user, THE Notification_System SHALL create an in-app Notification for the assignee.
2. WHEN a Task is approved or rejected, THE Notification_System SHALL create an in-app Notification for the request initiator.
3. WHEN a Task is escalated, THE Notification_System SHALL create in-app Notifications for both the previous and new assignees.
4. WHEN an in-app Notification is created for an event for which the user has enabled email delivery, THE Notification_System SHALL send an email to the user using the configured template for that event type.
5. THE Notification_System SHALL deliver emails within 5 minutes of the triggering event under normal system load.

---

### Requirement 18: Notification Preferences and Read Status

**User Story:** As a user, I want to mark notifications as read and configure which events trigger email delivery, so that I control my notification experience.

#### Acceptance Criteria

1. WHEN a user marks a Notification as read, THE Notification_System SHALL update the Notification's read status immediately.
2. THE Notification_System SHALL allow each user to individually enable or disable email delivery per event type.
3. WHEN a user retrieves their notification list, THE Notification_System SHALL return notifications ordered by creation time with unread items first.

---

### Requirement 19: Immutable Audit Logs

**User Story:** As a compliance officer, I want every create, update, approve, reject, and delete action to produce an immutable audit log entry, so that there is a tamper-evident record of all system activity.

#### Acceptance Criteria

1. WHEN any create, update, approve, reject, or delete action is performed on any entity, THE Audit_Log_System SHALL record an Audit_Log entry containing: actor user ID, action type, entity type, entity ID, timestamp, and a JSON diff of before/after state.
2. THE Audit_Log_System SHALL prevent modification or deletion of any Audit_Log entry.
3. WHEN an administrator searches audit logs by user, entity type, date range, or action, THE Audit_Log_System SHALL return only matching entries.
4. WHEN an administrator exports audit logs, THE Audit_Log_System SHALL produce a downloadable CSV file containing all filtered results.

---

### Requirement 20: User Dashboard

**User Story:** As a user, I want a personal dashboard showing my pending tasks, submitted requests, and recent activity, so that I have a single view of my workflow involvement.

#### Acceptance Criteria

1. WHEN a user requests their dashboard, THE Report_Service SHALL return the count and list of Tasks pending that user's action.
2. WHEN a user requests their dashboard, THE Report_Service SHALL return the list of Workflow_Instances initiated by that user with their current status.
3. WHEN a user requests their dashboard, THE Report_Service SHALL return the most recent 20 audit events related to that user.

---

### Requirement 21: Analytics and Aggregate Metrics

**User Story:** As an administrator or manager, I want aggregate metrics on workflow performance including average approval time, bottleneck stages, volume, and rejection rate, so that I can optimize processes.

#### Acceptance Criteria

1. WHEN an admin or manager requests workflow performance metrics, THE Report_Service SHALL return average approval time per workflow and per node.
2. WHEN an admin or manager requests workflow performance metrics, THE Report_Service SHALL identify nodes with the highest average dwell time as bottleneck stages.
3. WHEN an admin or manager requests workflow performance metrics, THE Report_Service SHALL return total instance volume and rejection rate per workflow.
4. WHEN filter parameters (department, workflow ID, date range) are supplied, THE Report_Service SHALL apply those filters before computing all metrics.
5. THE Report_Service SHALL support exporting filtered metric data as CSV or JSON.
