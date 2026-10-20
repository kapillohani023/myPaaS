# myPaaS

A single-node deploy control plane for applications running on managed Linux servers.
It owns projects, immutable configuration revisions, the deployment workflow, routing/TLS,
durable jobs, credentials, storage, monitoring, analytics and the operator dashboard.

The web dashboard follows [`src/main/resources/design.png`](src/main/resources/design.png).

## Run locally

Requirements: JDK 21 and Maven (the wrapper is included).

```bash
./mvnw spring-boot:run
```

A safe environment template lives at [`mypaas.env.example`](mypaas.env.example):

```bash
./mvnw -DskipTests package
set -a; . mypaas.env.example; set +a
java -jar target/mypaas-0.0.1-SNAPSHOT.jar
```

Open <http://localhost:8080>. Demo data is seeded on first start:

| Field | Value |
|---|---|
| Email | `john@example.com` |
| Password | `mypaas-demo!` |

The demo project **myapp** (Production Server, `myapp.example.com`) includes seeded releases,
deployment logs, files, certificate metadata, metrics and analytics. Demo seeding is controlled by
`mypaas.demo-seed` and can be disabled:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--mypaas.demo-seed=false
```

Demo automated certificate generation uses the local `openssl` binary; if it is unavailable the
domains tab simply shows no certificate.

## What is implemented

The package structure mirrors the domain vocabulary in [`CONTEXT.md`](CONTEXT.md).

- **Deployment engine** — `DeploymentService` exposes `deploy`, `rollback`, `cancel`, `status` and
  `logs`. Submission validates configuration and server capabilities, pins the latest committed
  project revision, writes the deployment and a durable `deployment.run` job in one transaction, and
  returns. A lease-based worker drives a validated state machine:
  `QUEUED → VALIDATING → CLONING → BUILDING → PREPARING → DEPLOYING → RESTARTING →
  HEALTH_CHECKING → SUCCEEDED`. Failures preserve the last known-good release and are recorded.
  Rollback activates the release that was active before the referenced terminal deployment, using
  that release's recorded revision, and supports rollback-of-rollback.
- **Immutable revisions** — every configuration save appends a `ProjectConfigurationRevision`.
  Deployments and releases record the revision they used; later edits never alter them.
- **Durable jobs** — JDBC-backed queue with leases, heartbeats, retries/backoff, dead-letter,
  per-type concurrency, coalescing, recurring schedules, bounded capacity and restart recovery.
  Payloads carry references only.
- **Authorization** — role + server/project membership + ownership, SQL-applied list scopes, a
  mandatory sensitive-action baseline, OTP step-up challenges and single-use, action/resource-bound
  grants. Denials and grants are audited.
- **Credentials and storage** — AES-256-GCM envelope encryption with versioned KEKs loaded from
  `data/keys` (outside the database and repository), data-key re-wrap rotation, sensitive stored
  files encrypted with the same mechanism, opaque storage keys, checksums and atomic writes.
- **Execution seam** — `CommandExecutor` with local and SSH adapters, plus a fake adapter used by the
  deployment tests. Execution specs use argv token lists, generated scripts, credential references,
  timeouts, output caps and cancellation. `FileTransfer` is a separate internal seam behind
  `storage.transfer`.
- **Routing/TLS** — managed namespace and ownership checks, certificate parsing/matching/expiry
  checks, generated Nginx configuration, validation before reload, last-known-good restoration and
  coalesced `routing.converge` jobs.
- **Runtime** — systemd unit generation, protected `0600` env files owned by the app user, dedicated
  users, journal capture, lifecycle and HTTP health checks. Unit files never contain secrets.
- **Notifications** — closed platform-event catalog, templates, recipient resolution, dedup,
  outbox rows and `notification.send` jobs written in the caller's transaction. Capture mode provides
  an access-controlled dev mailbox; `smtp` mode sends through JavaMail.
- **Monitoring/analytics** — one shared bounded telemetry pipeline with two public interfaces.
  Alert evaluation is clock/job driven; analytics ingest strips queries, filters bots and never stores
  raw client IPs. Page views/clicks come only from the documented tracking script.
- **API + dashboard** — `/api/v1` controllers and the Thymeleaf dashboard cross the same service seams
  and authorization. The dashboard has live deployment logs, deploy/cancel/rollback actions, file
  management, domains, environment, monitoring, history, settings and OTP step-up.
- **Deployment logs** — persisted with per-deployment sequence cursors, bounded buffers, secret
  redaction, 30-day retention and SSE streaming.
- **Audit** — append-only, redacted, actor/action/resource/outcome/correlation-ID records; scoped query
  for administrators.

## Tests

```bash
./mvnw test
```

The suite covers the deployment engine through `DeploymentService` with the fake executor and fake
file transfer (state transitions, revision pinning, idempotency, cancellation, rollback and
rollback-of-rollback), the role/scope matrix and IDOR attempts, step-up reuse/resource binding/expiry,
durable job leases/retries/dedup/recurring schedules, envelope-encryption rotation, sensitive storage
and log redaction. No SSH, Nginx or systemd is required.

## Production notes

Local development uses H2 in PostgreSQL compatibility mode. For production point
`spring.datasource.*` at PostgreSQL and validate the schema in a migration tool; keep the H2 profile
for development only.

- Disable demo seeding (`mypaas.demo-seed=false`) and remove/rotate the demo account.
  For a fresh production database, set `MYPAAS_BOOTSTRAP_ADMIN_EMAIL` and
  `MYPAAS_BOOTSTRAP_ADMIN_PASSWORD` for the first boot; the account is forced to change its
  password. Full configuration and secret-management instructions are in
  [`docs/secrets.md`](docs/secrets.md).
- Store `mypaas.keys-dir` outside the repository and back it up separately from the database. Losing
  it makes encrypted secrets unrecoverable.
- Put the control plane behind HTTPS; session cookies are secure/HTTP-only where applicable.
- Configure SMTP (`mypaas.notifications-mode=smtp`, `spring.mail.*`) and a verified sender.
- Provision target servers with a deploy account, the `mypaas` system root (`/etc/mypaas`), systemd,
  Nginx included from `/etc/mypaas/nginx/*.conf`, and the application runtime users.
- Set `mypaas.managed-namespace`, `mypaas.public-base-url`, storage roots and job concurrency for the
  deployment.

## API

The OpenAPI document is at [`src/main/resources/static/openapi.yaml`](src/main/resources/static/openapi.yaml)
and is served at `/openapi.yaml`. Authentication uses 15-minute JWT access tokens and rotating
refresh tokens; sensitive actions require an `X-Step-Up-Grant` obtained through OTP.

## Deliberate non-goals (v1)

No S3/object-storage port, ACME issuance, Docker executor, DNS provider integrations, KMS key source,
GitLab-specific webhook adapter, arbitrary Nginx snippets, or arbitrary release picker. Those are
documented non-goals so future work does not add ports “helpfully”. Roll back intervening deployments
to reach an older release.
