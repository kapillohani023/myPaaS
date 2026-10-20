# myPaaS — Context & Design Vocabulary

Shared language for this codebase. Two vocabularies live here: the **design vocabulary** (how we talk about code structure) and the **domain vocabulary** (how we talk about the product). Use these terms exactly — in identifiers, PR descriptions, and review comments. Consistent language is the point.

This document is the authoritative, self-contained design vocabulary for the codebase: module boundaries, domain terms, seam decisions, and cross-cutting rules are defined here.

---

## 1. Design vocabulary

| Term | Meaning | Avoid |
|---|---|---|
| **Module** | Anything with an interface and an implementation. Scale-agnostic: function, class, package. | unit, component, service |
| **Interface** | *Everything a caller must know to use the module correctly*: signatures plus invariants, ordering constraints, error modes, required config, performance characteristics. Not the `interface` keyword. | API, "signature" (too narrow) |
| **Implementation** | The body of code inside a module. | — |
| **Depth** | Leverage at the interface: how much behaviour a caller (or test) can exercise per unit of interface they must learn. Deep = lots of behaviour behind a small interface. | depth-as-line-ratio |
| **Seam** | A place where behaviour can be altered without editing that place; the *location* where a module's interface lives. | boundary (overloaded with DDD) |
| **Adapter** | A concrete thing satisfying an interface at a seam. Describes *role*, not substance. | — |
| **Leverage** | What callers get from depth: more capability per unit of interface learned. | — |
| **Locality** | What maintainers get from depth: change, bugs, and verification concentrate in one place. Fix once, fixed everywhere. | — |

**Operating rules**

1. **The deletion test.** Before keeping a module, imagine deleting it. If its complexity vanishes, it was a pass-through — merge it into its caller. If complexity reappears across N callers, it earns its keep.
2. **Two adapters rule.** One adapter means a hypothetical seam; two means a real one. Do not introduce a port unless two adapters exist *now* (a test adapter counts).
3. **The interface is the test surface.** Callers and tests cross the same seam. Tests assert observable outcomes through the interface and must survive internal refactors. When a module is deepened, old unit tests on its former internals are deleted — replace, don't layer.
4. **Internal seams stay internal.** A deep module may have private seams used by its own tests. Never expose them through the external interface because tests happen to use them.

---

## 2. Domain vocabulary

| Term | Meaning |
|---|---|
| **Control plane** | The single myPaaS Java application. Owns configuration, orchestration, state. One deployment unit; a documented single point of failure. |
| **Managed server** | A machine the control plane can deploy to (local host or remote over SSH). Has verified capabilities and a connection credential reference. Credentials are never stored on the server record itself. |
| **Deployment target** | The (managed server × project runtime destination) pair a deployment lands on. |
| **Project** | A deployable application: Git source, scripts, environment, file references, routing selections, health checks, webhook settings, and an append-only sequence of configuration revisions. |
| **Project configuration revision** | An immutable snapshot of all deployment-relevant project settings. A deployment references the revision it used; later edits never change an in-flight or historical deployment. |
| **Deployment** | One execution of the deployment workflow for a project. Has a validated state machine (states: `QUEUED`, `VALIDATING`, `CLONING`, `BUILDING`, `PREPARING`, `DEPLOYING`, `RESTARTING`, `HEALTH_CHECKING`, `SUCCEEDED`, `FAILED`, `CANCELLED`), steps, logs, a trigger, an actor, the project configuration revision it used, timings, a failure summary, a release reference, and a rollback relationship. |
| **Release** | An immutable build output on a target, recording the project configuration revision that produced it and activated by symlink switch. A deployment produces a release; a rollback activates the release that was active immediately before the referenced deployment, including that release's revision. |
| **Rollback** | A deployment that activates the release that was active immediately before the referenced terminal deployment. It records a rollback relationship and never reverses database migrations or external side effects. |
| **Trigger** | Where a deployment came from: dashboard, API, or webhook. Derived from the security context — never accepted as a caller-supplied parameter. |
| **Idempotency key** | A client-supplied token scoped to actor and project. Repeating a deployment request with the same key returns the original deployment instead of creating another. Webhook delivery IDs are natural keys. |
| **Background job** | A durable unit of asynchronous work with a type, payload references, idempotency key, schedule, retry policy, and observable status. Payloads never carry secret values. |
| **Routing** | The desired hostname → upstream mapping for a project, realized as managed Nginx configuration plus TLS and applied by `converge`. |
| **Managed domain namespace** | The platform-configured domains and subdomains projects may claim. A hostname outside the namespace cannot be assigned. |
| **Hostname assignment** | An authorized, unique mapping of a hostname to one project. Reassignment requires a privileged action and audit. |
| **Certificate** | An uploaded TLS certificate chain and matching private key, with metadata, validity, and hostname coverage. Private key material is stored as a sensitive file and never returned by read APIs. |
| **Webhook configuration** | Per-project webhook settings: provider, enabled state, allowed events, branch filters, and a secret credential reference. The secret is never returned. |
| **Stored file** | An uploaded file held outside the web root under a generated storage key. Its original filename is display-only and never determines a path. |
| **File reference** | A project configuration entry naming a stored file by ID plus a permitted deployment destination. The reference is validated independently of the original filename. |
| **Environment entry** | A named runtime variable whose value is either a literal or a credential reference. Literal values are non-secret by definition; secrets use credential references. |
| **Environment file reference** | A file reference marked for runtime injection as an environment file. The stored file is sensitive, encrypted at rest, and transferred with `0600` permissions. |
| **Metric sample** | A timestamped measurement for a server or application (CPU, memory, disk, network, health, latency, deployment outcome). Retained at a bounded resolution. |
| **Health result** | A timestamped application health outcome: healthy or unhealthy, latency, and diagnostic detail. |
| **Alert rule** | A scoped threshold condition with recipients, cooldown, and optional recovery notification. |
| **Alert event** | A deduplicated firing or recovery of an alert rule for one resource. |
| **Platform event** | A typed notification trigger published by a module. It carries IDs and references, never secrets, and is resolved into recipients and templates by `notification`. |
| **Analytics event** | A parsed request, page view, or click record scoped to one project. Client IPs are anonymized; query strings are stripped or allowlisted; retention is bounded. |
| **Credential reference** | A pointer to a protected secret (Git token, SSH key, SMTP password). Secrets are encrypted at rest and never returned by read APIs. |
| **Key version** | An identifier for the key-encryption key used to wrap a secret's data key. Ciphertext records carry it so rotation can re-wrap without bulk decrypt. |
| **Access scope** | The servers and projects an actor may see, derived from role, server memberships, and project memberships. Constrains list queries; it is not proof of access to one resource. |
| **Role** | One of the fixed v1 roles: `ADMIN`, `DEVELOPER`, `VIEWER`. Roles grant capability; server and project memberships grant scope. |
| **Step-up grant** | A short-lived, single-use authorization bound to one actor, action, and resource scope, issued only after OTP verification. Required for sensitive actions. |
| **Sensitive action** | An action requiring OTP. A mandatory baseline cannot be removed by configuration; configuration may add actions. |

---

## 3. Module map

Deep modules (the leverage lives here — design these first):

| Module | Interface shape | What the implementation hides |
|---|---|---|
| `deployment` | See §5 | The full deployment workflow: validation, state machine, locks, idempotency, webhook dedup, Git fetch, release preparation, build/prep scripts, file and runtime configuration, routing convergence, activation/restart, health checks, notifications, audit, durable job dispatch, restart recovery. |
| `routing` | `claim`, `unclaim`, `registerCertificate`, `selectCertificate`, `converge`, `certificateStatus` | Managed namespace/ownership checks, Nginx config generation from validated structured fields, certificate/key validation and matching, private-key storage via `storage`, certificate transfer to the target, validation-before-reload, atomic apply, last-known-good preservation. Callers never learn Nginx exists. |
| `notification` | `notify(PlatformEvent)` | Template selection, recipient resolution, throttling/dedup, SMTP outbox backed by `jobs`, delivery state, retry with backoff. Callers never compose emails. |
| `credentials` | `store`, `rotate`, `revoke`, `rotateEncryptionKey` (privileged), `resolveForExecution` (internal, used by execution adapters), plus `SecretRegistry` | Envelope encryption with versioned KEKs, key rotation, credential-reference resolution at execution time, redaction registry (see §4). Never returns secret values to callers. |
| `storage` | `upload`, `metadata`, `open`, `delete`, `transfer` | Generated storage keys, checksums and atomic writes, sensitivity classification and encryption (keys owned by `credentials`), retention, destination/path validation, remote transfer, per-file authorization. List/search is a separate read-side interface. Callers never see storage keys or filesystem paths. |
| `logging` | `append`, `query`, `stream` | Single deployment-log implementation: secret redaction (via the registry), bounded buffers, backpressure, retention, SSE framing. `DeploymentService.logs` is a facade over this module. |
| `jobs` (internal) | `schedule`, `cancel`, `status` | Durable DB-backed queue: leases/heartbeats, retries/backoff, dead-letter, per-type concurrency, coalescing, delayed/recurring schedules, bounded capacity, restart recovery. Handlers are idempotent. Payloads carry references only. |
| `monitoring` | `record`, `recordHealth`, `query`, `upsertAlertRule`, `deleteAlertRule` | Server and application metric collection, health sampling, aggregation, bounded retention, alert evaluation, dedup/cooldowns, recovery notifications. Reads deployment outcomes and certificate metadata through read-side interfaces; never sits in the deployment or request path. |
| `analytics` | `ingest`, `recordClientEvent`, `query` | Nginx access-log parsing, client event validation, bot/exclusion filtering, IP anonymization, aggregation, bounded retention, asynchronous ingestion. Shares the telemetry pipeline with monitoring; separate public interface. |
| `runtime` | `start`, `stop`, `restart`, `status`, `health` for an app instance | systemd unit generation, protected environment injection (`0600` env files owned by the app user; unit files never contain secrets), dedicated OS users, journal capture, resource limits, health-check execution, exit-status diagnostics. |

Narrow-interface modules — keep their public surface small, but do not under-design their behaviour:

- **`project`**: CRUD + `validate(configuration)` + immutable configuration revisions (`save`, `revision`). Owns pre-deployment validation. Does not absorb deployment logic.
- **`server`**: repository + `verifyConnectivity()` / capability detection. Answers questions; runs no workflows.
- **`audit`**: `record(event)` + scoped query. Append-only. Records actor, action, resource, timestamp, outcome, and correlation ID. It must be impossible for callers to write secret contents into it. Security-sensitive mutations write their audit event in the same transaction; if the audit write fails, the mutation fails.
- **`identity`**: authentication, JWT access/refresh lifecycle (rotation, revocation, replay detection), OTP issuance and verification, password reset, email verification. Authorization decides when OTP is required; identity performs the OTP mechanics.
- **`authorization`**: `require(action, resource)` and `visibleScope(type)`, consumed at the service layer of every module. Owns the sensitive-action policy and step-up grant lifecycle (`beginStepUp`, `completeStepUp`). Most-crossed seam in the system; design once, early. Authorization tests must run independently of the UI.
- **`api` / web UI**: adapters only. Zero business rules. Parse and validate requests, authenticate, apply rate limits and idempotency, map errors, and delegate. Dashboard, REST controllers, and webhook handlers all cross the same service seams.

Deliberate module merges:

- **`alerts` folds into `monitoring`.** Alert rules, dedup, cooldowns, and recovery notifications live inside monitoring.
- **`monitoring` and `analytics` keep two interfaces but share one ingestion → aggregation → retention implementation.** Do not build two pipelines.

**`storage` boundary.** `storage` owns file bytes and metadata; `project` owns file references (file ID plus permitted destination); `deployment` requests transfer through `storage`. `storage` authorizes every access from the security context — a file ID is never proof of access — generates opaque storage keys, writes atomically with checksums, encrypts sensitive files via `credentials`, and validates destinations against the target's allowed root. Transfer is idempotent, verifies integrity, preserves the intended mode, and never logs file contents. `StorageProvider` is internal to `storage`. Storage is tested through its interface with the local provider and a fake `FileTransfer`; no remote host.

**`credentials` key rotation.** Secrets use envelope encryption: a per-secret data key encrypts the value, and a versioned key-encryption key (KEK) held outside the database and repository wraps it. Ciphertext records the KEK version. Rotation adds a KEK version and re-wraps data keys in bounded batches; old versions remain readable until re-wrap completes, then are retired. `credentials.rotate(...)` rotates a credential value; encryption-key rotation is a separate privileged operation. Sensitive files use the same mechanism through `storage`. Tests cover old-key decrypt, re-wrap, and retired-key rejection.

**`project` configuration boundary.** A project owns an append-only sequence of `ProjectConfigurationRevision`s. Each revision captures Git source and ref, target server, scripts, file references, environment entries, routing selections, health checks, and retention settings. Saving valid configuration creates a revision; secrets are represented only by credential references. Deployments read the latest committed revision at submit and retain that revision ID; a ref override is recorded on the deployment, not written back to the project. Validation is static here and re-checked against server capabilities and credential availability at deployment submit. Tests cover revision immutability and deployment pinning.

**`jobs` boundary.** `jobs` is the only asynchronous execution mechanism. Callers persist intent and call `schedule(...)` in the same database transaction; workers claim jobs with leases outside the transaction, heartbeat, and run idempotent handlers. The module hides PostgreSQL persistence, retries/backoff, dead-letter, per-type concurrency, duplicate coalescing, delayed and recurring schedules, bounded capacity, and restart recovery. Payloads carry IDs, references, and non-secret parameters only — never secret values. Job types include `deployment.run`, `routing.converge`, `monitoring.collect`, `analytics.ingest`, `notification.send`, and `retention.purge`. Deployment queue overflow fails fast; routing converges coalesce, and monitoring and analytics coalesce or drop according to policy. No job handler runs inside a request thread or a database transaction. Completed jobs are retained for a bounded observability window.

```java
interface JobScheduler {
    JobId schedule(JobSpec spec);
    void cancel(JobId id);
    JobStatus status(JobId id);
}
```

`JobSpec` carries type, payload references, idempotency key, `runAt`, retry policy, and correlation ID. Delivery is at-least-once; every handler must be idempotent. Tests use an in-memory job store and fake clock and cover crash/restart, lease expiry, duplicate delivery, retry/dead-letter, per-type isolation, and overflow.

**`monitoring` boundary.** `monitoring` owns server and application metric samples, health results, alert rules, and alert events. Collectors are scheduled by `jobs` and run through `CommandExecutor`; samples are aggregated and retained on the shared telemetry pipeline. Alert evaluation runs on a `jobs` schedule, deduplicates by rule + resource, enforces cooldowns, and sends firing and recovery notifications through `notification.notify(PlatformEvent)`. Queries are scoped by `authorization.visibleScope`. Deployment outcomes and certificate metadata arrive through their read-side interfaces; monitoring never sits in the deployment or request path. Recording is best-effort: monitoring failure never changes a deployment result. Sampling defaults to 60 seconds; retention is bounded and configurable. Tests use a fake collector, fake clock, and notification adapter; no real server.

```java
interface MonitoringService {
    void record(MetricSample sample);              // best-effort; never affects deployment outcome
    void recordHealth(HealthCheckResult result);
    MonitoringView query(MonitoringQuery query);
    AlertRuleId upsertAlertRule(AlertRule rule);
    void deleteAlertRule(AlertRuleId id);
}
```

`record` and `recordHealth` are for collectors and other modules, not public client APIs. Alert evaluation is internal and clock-driven; callers do not trigger it. Monitoring and analytics share one ingestion → aggregation → retention implementation but keep separate public interfaces.

**`analytics` boundary.** `analytics` owns request, page-view, and click analytics. Nginx access logs are read in bounded batches through `CommandExecutor` using the access-log format owned by `routing`; client page views and clicks arrive only through the documented, rate-limited tracking endpoint, which validates the project's public tracking key and allowed hostname. Request counts from access logs are not relabeled as page views. Ingestion runs through `jobs` and never blocks deployments or request serving. The module filters bots and configured exclusions, anonymizes client IPs, strips or allowlists query strings, aggregates per project and time bucket, and enforces bounded retention. `query` is scoped by `authorization.visibleScope`. Click tracking exists only when the documented client script is installed. Monitoring and analytics share one internal telemetry pipeline but keep separate public interfaces. Tests use fake access-log batches, a fake clock, and the shared in-memory telemetry pipeline; no real Nginx.

```java
interface AnalyticsService {
    void ingest(AccessLogBatch batch);             // collector only
    void recordClientEvent(ClientEvent event);     // tracking endpoint only
    AnalyticsView query(AnalyticsQuery query);
}
```

**`routing` boundary.** `routing` owns hostname assignments, certificate metadata, and the desired Nginx configuration. Hostnames must belong to the configured managed domain namespace and are unique across projects; reassignment requires a privileged, audited action. Certificate upload validates chain, key match, validity, and hostname coverage; certificate material is stored as sensitive `StoredFile`s and the private key is never returned by read APIs. `claim`, `unclaim`, and `selectCertificate` change desired state and enqueue a coalesced `routing.converge` job; `converge(projectId)` is idempotent and applies it: it ensures certificate material is on the target, generates Nginx configuration from structured fields, validates the complete configuration, reloads only after validation, and preserves the last known-good configuration on failure. `certificateStatus` is the read-side interface monitoring uses for expiry alerts. Routing also owns the Nginx access-log format consumed by analytics. Every routing change is audited.

```java
interface RoutingService {
    HostnameAssignment claim(ProjectId project, Hostname hostname);
    void unclaim(Hostname hostname);
    CertificateId registerCertificate(CertificateUpload upload);
    void selectCertificate(ProjectId project, Hostname hostname, CertificateId certificate);
    void converge(ProjectId project);
    CertificateStatus certificateStatus(CertificateId certificate);
}
```

`converge` is safe to retry; a failed validation or reload leaves the previous configuration active. Errors are explicit: hostname outside namespace, hostname already assigned, certificate/key mismatch, expired certificate, hostname not covered, transfer failure, validation failure, and reload failure. Tests cover namespace and ownership conflicts, certificate mismatch and expiry, validation failure with last-known-good preservation, and idempotent converge; no real Nginx is required.

**`runtime` boundary.** `runtime` owns application-instance lifecycle and health-check execution. `start`, `stop`, `restart`, `status`, and `health` hide systemd unit generation, dedicated OS users, protected environment injection, resource limits, journal capture, timeouts, and exit diagnostics. `deployment` calls these as workflow steps and records outcomes; it does not implement health checks. Runtime resolves credential references only through execution adapters and materializes `0600` env files owned by the app user; unit files never contain secrets. V1 assumes the release layout: a versioned release directory plus a stable `current` symlink. Projects that cannot run from that layout are out of scope; alternative activation strategies are a future adapter. Tests use a fake executor and cover lifecycle, timeout, exit failure, and health outcomes; no real systemd.

**`server` boundary.** `server` owns registration, connectivity verification, SSH host-key pinning, and capability detection. It answers questions and runs no workflows. Credentials are references; private keys are never returned. A changed host key fails closed until an authorized administrator re-verifies. Capability results are cached with a verification timestamp and re-checked before deployment when stale.

**`identity` boundary.** `identity` owns password authentication, JWT access/refresh lifecycle (rotation, revocation, replay detection), email verification, password reset, and OTP issuance and verification. No open self-registration; administrators create or invite users. Bootstrap administrator creation is one-time (only when no administrator exists), audited, and forces a password change; known default credentials cannot remain valid in production. Password reset responds identically whether the account exists and revokes existing refresh sessions on success. Password reset and security notifications require a verified email. JWT claims carry stable identifiers only; database-backed authorization remains authoritative. Reset and verification tokens are single-use, expiring, stored hashed, and never returned in API responses. OTP values never appear in logs or audit. Authorization decides when OTP is required; identity verifies the OTP and returns a proof, never a grant. For token-bearing emails, identity generates the raw token, stores only its hash for verification, and hands notification a short-lived encrypted delivery reference; the raw value never enters logs, API responses, or audit.

**`api` boundary.** Controllers and dashboard handlers are adapters. They authenticate, validate request shape, apply rate limits and idempotency, map domain errors to the API error format, and delegate to application services. They contain no business rules. The dashboard may call services in-process or through the API, but it crosses the same service seams and authorization checks as external clients. Every supported operation has an `/api/v1` route and OpenAPI documentation. List endpoints are paginated and filtered; read-side interfaces accept an explicit page and filter. Webhook handlers are also adapters; they verify through `WebhookVerifier` and call `DeploymentService`.

**`notification` boundary.** `notification` owns templates, recipient resolution, throttling/dedup, the outbox, delivery state, retry/backoff, and failure reporting. Callers publish a `PlatformEvent`; they never compose email. The event catalog is closed and versioned: `DeploymentSucceeded`, `DeploymentFailed`, `RollbackSucceeded`, `RollbackFailed`, `AlertFired`, `AlertRecovered`, `CertificateExpiring`, `PasswordResetRequested`, `EmailVerificationRequested`, `OtpRequested`, `AccountSecurityChanged`. Events carry IDs and references, never raw secrets. The outbox row and its `notification.send` job are written in the caller's transaction; the outbox row owns encrypted material and the job carries only the outbox ID. One-time tokens are resolved only at send time and deleted after delivery. Outbox material is never logged. Outbox workers run through `jobs`. Delivery failure never changes a deployment or alert outcome.

**`webhook` boundary.** Webhook configuration is project-owned, mutable independently of deployment revisions, and carries a provider, enabled state, allowed events, branch filters, and a secret credential reference. Changes are audited. The webhook endpoint verifies through `WebhookVerifier` (generic HMAC and GitHub adapters), rejects stale or replayed requests, resolves the project and permitted branch/event, applies rate limits, audits accept/reject without secrets, and calls `DeploymentService.deploy` with the webhook trigger in the security context. Delivery IDs are idempotency keys. A rejected webhook never reaches script execution.

**`authorization` boundary.** `require(action, resource)` resolves role permission, server membership, project membership, and resource ownership. `visibleScope(type)` returns an opaque scope that repositories must apply to list queries; fetch-all-then-filter is forbidden. A client-supplied `projectId` or `fileId` is never proof of authorization.

```java
interface Authorization {
    void require(Action action, ResourceRef resource);      // throws Forbidden / StepUpRequired
    AccessScope visibleScope(ResourceType type);
    StepUpChallenge beginStepUp(Action action, ResourceRef resource);
    StepUpGrant completeStepUp(StepUpChallengeId challenge, OtpCode code);
}
```

A step-up grant is carried in the security context on the retry, not passed as a service-method parameter. `require` validates it against the current actor, action, and resource.

**Sensitive actions.** The mandatory baseline is credential changes, server access or permission changes, deletion of critical files/certificates/projects, and authentication or security setting changes. Configuration may add actions but cannot remove this baseline. A sensitive action requires a step-up grant: `beginStepUp` issues an OTP challenge; `completeStepUp` verifies the OTP and mints a short-lived, single-use grant bound to actor, action, and resource scope. `require` rejects a missing, expired, reused, or mismatched grant with `StepUpRequired`. Grant issuance, grant use, and every denial are audited.

**Authorization errors.** Callers see `Forbidden`, `StepUpRequired`, or a masked `NotFound`; resource existence is not disclosed to unauthorized actors. Tests cover the role/scope matrix, IDOR attempts, list-query scoping, and step-up replay, expiry, and cross-resource use independently of the UI.

---

## 4. Seam map

Real seams (adapters that exist — build exactly these):

| Seam | Production adapters | Test adapter |
|---|---|---|
| `CommandExecutor` | `LocalCommandExecutor`, `SshCommandExecutor` | **Fake executor** — the adapter the deployment engine is tested through. This is the seam's main justification. |
| `FileTransfer` | `LocalFileTransfer`, `SshFileTransfer` | **Fake transfer** — records writes, verifies checksums, and can inject failures. Internal to `storage`. |
| `StorageProvider` | Local filesystem (internal to `storage`) | Temp-dir / in-memory |
| Email delivery | SMTP (behind notification's outbox) | Fake capturing sends |
| `WebhookVerifier` | `GenericHmacVerifier`, `GitHubWebhookVerifier` | Fake verifier |
| `RateLimiter` | `PostgresRateLimiter` | `InMemoryRateLimiter` |
| `SecretRegistry` | Credentials-owned registry | Static test registry |

Hypothetical seams — **do not build ports for these**: S3/object storage, ACME issuance, Docker executor, DNS providers, KMS key source, GitLab-specific webhook adapter (generic HMAC and GitHub are v1), custom Nginx snippets (not v1; if added, a separate privileged, validated path). Note them as non-goals in module docs so nobody adds the port "helpfully."

`WebhookVerifier` is the only place provider-specific signature and payload logic lives. `RateLimiter` state is persistent across restart, keyed, and time-bounded.

**Webhook verification contract.**

```java
interface WebhookVerifier {
    VerifiedWebhook verify(WebhookRequest request, WebhookConfiguration config);
}
```

Provider-specific signature algorithms, header names, timestamp/replay checks, and event mapping stay inside the adapter. A verifier rejects invalid, stale, or replayed requests.

**Rate-limit contract.**

```java
interface RateLimiter {
    void acquire(RateLimitKey key, RateLimitPolicy policy); // throws RateLimited(retryAfter)
}
```

Keys are bucketed by actor, IP, project, and operation. State expires and is bounded. Security-sensitive denials are audited.

The **`SecretRegistry`** is an additional internal seam: credentials registers live secret values; deployment and logging consume the registry to redact output. It keeps "what is a secret" in one module. Internal seam — never exposed through any public API.

**Execution contract.** `CommandExecutor` is the security boundary for all process work:

```java
interface CommandExecutor {
    CommandResult run(ExecutionSpec spec, OutputSink output, CancellationToken cancellation);
}
```

`ExecutionSpec` carries: target server, argv or script, working directory, environment bindings (literal or credential reference), timeout, output limit, and correlation ID. `CommandResult` carries: exit status, exit code, output-truncated flag, and timings. `run` blocks until exit, timeout, or cancellation; callers invoke it on a bounded worker, never a request thread.

Invariants:

- Argv is a token list, never a shell-concatenated string. User scripts run through a fixed shell from a generated file, not by interpolating values into a shell command.
- Credential references are resolved by the adapter at execution time, injected through environment bindings or short-lived `0600` files, registered with `SecretRegistry`, and cleaned up. Secret values never appear in argv, script text, logs, exceptions, or audit records.
- Every command runs under the managed server's execution policy: pinned SSH host key, least-privileged OS account, allowed roots and privileged operations, timeouts, and output caps. Callers cannot widen the policy.
- Output is streamed through a blocking `OutputSink` with backpressure; the executor does not buffer unbounded output. Timeout and cancellation kill the process group.
- Error modes are explicit: policy rejection, connection or host-key failure, launch failure, timeout, cancellation, output-limit truncation, and non-zero exit.
- No network or process work occurs inside a database transaction.

**Transfer contract.** `FileTransfer` is a separate internal seam behind `storage.transfer(...)`:

```java
interface FileTransfer {
    TransferReceipt put(TransferSpec spec, CancellationToken cancellation);
}
```

`TransferSpec` carries: target server, source content handle, absolute destination within an allowed root, owner/mode, expected checksum, and overwrite policy. `put` writes to a temp file, verifies the checksum, applies permissions, and atomically renames; it is idempotent for the same content and destination. It rejects path traversal and symlink escapes, never accepts a client-supplied path, and never logs content. Adapters are `LocalFileTransfer`, `SshFileTransfer`, and a fake for tests. `storage` hides this seam; callers use `storage.transfer(...)`.

---

## 5. The deployment engine (decided)

Decided by design-it-twice (three candidates compared on depth, locality, seam placement). **External interface shaped for callers; internal implementation funnels through one guarded pipeline.**

```java
interface DeploymentService {
    DeploymentHandle deploy(ProjectId project);                         // latest committed configuration revision
    DeploymentHandle deploy(ProjectId project, DeployOptions opts);     // ref override, force, optional idempotency key
    DeploymentHandle rollback(DeploymentId of);                         // activate the release active immediately before `of`
    void cancel(DeploymentId id);
    DeploymentStatus status(DeploymentId id);
    LogStream logs(DeploymentId id, LogCursor from);                    // authorized facade over `logging`
}
```

**Invariants (part of the interface — callers rely on these):**

- `deploy`/`rollback`/`cancel` persist state and return promptly. Execution is driven by durable `jobs` and survives control-plane restart. No network or process work occurs inside a database transaction.
- Trigger and actor come from the security context, never from parameters. A caller cannot claim to be a webhook.
- A deployment captures the current committed `ProjectConfigurationRevision` at submit and retains it. Later project edits do not alter an in-flight or historical deployment. A ref override is recorded on the deployment, not written back to the project.
- Idempotency is scoped to actor and project. A supplied client key is persisted for its retention window; repeating the request returns the original handle, including after the original reaches a terminal state. Webhook delivery IDs are natural keys. Without a key, each call is a new deployment — `deploy(project)` twice deliberately queues two deployments behind the lock. Duplicate keyed requests and duplicate webhook deliveries never create a second deployment.
- Authorization (action + resource scope, including any required step-up grant) is checked inside every method, including before a log stream opens.
- Locks, idempotency records, and the state machine never appear in the interface.
- `rollback(of)` requires `of` to be terminal and activates the release that was active immediately before `of` was submitted, using that release's recorded configuration revision. It persists a new deployment record, re-converges routing from that revision, runs health checks, and records a rollback relationship. It is rejected when no predecessor release exists or that release is already active. It revalidates the revision; missing credentials, deleted file references, or incompatible server capabilities fail the rollback rather than silently using current configuration. It does not reverse database migrations or external side effects. v1 has no arbitrary release picker; to reach an older release, roll back the intervening deployments.
- `cancel` is idempotent. A queued deployment cancels immediately; a running deployment signals the active executor and reaches `CANCELLED` only after the process is terminated and cleanup is recorded. Cancellation never leaves routing pointed at an incomplete release.
- `DeployOptions.force` only bypasses the "no changes since the last successful release" short-circuit. It never bypasses validation, authorization, locking, idempotency, or health checks.

**Internal shape (not part of the interface):** all methods reduce to a private `submit(command)` pipeline — one chokepoint enforcing idempotency, authorization, locking, and audit. `submit` persists intent and enqueues a durable `deployment.run` job in the same transaction; the worker drives the state machine outside the transaction. This is an internal seam; do not expose it. History/list queries live in a separate read-side interface.

**Log ownership.** `DeploymentService.logs(...)` is a facade: it authorizes the caller against the deployment, then delegates to `logging.query/stream`. `logging` owns buffers, retention, redaction, SSE framing, and backpressure. There is no second deployment-log implementation.

**Health ownership.** Health-check execution belongs to `runtime`; `deployment` calls it, records the result, and decides the state transition.

**Deliberately rejected** (from the flexibility-first candidate): `skipBuild`, `configOverrides`, listener hooks, and `plan()` on this interface. Pre-deployment validation belongs to `project.validate(...)`; deployment revalidates at submit and fails fast.

**Test surface:** the whole engine — state transitions, configuration-revision pinning, durable job dispatch, idempotent replay, failure paths, rollback (including rollback-of-rollback), interrupted-job recovery, webhook dedup, log-stream authorization, secret redaction in logs — is tested through `DeploymentService` with the fake `CommandExecutor` and in-memory storage. No SSH, no Nginx, no systemd in these tests.

---

## 6. Cross-cutting rules

1. **API-first.** Every operation is reachable through `/api/v1`; the dashboard and external clients cross the same application-service seams with the same authorization. The dashboard may call services in-process (Thymeleaf/HTMX) but must not bypass service authorization. Controllers are adapters: input validation, error mapping, rate limits, idempotency, and OpenAPI live there; business rules do not.
2. **Secrets.** Never returned by read APIs, never in logs, argv, script text, or service unit files, resolved only by execution adapters, redacted via `SecretRegistry`, encrypted at rest with versioned envelope keys, keys outside the database and repo.
3. **File safety.** Stored files are addressed by opaque IDs, never paths. Uploads are size-bounded, checksummed, written atomically, and stored outside the web root. Sensitive files are encrypted at rest via `credentials`; contents are never logged or returned in errors.
4. **Transactions and jobs.** Persist intent and enqueue the corresponding `jobs` entry in the same database transaction; execute network/process work outside transactions; reconcile interrupted work on restart.
5. **Rate limits.** Apply at the API adapter and at service entry for expensive operations: login, OTP, password reset, webhook deliveries, deployment creation, and file uploads. Use a persistent limiter keyed by actor/IP/project with bounded state and explicit retry-after. Security-sensitive denials are audited.
6. **Bounded everything.** Queues, thread pools, log buffers, process output, streaming connections. Defaults: 3 concurrent deployments, 50 MB uploads, 30-day deployment logs, 90-day analytics, 60s metrics sampling, bounded metric retention. Per-type job queues have bounded capacity and explicit overflow policy: deployment creation fails fast; monitoring and analytics coalesce or drop.
7. **Backup and recovery.** Back up PostgreSQL and the encryption-key source separately; document and test restore. Losing the key source makes encrypted secrets unrecoverable.
8. **Transport.** External access uses HTTPS; session cookies are secure and HTTP-only where applicable; webhook endpoints are public but signature-verified and rate-limited.
9. **Build order.** Foundation (including durable jobs) → servers, projects, and storage → deployment engine → routing and TLS → integrations and operations → hardening. The deployment engine interface (§5) is the gate for the deployment-engine phase.
