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

- **Java 21** (e.g. via SDKMAN: `sdk install java 21-tem`)
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
