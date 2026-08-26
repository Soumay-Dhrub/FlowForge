# FlowForge

FlowForge is a configurable workflow orchestration platform that lets teams design, publish, and run multi-step approval workflows through a drag-and-drop canvas interface. It handles task assignment, escalation, notifications, and audit logging out of the box.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Frontend | Next.js 14, TypeScript, Tailwind CSS, React Flow (`@xyflow/react`), React Query |
| Backend | Spring Boot 3.5 (Java 25), Spring Security (JWT), Spring Data JPA, MapStruct, Lombok |
| Database | PostgreSQL 15, Flyway migrations |
| Build | Maven 3.9+ (backend), pnpm (frontend) |
| Containerization | Docker, docker-compose |
| CI | GitHub Actions |

---

## Prerequisites

- **Java 25** (e.g. via SDKMAN: `sdk install java 25-tem`)
- **Maven 3.9+** (`mvn --version`)
- **Docker Desktop** (includes Docker Compose v2)
- **Node.js 20+** and **pnpm** (`npm install -g pnpm`)

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

3. **Start all services**
   ```bash
   docker-compose up --build
   ```

   This starts:
   - `postgres` — PostgreSQL 15 on port `5432`
   - `backend` — Spring Boot API on port `8080`
   - `frontend` — Next.js app on port `3000`
   - `pgadmin` — pgAdmin UI on port `5050` (local dev only)

4. **Access the app**
   - Frontend: http://localhost:3000
   - API: http://localhost:8080/api
   - pgAdmin: http://localhost:5050

---

## Running Without Docker

### Backend

```bash
# Start a local Postgres instance first, then:
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="--DB_URL=jdbc:postgresql://localhost:5432/flowforge --DB_USERNAME=flowforge --DB_PASSWORD=flowforge"
```

### Frontend

```bash
cd frontend
pnpm install
pnpm dev
```

---

## Running Tests

### Backend unit + property-based tests

```bash
cd backend
mvn verify
```

### Backend integration tests (requires Docker)

```bash
cd backend
mvn verify -P integration
```

### Frontend tests

```bash
cd frontend
pnpm test --ci
```

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
| `ATTACHMENT_MAX_SIZE` | `10485760` | Max upload size in bytes (10 MB) |

---

## Project Structure

```
FlowForge/
├── backend/                        Spring Boot API (Java 25 / Maven)
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
│   │   └── common/                 Shared exceptions, response wrapper
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
docker compose up -d backend      # applies V1, V2, V3 on an empty database
docker compose logs backend | grep -i flyway
```

Nothing seeds an initial administrator. `V2` seeds the three roles and a default department, so the
first user has to be inserted directly with a bcrypt hash, after which they can create everyone else
through `POST /api/users`.

```bash
htpasswd -bnBC 12 "" 'YourPassword' | tr -d ':\n' | sed 's/^\$2y\$/$2a$/'
```

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
