# Secrets and configuration

myPaaS separates **platform boot configuration** from **tenant secrets**.

- Platform boot config (database password, SMTP password, KEK location, bootstrap admin)
  comes from the process environment or a secret manager. It is never committed and never
  stored in the repository.
- Tenant secrets (database URLs, API tokens, SSH private keys, webhook HMAC secrets) are
  stored through the `credentials` module: envelope-encrypted at rest, referenced by ID,
  and never returned by read APIs.

## 1. Platform environment file

A safe template is in the repository at [`mypaas.env.example`](../mypaas.env.example).
Create a file owned by the service user, readable only by that user:

```bash
install -m 600 /dev/null /etc/mypaas/mypaas.env
```

Example `/etc/mypaas/mypaas.env`:

```bash
# --- identity -------------------------------------------------------------
MYPAAS_DEMO_SEED=false
MYPAAS_PUBLIC_BASE_URL=https://paas.example.com
MYPAAS_MANAGED_NAMESPACE=apps.example.com

# One-time bootstrap administrator. Remove these after the first successful
# sign-in; the account is forced to change its password.
MYPAAS_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
MYPAAS_BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-long-random-password

# --- keys and storage -----------------------------------------------------
MYPAAS_KEYS_DIR=/var/lib/mypaas/keys
MYPAAS_STORAGE_ROOT=/var/lib/mypaas/storage

# --- database (PostgreSQL in production) ---------------------------------
SPRING_DATASOURCE_URL=jdbc:postgresql://db.internal:5432/mypaas
SPRING_DATASOURCE_USERNAME=mypaas
SPRING_DATASOURCE_PASSWORD=replace-me

# --- mail ----------------------------------------------------------------
MYPAAS_NOTIFICATIONS_MODE=smtp
MYPAAS_MAIL_FROM=myPaaS <no-reply@example.com>
SPRING_MAIL_HOST=smtp.example.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=smtp-user-or-provider-api-key-id
SPRING_MAIL_PASSWORD=replace-me
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
```

Run with the environment loaded (systemd `EnvironmentFile=`, Docker/Kubernetes secret, etc.):

```bash
set -a
. /etc/mypaas/mypaas.env
set +a
java -jar mypaas-0.0.1-SNAPSHOT.jar
```

Do **not** pass secrets as command-line arguments; they are visible in the process list.

### SMTP notes

- `SPRING_MAIL_USERNAME` is the SMTP identity (username, client ID or provider API-key ID).
- `SPRING_MAIL_PASSWORD` is the SMTP password/token.
- `MYPAAS_MAIL_FROM` is the envelope/display sender.
- For implicit TLS on port 465, add:
  `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE=true` and disable STARTTLS.
- Leave `MYPAAS_NOTIFICATIONS_MODE=capture` for local development. OTP, password-reset and
  verification emails are then captured in the administrator-only **dev mailbox**
  (`/dashboard/dev-mailbox`).

SMTP is deliberately bootstrapped from the environment rather than the credentials
module: OTP delivery must work before a user can authorize access to stored credentials.
Project and server credentials use the API below.

### Keys and backup

`MYPAAS_KEYS_DIR` contains the versioned KEK files (`kek-v*.key`) and the JWT signing key
(`jwt.key`). It is created automatically on first boot.

- Back up the database and the key directory **separately**.
- Losing the key directory makes encrypted credentials and sensitive files unrecoverable.
- Never commit it; the default `data/` directory is gitignored.
- The JWT signing key is generated once and persisted; rotating it invalidates access
  tokens (users simply sign in again).

### Bootstrap administrator

When no `ADMIN` user exists and `MYPAAS_BOOTSTRAP_ADMIN_EMAIL` +
`MYPAAS_BOOTSTRAP_ADMIN_PASSWORD` are set, one administrator is created on startup with
`must_change_password=true`. On the first sign-in the user is redirected to
`/change-password` and cannot use the dashboard until the password is changed. Remove the
bootstrap variables afterwards.

If you prefer demo mode for a first look, leave `MYPAAS_DEMO_SEED=true` and use
`john@example.com` / `mypaas-demo!`. That account is a known development credential and
must not exist in production.

## 2. Tenant secrets: credentials module

Credential creation is an admin-only, sensitive action (`CREDENTIAL_MANAGE`) and therefore
needs a one-time OTP step-up grant.

### 2.1 Sign in and begin step-up

```bash
BASE=http://localhost:8080

TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"..."}' | jq -r .accessToken)

curl -s -X POST "$BASE/api/v1/auth/step-up/begin" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"action":"CREDENTIAL_MANAGE","resourceType":"GLOBAL"}'
# -> {"id":"challenge-id", ...}
```

The 6-digit code is sent to the administrator's verified email. In capture mode it appears
in `/dashboard/dev-mailbox`.

```bash
curl -s -X POST "$BASE/api/v1/auth/step-up/complete" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"challengeId":"challenge-id","code":"123456"}'
# -> {"value":"single-use-grant", "expiresAt":...}
```

Grants are single-use and bound to the actor, action and resource. If the next request
fails and you need to retry, begin a new challenge.

### 2.2 Store a project secret

```bash
curl -s -X POST "$BASE/api/v1/credentials" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Step-Up-Grant: $GRANT" \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "DATABASE_URL",
        "projectId": 1,
        "secret": "postgres://app:password@db.internal:5432/app"
      }'
# -> {"id":7,"name":"DATABASE_URL","projectId":1,...}   (no secret value)
```

The `id` returned is the **credential reference**. Use it in project environment entries:

```bash
# Read current entries first; PUT replaces the list.
curl -s "$BASE/api/v1/projects/1/environment" -H "Authorization: Bearer $TOKEN"

# Environment writes require ENV_WRITE step-up. Begin a challenge with:
#   {"action":"ENV_WRITE","resourceType":"PROJECT","resourceId":1}
curl -s -X PUT "$BASE/api/v1/projects/1/environment" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Step-Up-Grant: $ENV_GRANT" \
  -H 'Content-Type: application/json' \
  -d '{"entries":[
        {"name":"NODE_ENV","literalValue":"production"},
        {"name":"DATABASE_URL","credentialId":7}
      ]}'
```

At deployment time the runtime resolves the reference and writes a `0600` environment file
owned by the application user. The value never appears in the systemd unit, deployment logs,
argv, audit records or API responses.

You can also add an entry from the dashboard **Environment** tab (it asks for OTP step-up).

### 2.3 Server SSH keys

Store the private key as a global credential (`projectId` omitted), then register the
server with the returned `id`:

```bash
SSH_CREDENTIAL=$(curl -s -X POST "$BASE/api/v1/credentials" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Step-Up-Grant: $GRANT" \
  -H 'Content-Type: application/json' \
  -d '{"name":"prod-server-ssh","secret":"-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----"}' \
  | jq -r .id)

curl -s -X POST "$BASE/api/v1/servers" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{
        \"name\":\"Production Server\",
        \"host\":\"server.example.com\",
        \"sshPort\":22,
        \"username\":\"mypaas\",
        \"credentialId\":$SSH_CREDENTIAL,
        \"local\":false,
        \"hostKeyPublic\":\"ssh-ed25519 AAAA...\",
        \"allowedRoot\":\"/opt/apps\"
      }"
```

Pin the real host key (for example `ssh-keyscan -t ed25519 server.example.com`). A changed
host key fails closed until an administrator re-verifies it.

### 2.4 Webhook secret

```bash
WEBHOOK_CREDENTIAL=$(curl -s -X POST "$BASE/api/v1/credentials" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Step-Up-Grant: $GRANT" \
  -H 'Content-Type: application/json' \
  -d '{"name":"github-webhook","secret":"a-long-random-hmac-secret"}' | jq -r .id)

curl -s -X PUT "$BASE/api/v1/projects/1/webhook" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{
        \"provider\":\"github\",
        \"enabled\":true,
        \"allowedEvents\":[\"push\"],
        \"branchFilter\":\"main\",
        \"secretCredentialId\":$WEBHOOK_CREDENTIAL
      }"
```

### 2.5 Certificates

Certificate private keys are not stored through the credential API. Upload the chain and
PKCS#8 private key together; both are validated/matched, stored as encrypted sensitive
files, and the private key is never returned:

```bash
curl -s -X POST "$BASE/api/v1/certificates" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Step-Up-Grant: $CERT_GRANT" \
  -H 'Content-Type: application/json' \
  -d '{
        "chainPem":"-----BEGIN CERTIFICATE-----\n...\n",
        "privateKeyPem":"-----BEGIN PRIVATE KEY-----\n...\n",
        "hostnames":["app.example.com"]
      }'
```

`CERTIFICATE_MANAGE` is an admin-only sensitive action, so begin a step-up challenge with
`"resourceType":"GLOBAL"`.

### 2.6 Rotation and revocation

Credential value rotation (step-up required):

```bash
curl -s -X POST "$BASE/api/v1/credentials/7/rotate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Step-Up-Grant: $GRANT" \
  -H 'Content-Type: application/json' \
  -d '{"secret":"new-value"}'
```

Revocation (step-up required; deployments that still reference it fail validation):

```bash
curl -s -X DELETE "$BASE/api/v1/credentials/7" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Step-Up-Grant: $GRANT"
```

Encryption-key rotation (step-up required):

```bash
curl -s -X POST "$BASE/api/v1/admin/keys/rotate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Step-Up-Grant: $GRANT"
# -> {"kekVersion":2}
```

This adds a new KEK version, re-wraps credential data keys and sensitive stored-file data
keys in bounded batches, then retires the old versions. Back up the key directory
immediately afterwards.

## 3. Finding IDs

| ID | Where |
|---|---|
| `projectId` | `GET /api/v1/projects` or the dashboard project picker |
| `serverId` | `GET /api/v1/servers` |
| `credentialId` | response from `POST /api/v1/credentials` |
| `fileId` | `GET /api/v1/projects/{id}/files` |
| `deploymentId` | response from deployment submit / project history |
| `certificateId` | `GET /api/v1/certificates` |

## 4. Do not do this

- Do not put secrets in `application.properties`, source control, audit details, deployment
  scripts, systemd units, argv or environment literals.
- Do not use the demo account or demo password in production.
- Do not delete `MYPAAS_KEYS_DIR` while encrypted data exists.
- Do not reuse a step-up grant; it is consumed on first use.
- Do not treat a client-supplied ID as authorization; every service call re-checks role,
  membership and ownership.
