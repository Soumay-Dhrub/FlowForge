# FlowForge

[![CI](https://github.com/Soumay-Dhrub/FlowForge/actions/workflows/ci.yml/badge.svg)](https://github.com/Soumay-Dhrub/FlowForge/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.2.5](https://img.shields.io/badge/Spring%20Boot-3.2.5-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![Next.js 14](https://img.shields.io/badge/Next.js-14.2.3-black.svg)](https://nextjs.org/)
[![PostgreSQL 15](https://img.shields.io/badge/PostgreSQL-15-336791.svg)](https://www.postgresql.org/)

A workflow orchestration platform for multi-step approval processes. Business users draw a workflow on
a drag-and-drop canvas — approvals, conditional branches, parallel paths — publish it as an immutable
version, and the engine runs every submission against it, handling assignment, delegation, escalation,
notifications and a tamper-evident audit trail.

```
Draw the workflow  →  Publish a version  →  Submit a request  →  Engine routes it
                                                                  ├─ assigns tasks
                                                                  ├─ escalates overdue work
                                                                  ├─ notifies participants
                                                                  └─ records every change
```

## Contents

- [Features](#features)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Quick start](#quick-start)
- [First sign-in](#first-sign-in)
- [Local development](#local-development)
- [Configuration](#configuration)
- [Testing](#testing)
- [API](#api)
- [Project structure](#project-structure)
- [Deployment](#deployment)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)

## Features

**Visual workflow builder.** Seven node types on a React Flow canvas: `START`, `TASK`, `APPROVAL`,
`CONDITION`, `NOTIFICATION`, `AND_JOIN`, `END`. Conditions are SpEL expressions evaluated against the
submitted payload, in a locked-down context that cannot reach beans, constructors or types.

**Immutable versioning.** Publishing freezes a version and validates the graph first: exactly one
start node, every node reachable, no orphaned edges, at least one end node. All violations are
reported together rather than one per attempt. A running instance stays bound to the version it
started on, so publishing never changes work already in flight.

**Execution engine.** A synchronous state machine, one transaction per step, so an instance is only
ever observed at a node it genuinely reached. Supports conditional routing and parallel fan-out with
an AND-join that waits for every inbound branch.

**Human tasks.** Assignment by user or by role, per-node deadlines, automatic escalation of overdue
work, delegation for a date window with overlap and cycle detection, threaded comments, and file
attachments with type and size gates.

**Notifications.** In-app inbox plus optional email via Thymeleaf templates, with per-user per-event
preferences. Email failure is isolated by contract and can never roll back the decision that
triggered it.

**Audit trail.** Append-only, enforced at the database level. Services record domain-specific entries;
an AOP aspect covers anything that does not, and de-duplicates so one action yields one row.

**Reporting.** Dashboard plus per-workflow performance: approval times, rejection rates, per-node
dwell times and bottleneck detection with a minimum-sample threshold. CSV export for audit logs and
performance reports.

**Auth and RBAC.** JWT access tokens with single-use rotating refresh tokens, BCrypt at strength 12,
password reset over email, and three roles (`ADMIN`, `MANAGER`, `EMPLOYEE`) enforced per endpoint.

## Architecture

```
┌─────────────────────────────┐
│ Next.js 14 (App Router)     │  React Flow canvas · TanStack Query · Tailwind
│ browser                     │
└──────────────┬──────────────┘
               │ same-origin /api/*  (rewritten server-side, so no CORS)
┌──────────────▼──────────────┐
│ Spring Boot 3.2.5           │
│  JwtAuthenticationFilter    │  stateless, @PreAuthorize per endpoint
│  Controllers → Services     │  DTO mapping via MapStruct
│  Workflow engine            │  NodeExecutor per node type
│  AuditLogAspect             │  AOP net over service writes
└──────┬───────────────┬──────┘
       │               │
┌──────▼──────┐  ┌─────▼─────┐
│ PostgreSQL  │  │   SMTP    │  MailHog locally
│ Flyway V1-4 │  └───────────┘
│ attachments │  bytes on a volume, metadata in the database
└─────────────┘
```

The frontend calls the relative path `/api`, which the Next.js server rewrites to the backend. Both
therefore share one origin and the backend ships no CORS configuration — worth knowing before pointing
the client at a cross-origin API.

## Tech stack

| Layer | Technology |
|---|---|
| Frontend | Next.js 14.2.3, React 18.3, TypeScript 5.4, Tailwind CSS, React Flow (`@xyflow/react`), TanStack Query 5, react-hook-form + zod |
| Backend | Spring Boot 3.2.5 (Java 21), Spring Security + JJWT, Spring Data JPA, MapStruct, Lombok, Spring AOP, Thymeleaf |
| Database | PostgreSQL 15, Flyway migrations |
| Testing | JUnit 5, Mockito, AssertJ, jqwik (property-based), Testcontainers, Jest + Testing Library |
| Build | Maven 3.9+, pnpm (pinned via `packageManager`) |
| Infrastructure | Docker, Docker Compose, GitHub Actions |

## Quick start

Only Docker is required — both applications build inside containers.

```bash
git clone https://github.com/Soumay-Dhrub/FlowForge.git
cd FlowForge
docker compose up --build
```

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| API | http://localhost:8080/api |
| Health | http://localhost:8080/actuator/health |

No `.env` is needed to start: every value has a working default. Copy `.env.example` to `.env` when you
need to change a port or set a real `JWT_SECRET`.

`pgadmin` (5050) and `mailhog` (8025) are gated behind the `dev` profile:

```bash
docker compose --profile dev up        # or set COMPOSE_PROFILES=dev in .env
```

## First sign-in

A clean database contains the three roles and a `General` department but **no users**. There is no
public registration endpoint, and creating a user requires an existing `ADMIN` — so on first start the
backend creates one administrator and prints a generated password once:

```bash
docker compose logs backend | grep -A6 "first administrator"
```

```
FlowForge had no users, so a first administrator was created.
  email:    admin@flowforge.local
  password: <a random 32-character password appears here>
```

Sign in with that and change it, or set `BOOTSTRAP_ADMIN_PASSWORD` before the first start to choose
your own. The password is generated rather than defaulted on purpose: a fixed default committed to a
public repository would be a known credential on every deployment. The bootstrap runs only while the
`users` table is empty, so it can never alter an existing installation.

## Local development

### Backend

```bash
docker compose up -d postgres      # database only

cd backend
DB_URL=jdbc:postgresql://localhost:5432/flowforge \
DB_USERNAME=flowforge \
DB_PASSWORD=flowforge \
mvn spring-boot:run
```

<details>
<summary>Windows PowerShell</summary>

```powershell
cd backend
$env:DB_URL="jdbc:postgresql://localhost:5432/flowforge"
$env:DB_USERNAME="flowforge"
$env:DB_PASSWORD="flowforge"
mvn spring-boot:run
```
</details>

The backend compiles to Java 21 bytecode but builds and tests on any JDK from 21 upward, so there is
nothing to configure. For exact parity with CI and the runtime image, `mvn -Pjdk21-toolchain verify`
runs the build on a real JDK 21; that profile needs a `~/.m2/toolchains.xml` entry, which is why it is
opt-in rather than automatic.

### Frontend

```bash
cd frontend
corepack pnpm install     # or: pnpm install
corepack pnpm dev         # or: pnpm dev
```

`corepack` ships with Node and uses the pnpm version pinned in `package.json`, so no global install is
needed. The dev server proxies `/api/*` to `http://localhost:8080`; override with `API_PROXY_TARGET`
in `.env.local`.

### Prerequisites for running outside Docker

- **Java 21+** (`sdk install java 21-tem`) and **Maven 3.9+**
- **Node.js 20+**

## Configuration

Backend variables, all read by `backend/src/main/resources/application.yml`:

| Variable | Default | Description |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/flowforge` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `flowforge` / `flowforge` | Credentials |
| `JWT_SECRET` | *(placeholder — change it)* | HS256 key, minimum 256 bits |
| `JWT_ACCESS_EXPIRY_MS` | `900000` | Access token TTL (15 min) |
| `JWT_REFRESH_EXPIRY_MS` | `2592000000` | Refresh token TTL (30 days) |
| `BOOTSTRAP_ADMIN_ENABLED` | `true` | Create a first administrator when no users exist |
| `BOOTSTRAP_ADMIN_EMAIL` | `admin@flowforge.local` | Address of that account |
| `BOOTSTRAP_ADMIN_PASSWORD` | *(empty)* | Empty generates one and logs it once |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` | SMTP host (MailHog by default) |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | *(empty)* | SMTP credentials |
| `MAIL_FROM` | `no-reply@flowforge.local` | Envelope sender |
| `WEB_BASE_URL` | `http://localhost:3000` | Base URL in notification email links |
| `PASSWORD_RESET_URL` | `http://localhost:3000/reset-password` | Page that collects the new password |
| `PASSWORD_RESET_EXPIRY_MS` | `86400000` | Reset token TTL, clamped to 24h |
| `ATTACHMENT_MAX_SIZE` | `10485760` | Per-file limit in bytes (10 MiB), enforced while writing |
| `ATTACHMENT_MAX_UPLOAD_SIZE` | `11MB` | Servlet limit, deliberately above the above so an oversized file gets a descriptive 413 rather than a truncated stream |
| `ATTACHMENT_MAX_REQUEST_SIZE` | `12MB` | Servlet limit for the whole multipart request |
| `ATTACHMENT_STORAGE_PATH` | `./var/attachments` | Root for attachment bytes; needs a volume in production |

Frontend variables live in `frontend/.env.local.example`. Next.js inlines `NEXT_PUBLIC_*` at build
time, so they cannot be supplied to a running container.

Generate a real secret with:

```bash
openssl rand -base64 48
```

The backend refuses to start on a secret shorter than 256 bits, and logs a `SECURITY` warning for as
long as the committed placeholder is in use.

## Testing

```bash
cd backend
mvn verify                    # 383 unit + jqwik property tests, no Docker required
mvn verify -Pintegration      # 30 integration tests against a real PostgreSQL container
```

```bash
cd frontend
corepack pnpm run test:ci     # 19 suites, 120 tests
corepack pnpm run lint
corepack pnpm exec tsc --noEmit
```

Surefire excludes the `integration` group by default, which is why the two backend commands differ.
Integration tests use Testcontainers and share one PostgreSQL container across all classes.

## API

40 endpoints under `/api`. All require a bearer token except `/api/auth/**` and
`/actuator/health`, and each is guarded by `@PreAuthorize`.

| Group | Endpoints |
|---|---|
| `/api/auth` | `POST` login, logout, refresh, password-reset/request, password-reset/confirm |
| `/api/users` | `GET` list, `GET` `{id}`, `GET` me, `POST` create, `PATCH` `{id}`, `PATCH` `{id}/status` |
| `/api/users/me/notification-preferences` | `GET`, `PUT` |
| `/api/roles`, `/api/departments` | `GET` reference data |
| `/api/workflows` | `GET` list, `GET` `{id}`, `POST` create, `POST` `{id}/clone`, `PUT` version, `POST` publish |
| `/api/workflows/{id}/instances` | `POST` submit a request |
| `/api/instances` | `GET` list, `GET` `{id}`, `POST` `{id}/cancel` |
| `/api/instances/{id}/comments` | `GET`, `POST` |
| `/api/instances/{id}/attachments` | `GET`, `POST` |
| `/api/tasks` | `GET` list, `GET` `{id}`, `PATCH` `{id}/decision`, `POST` `{id}/delegate` |
| `/api/notifications` | `GET` list, `GET` unread-count, `PATCH` `{id}/read` |
| `/api/reports` | `GET` dashboard, `GET` workflow performance |
| `/api/audit-logs` | `GET` search, `GET` export CSV |

Responses use a consistent envelope:

```json
{ "success": true, "data": { } }
{ "success": false, "message": "Invalid email or password" }
{ "success": false, "message": "Validation failed", "errors": [ { "field": "password", "message": "..." } ] }
```

## Project structure

```
FlowForge/
├── backend/                        Spring Boot API (Java 21 / Maven)
│   ├── src/main/java/com/flowforge/
│   │   ├── auth/                   JWT auth, password reset
│   │   ├── user/                   User, Role, Department, first-admin bootstrap
│   │   ├── workflow/               Definitions, versions, graph validation
│   │   ├── engine/                 Execution engine and node executors
│   │   ├── task/                   Tasks, approvals, delegation, comments, attachments
│   │   ├── notification/           In-app inbox and email dispatch
│   │   ├── audit/                  AOP-based audit logging
│   │   ├── report/                 Dashboard and performance analytics
│   │   └── common/                 Exceptions, response envelope, validation constraints
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/           Flyway migrations V1-V4
│   └── pom.xml
├── frontend/                       Next.js 14 (TypeScript / pnpm)
│   ├── src/app/                    App Router pages
│   ├── src/components/             UI components, including the workflow builder
│   ├── src/lib/                    API client and typed endpoint wrappers
│   └── package.json
├── .github/workflows/              CI and CD pipelines
├── docker-compose.yml
└── .env.example
```

## Deployment

### Images and tags

CI builds both images on every push, tagged with the commit SHA, plus `latest` on `main`. The SHA tag
is the one that matters: it identifies exactly what is running, so a container can always be traced
back to its commit.

```bash
docker build -t flowforge-backend:$(git rev-parse HEAD) ./backend
docker build -t flowforge-frontend:$(git rev-parse HEAD) ./frontend
```

### The frontend proxy target is a build-time value

`API_PROXY_TARGET` is baked into the frontend image. Next.js resolves the `/api/*` rewrite during
`next build` and freezes the destination into `routes-manifest.json`, so **setting it as a container
environment variable at runtime has no effect**. Deploying where the backend has a different address
means rebuilding:

```bash
docker build --build-arg API_PROXY_TARGET=https://api.example.com -t flowforge-frontend:x ./frontend
```

It defaults to `http://backend:8080`, the compose service name. Inside the frontend container
`localhost` is the frontend itself, which is why it cannot be used.

### Variables that must be set

Everything else has a working default; these do not, or their defaults are unsafe.

| Variable | Why |
|---|---|
| `JWT_SECRET` | Defaults to a placeholder published in this repository. Anyone holding it can mint valid tokens for any user, including an administrator's. |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Default to a local `flowforge`/`flowforge` Postgres. |
| `MAIL_HOST`, `MAIL_PORT` | Default to MailHog. Without a real SMTP host, notification emails fail silently; the in-app notification still lands. |
| `ATTACHMENT_STORAGE_PATH` | Defaults inside the container. See below. |
| `WEB_BASE_URL`, `PASSWORD_RESET_URL` | Default to `localhost`, which makes email links useless to recipients. |

### Attachment storage needs a volume

Attachment bytes live on disk and only their metadata is in Postgres, so a volume at the storage path
is not optional: without one a redeploy discards every attachment while its `attachments` row
survives. `docker-compose.yml` mounts a named `attachment_data` volume for this.

A host bind mount must be writable by uid 101, the non-root `spring` user the image runs as, or
uploads fail with "Attachment storage is not writable".

### Migrations

Flyway runs at startup and is the only thing that touches the schema — Hibernate is set to `validate`,
so a mismatch between entities and the migrated schema fails startup rather than silently altering a
table.

```bash
docker compose up -d postgres     # wait for healthy
docker compose up -d backend      # applies V1..V4 on an empty database
docker compose logs backend | grep -i flyway
```

`V2` seeds the three roles and the default department. The first administrator is created by the
application rather than a migration, so no password hash is committed — see `AdminBootstrap`.
Deleting the last user re-enables the bootstrap on next start, which is the recovery path for a lost
admin account.

### Health checks

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Liveness and readiness. Backs the compose healthcheck and `depends_on: service_healthy`. |
| `GET http://127.0.0.1:3000` | Frontend. Probe over IPv4, not `localhost` — busybox resolves IPv6 first and the Next server binds IPv4 only, which reports a healthy container as unhealthy. |

Actuator exposure is limited to `health`. The mail indicator is disabled deliberately: an unreachable
SMTP host would mark the backend unhealthy and block the whole stack through `depends_on`, and mail is
a downstream concern rather than a liveness signal.

## Troubleshooting

**`Bind for 0.0.0.0:5432 failed: port is already allocated`**

A local Postgres or another project holds the port. Only the host side needs to change:

```bash
POSTGRES_PORT=5433 docker compose up -d
```

`BACKEND_PORT` and `FRONTEND_PORT` work the same way. Nothing inside the compose network is affected,
so the frontend still reaches the backend.

**Cannot sign in on a fresh install**

Get the generated administrator password from the log:

```bash
docker compose logs backend | grep -A6 "first administrator"
```

If that line is absent, the `users` table is not empty — the bootstrap only runs on a genuinely empty
database. `docker compose down -v` discards the volume and starts over, deleting all data.

**Editor shows hundreds of backend errors but `mvn verify` passes**

The language server is not running Lombok's annotation processor, so every generated constructor,
getter and `log` field looks missing. Install a Lombok plugin — IntelliJ bundles one, VS Code needs the
Lombok extension — then reload. This never affects the real build.

**`Cannot find matching toolchain definitions for jdk [version='21']`**

You passed `-Pjdk21-toolchain` without a `~/.m2/toolchains.xml`. Add the entry shown in
`backend/pom.xml`, or drop the flag — the default build works on any JDK 21+.

**`Attachment storage is not writable`**

A host bind mount at the attachment path must be writable by uid 101. The named volume in
`docker-compose.yml` avoids this.

**Emails never arrive**

Nothing is sent to real inboxes by default; `MAIL_HOST` points at MailHog. Start it with
`docker compose --profile dev up -d mailhog` and read the captured mail at http://localhost:8025.

## Contributing

Work on a branch and open a pull request. CI runs on every push to `main` and every pull request:
backend tests, backend integration tests, frontend tests, and both Docker builds. See
`.github/workflows/ci.yml`.

Before opening a pull request:

```bash
cd backend  && mvn verify && mvn verify -Pintegration
cd frontend && corepack pnpm run lint && corepack pnpm exec tsc --noEmit && corepack pnpm run test:ci
```

## License

[MIT](LICENSE)
