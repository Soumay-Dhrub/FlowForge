# FlowForge

FlowForge is a configurable workflow orchestration platform that lets teams design, publish, and run multi-step approval workflows through a drag-and-drop canvas interface. It handles task assignment, escalation, notifications, and audit logging out of the box.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Frontend | Next.js 14, TypeScript, Tailwind CSS, React Flow (`@xyflow/react`), React Query |
| Backend | Spring Boot 3.2 (Java 21), Spring Security (JWT), Spring Data JPA, MapStruct, Lombok |
| Database | PostgreSQL 15, Flyway migrations |
| Build | Maven 3.9+ (backend), pnpm (frontend) |
| Containerization | Docker, docker-compose |
| CI | GitHub Actions |

---

## Prerequisites

The fastest path needs only Docker: `docker compose up --build` builds both applications inside
containers, so no local JDK, Maven or Node install is required. The rest matter only if you want to
run a part of the stack directly.

- **Docker Desktop** (includes Docker Compose v2) — sufficient on its own
- **Java 21 or newer** (e.g. `sdk install java 21-tem`) — for building the backend locally
- **Maven 3.9+** (`mvn --version`)
- **Node.js 20 or newer** — for running the frontend dev server
- **pnpm** — no separate install needed; `corepack pnpm ...` uses the exact version pinned in
  `frontend/package.json`. `npm install -g pnpm` also works.

### A note on the JDK version

The backend compiles to Java 21 bytecode (`<release>21</release>`) but **builds and tests on any JDK
from 21 upward**, so you do not need to match a specific version or configure anything.

If you want the build to run on a real JDK 21 for exact parity with CI and the runtime image, opt in:

```bash
mvn -Pjdk21-toolchain verify
```

That profile requires a `~/.m2/toolchains.xml` entry pointing at a JDK 21 (the profile comment in
`backend/pom.xml` shows the snippet). It is opt-in precisely so that a fresh clone never needs it.

---

## Running Locally with Docker Compose

1. **Clone the repo**
   ```bash
   git clone <repo-url>
   cd FlowForge
   ```

2. **Copy environment variables**
   ```bash
   cp .env.example .env
   # Edit .env with your secrets (see Environment Variables below)
   ```

   Copying is optional — every value has a working default, so `docker compose up` runs without a
   `.env` at all. Copy it when you need to change a port or set a real `JWT_SECRET`.

3. **Start all services**
   ```bash
   docker compose up --build
   ```

   This starts:
   - `postgres` — PostgreSQL 15 on port `5432`
   - `backend` — Spring Boot API on port `8080`
   - `frontend` — Next.js app on port `3000`

   `pgadmin` (`5050`) and `mailhog` (`8025`) are gated behind the `dev` profile. Add them with
   `docker compose --profile dev up`, or set `COMPOSE_PROFILES=dev` in `.env`.

4. **Get the first sign-in credentials**

   A clean database has the three roles and a `General` department but **no users**, and there is no
   public registration endpoint — creating a user requires an existing `ADMIN`. So on first start the
   backend creates one administrator and prints a generated password once:

   ```bash
   docker compose logs backend | grep -A6 "first administrator"
   ```

   ```
   FlowForge had no users, so a first administrator was created.
     email:    admin@flowforge.local
     password: 3yc5wI24UdhFSRPnAYVAJEvyY1kIR-iH
   ```

   To choose the password yourself instead, set `BOOTSTRAP_ADMIN_PASSWORD` in `.env` before the first
   start. The password is generated rather than defaulted on purpose: a fixed default committed here
   would be a known credential on every deployment. This only ever runs while the `users` table is
   empty, so it can never alter an existing installation.

5. **Access the app**
   - Frontend: http://localhost:3000
   - API: http://localhost:8080/api
   - pgAdmin: http://localhost:5050 (`--profile dev`)
   - MailHog (captures all outgoing mail): http://localhost:8025 (`--profile dev`)

---

## Running Without Docker

### Backend

Start a Postgres instance first — the compose file can provide just that one:

```bash
docker compose up -d postgres
```

Then run the backend against it. These are environment variables, not Spring arguments, so pass them
as such:

```bash
cd backend
DB_URL=jdbc:postgresql://localhost:5432/flowforge \
DB_USERNAME=flowforge \
DB_PASSWORD=flowforge \
mvn spring-boot:run
```

On Windows PowerShell:

```powershell
cd backend
$env:DB_URL="jdbc:postgresql://localhost:5432/flowforge"
$env:DB_USERNAME="flowforge"; $env:DB_PASSWORD="flowforge"
mvn spring-boot:run
```

### Frontend

```bash
cd frontend
corepack pnpm install     # or: pnpm install
corepack pnpm dev         # or: pnpm dev
```

The dev server proxies `/api/*` to `http://localhost:8080`, so the browser stays on one origin and no
CORS configuration is needed. Override the target with `API_PROXY_TARGET` in `.env.local`.

---

## Running Tests

### Backend unit + property-based tests

```bash
cd backend
mvn verify
```

Runs 383 unit and jqwik property tests. Surefire excludes the `integration` group, so this needs no
Docker.

### Backend integration tests (requires Docker)

```bash
cd backend
mvn verify -Pintegration
```

Runs 30 tests against a real PostgreSQL 15 container via Testcontainers, sharing one container across
all classes.

### Frontend tests

```bash
cd frontend
corepack pnpm run test:ci     # 19 suites, 120 tests
corepack pnpm run lint
corepack pnpm exec tsc --noEmit    # type check (no dedicated script)
```

---

## Troubleshooting

**`Bind for 0.0.0.0:5432 failed: port is already allocated`**
A local Postgres (or another project) holds the port. Only the host side needs to change:

```bash
POSTGRES_PORT=5433 docker compose up -d
```

`BACKEND_PORT` and `FRONTEND_PORT` work the same way. Nothing inside the compose network is affected,
so the frontend still reaches the backend.

**Cannot sign in on a fresh install**
The database starts with no users. Get the generated administrator password from the log:

```bash
docker compose logs backend | grep -A6 "first administrator"
```

If that line is absent, the `users` table is not empty — the bootstrap only runs on a genuinely empty
database. `docker compose down -v` discards the volume and starts over (this deletes all data).

**Editor shows hundreds of errors in the backend but `mvn verify` passes**
The language server is not running Lombok's annotation processor. 77 files rely on Lombok, so without
it every generated constructor, getter and `log` field looks missing. Install a Lombok plugin
(IntelliJ has one built in; VS Code needs the Lombok extension), then reload. This never affects the
real build.

**`Cannot find matching toolchain definitions for jdk [version='21']`**
You passed `-Pjdk21-toolchain` without a `~/.m2/toolchains.xml`. Either add the entry shown in
`backend/pom.xml`, or just drop the flag — the default build works on any JDK 21+.

**`Attachment storage is not writable`**
A host bind mount at the attachment path must be writable by uid 101, the non-root `spring` user the
image runs as. The named volume in `docker-compose.yml` avoids this.

**Emails never arrive**
Nothing is sent to real inboxes by default; `MAIL_HOST` points at MailHog. Start it with
`docker compose --profile dev up -d mailhog` and read the captured mail at http://localhost:8025.

---

## Environment Variables

Copy `.env.example` to `.env` and fill in values before running.

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/flowforge` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `flowforge` | Database username |
| `DB_PASSWORD` | `flowforge` | Database password |
| `JWT_SECRET` | *(required in prod)* | HS256 secret key, min 256 bits |
| `JWT_ACCESS_EXPIRY_MS` | `900000` | Access token TTL (15 min) |
| `JWT_REFRESH_EXPIRY_MS` | `2592000000` | Refresh token TTL (30 days) |
| `MAIL_HOST` | `localhost` | SMTP server hostname |
| `MAIL_PORT` | `1025` | SMTP port |
| `MAIL_USERNAME` | *(empty)* | SMTP auth username |
| `MAIL_PASSWORD` | *(empty)* | SMTP auth password |
| `MAIL_FROM` | `no-reply@flowforge.local` | Envelope sender on outgoing mail |
| `ATTACHMENT_MAX_SIZE` | `10485760` | Per-file limit in bytes (10 MiB), enforced by the application |
| `ATTACHMENT_MAX_UPLOAD_SIZE` | `11MB` | Servlet limit. Deliberately above `ATTACHMENT_MAX_SIZE` so oversized files get a descriptive 413 instead of a truncated stream |
| `ATTACHMENT_MAX_REQUEST_SIZE` | `12MB` | Servlet limit for the whole multipart request |
| `ATTACHMENT_STORAGE_PATH` | `./var/attachments` | Root for attachment bytes; needs a volume in production |
| `BOOTSTRAP_ADMIN_ENABLED` | `true` | Create a first administrator when no users exist |
| `BOOTSTRAP_ADMIN_EMAIL` | `admin@flowforge.local` | Address of that account |
| `BOOTSTRAP_ADMIN_PASSWORD` | *(empty)* | Empty generates one and logs it once |
| `WEB_BASE_URL` | `http://localhost:3000` | Base URL used in notification email links |
| `PASSWORD_RESET_URL` | `http://localhost:3000/reset-password` | Page that collects the new password |
| `PASSWORD_RESET_EXPIRY_MS` | `86400000` | Reset token TTL, clamped to 24h |

Frontend variables live in `frontend/.env.local.example`. Next.js inlines `NEXT_PUBLIC_*` values at
build time, so they cannot be supplied to a running container.

Generate a real `JWT_SECRET` with:

```bash
openssl rand -base64 48
```

The backend refuses to start on a secret shorter than 256 bits, and logs a `SECURITY` warning for as
long as the committed placeholder is still in use.

---

## Project Structure

```
FlowForge/
├── backend/                        Spring Boot API (Java 21 / Maven)
│   ├── src/main/java/com/flowforge/
│   │   ├── FlowForgeApplication.java
│   │   ├── auth/                   JWT auth, password reset
│   │   ├── user/                   User, Role, Department
│   │   ├── workflow/               Workflow builder, versions
│   │   ├── engine/                 Workflow execution engine
│   │   ├── task/                   Tasks, approvals, comments
│   │   ├── notification/           In-app + email notifications
│   │   ├── audit/                  AOP-based audit logging
│   │   ├── report/                 Dashboard + analytics
│   │   └── common/                 Shared exceptions, response wrapper, validation constraints
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── application-test.yml
│   │   └── db/migration/           Flyway SQL migration scripts
│   └── pom.xml
├── frontend/                       Next.js 14 (TypeScript / pnpm)
│   ├── src/app/                    App Router pages
│   ├── src/components/             Reusable UI components
│   ├── src/lib/                    API client, auth context
│   └── package.json
├── docker-compose.yml
├── .env.example
├── .gitignore
└── README.md
```

---

## Branch Protection

- All merges to `main` require a passing CI build (both `backend-test` and `frontend-test` jobs).
- Direct pushes to `main` are disabled; open a pull request instead.
- See `.github/workflows/ci.yml` for the full pipeline definition.

## Deployment

### Images and tags

CI builds both images on every push and tags them with the commit SHA. On `main` it also tags
`latest`. The SHA tag is the one that matters: it is the only tag that identifies exactly what is
running, so given a container you can always get back to the commit it came from. `latest` exists so a
deployment can follow the branch, never as an image's only tag.

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

### Environment variables that must be set

Everything else has a working default; these do not, or their defaults are unsafe.

| Variable | Why |
|---|---|
| `JWT_SECRET` | Defaults to a public placeholder. Anyone holding it can mint valid tokens for any user. Must be at least 256 bits of random data. |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Default to a local `flowforge`/`flowforge` Postgres. |
| `MAIL_HOST`, `MAIL_PORT` | Default to MailHog on `localhost:1025`. Without a real SMTP host, notification emails are written and then silently fail to send; the in-app notification still lands. |
| `ATTACHMENT_STORAGE_PATH` | Defaults to `./var/attachments`, inside the container. See below. |

### Attachment storage needs a volume

Attachment bytes live on disk, and their metadata lives in Postgres. Mounting a volume at the storage
path is not optional: without one the files sit in the container's writable layer and a redeploy
discards every attachment while its `attachments` row survives, leaving metadata pointing at nothing.
`docker-compose.yml` mounts a named `attachment_data` volume for this.

The image creates the directory owned by the non-root `spring` user before switching to it. A bind
mount from the host must be writable by uid 101, or uploads fail with "Attachment storage is not
writable".

### First run and migrations

Flyway runs automatically at startup and is the only thing that should ever touch the schema —
Hibernate is set to `validate`, so a mismatch between the entities and the migrated schema fails
startup rather than silently altering a table.

```bash
docker compose up -d postgres     # wait for healthy
docker compose up -d backend      # applies V1..V4 on an empty database
docker compose logs backend | grep -i flyway
```

`V2` seeds the three roles and a default department. The first administrator is created by the
application, not by a migration, so no password hash is committed to the repository — see
`AdminBootstrap`. It runs only while the `users` table is empty:

| Variable | Default | Behaviour |
|---|---|---|
| `BOOTSTRAP_ADMIN_ENABLED` | `true` | Set `false` where accounts are provisioned another way. |
| `BOOTSTRAP_ADMIN_EMAIL` | `admin@flowforge.local` | Address of the created account. |
| `BOOTSTRAP_ADMIN_PASSWORD` | *(empty)* | Empty generates a strong random password and logs it once. Set it to choose your own. |

That administrator can then create everyone else through `POST /api/users`. Deleting the last user
re-enables the bootstrap on next start, which is the intended recovery path for a lost admin account.

### Health checks

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Liveness and readiness. Backs the compose healthcheck and `depends_on: service_healthy`. |
| `GET http://localhost:3000` | Frontend. Probe over IPv4 (`127.0.0.1`), not `localhost` — busybox resolves IPv6 first and the Next server binds IPv4 only, which reports a healthy container as unhealthy. |

Actuator exposure is limited to `health`, and the mail health indicator is disabled deliberately: an
unreachable SMTP host would mark the backend unhealthy and, through `depends_on`, block the whole
stack — mail delivery is a downstream concern, not a liveness signal.

### Ports

`POSTGRES_PORT` often needs overriding to `5433`, since a locally installed Postgres usually holds
5432. Only the host side changes; nothing inside the network is affected.

```bash
POSTGRES_PORT=5433 docker compose up -d --build
```
