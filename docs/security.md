# Miru Security Profile

This document captures Miru's security architecture, threat model, and source-verified controls across the backend, frontend, container build, Kubernetes examples, and CI/CD workflows.

> [!NOTE]
> Every control below is verified directly against the repository source code.

---

## 1. Architecture & Trust Boundaries

Miru isolates untrusted browser/API clients from the Plex Media Server, database storage, external identity/avatar providers, and outbound webhook endpoints behind a single typed Ktor HTTP/SSE boundary:

```
┌───────────────────────────┐
│   Browser / API Clients   │  Untrusted (Session Cookie / API Key)
└─────────────┬─────────────┘
              │ HTTP / SSE (JSON, HTML, Problem+JSON + Security Headers / CSP)
┌─────────────▼─────────────┐
│   Miru Server (Ktor 3)    │  Trust Boundary (Distroless, UID 10001):
│   Non-exec /tmp, RO root  │  • Global SecurityHeaders + AuthRateLimiter
│   Arrow Either<MiruError> │  • AuthMiddleware + Library-Section RBAC
└──────┬──────────┬─────────┘
       │          │
       │          ├──────────────────────► SQLite (/data/miru.db) or PostgreSQL
       │          ├──────────────────────► Plex Media Server (X-Plex-Token, WS/HTTP)
       │          ├──────────────────────► OIDC IdP & DNS-Pinned Avatar CDNs (HTTPS)
       │          └──────────────────────► Outbound Webhooks (HMAC-SHA256, SSRF-Filtered)
```

### Core Design Guarantees

- **Fail-closed startup configuration ([`AppConfig.kt`](../src/main/kotlin/app/hononeko/miru/config/AppConfig.kt))**: Rejects insecure or incomplete startup states before binding a socket — requiring `AUTH_SESSION_SECRET` ≥ 32 characters whenever `AUTH_MODE != none`, requiring `MIRU_ENCRYPTION_KEY` ≥ 32 characters when configured, enforcing `PasswordPolicy` on `MIRU_ADMIN_PASSWORD`, and validating all rate-limit and cookie flags.
- **Typed domain errors without stack traces ([`ProblemDetails.kt`](../src/main/kotlin/app/hononeko/miru/http/ProblemDetails.kt))**: Service and repository errors flow through Arrow-kt `Either<MiruError, T>` and serialize to RFC 7807 / RFC 9457 `application/problem+json`. Database, upstream auth, upstream availability, and unhandled exceptions return generic client details while logging diagnostic context server-side.
- **Proxied media access**: Clients never communicate directly with Plex or receive `PLEX_TOKEN`; metadata and artwork pass through Miru's authenticated, RBAC-checked routes.

---

## 2. Authentication, Rate Limiting & RBAC

| Mechanism | Implementation Details | Source |
| :--- | :--- | :--- |
| **Password hashing** | BouncyCastle Argon2id v1.3 (`m=19456` KiB, `t=2` iterations, `p=1` parallelism, 16-byte `SecureRandom` salt, 32-byte hash) | [`PasswordHasher.kt`](../src/main/kotlin/app/hononeko/miru/auth/PasswordHasher.kt) |
| **Password memory, timing & concurrency bounds** | Converts passwords to `CharArray` zeroized in `finally`; constant-time comparison via `MessageDigest.isEqual`; dummy Argon2id check for unknown/non-local usernames; concurrent hashes bounded by `Semaphore(4)` (`Dispatchers.Default`) to cap Argon2id memory under burst load | [`PasswordHasher.kt`](../src/main/kotlin/app/hononeko/miru/auth/PasswordHasher.kt), [`AuthService.kt`](../src/main/kotlin/app/hononeko/miru/auth/AuthService.kt) |
| **Auth rate limiting** | Bounded-memory (max `10,000` keys) fixed-window limiter on `POST /api/v1/auth/login` (`20`/IP and `10` failures/account per `60s`) and `POST /api/v1/auth/setup` (`10`/IP per `60s`), returning RFC 7807 `429 Too Many Requests` + `Retry-After`. Defaults to socket peer IP (`MIRU_TRUST_PROXY_HEADERS=false`); when enabled behind a trusted proxy, uses the rightmost `X-Forwarded-For` entry | [`AuthRateLimiter.kt`](../src/main/kotlin/app/hononeko/miru/auth/AuthRateLimiter.kt), [`AuthRoutes.kt`](../src/main/kotlin/app/hononeko/miru/http/AuthRoutes.kt) |
| **Password policy** | Minimum 12 characters requiring ≥ 1 lowercase, ≥ 1 uppercase, and ≥ 1 digit (applied to first-run setup, bootstrap env vars, local account creation, self-service password changes via `POST /api/v1/auth/me/password`, and admin password resets via `PATCH /api/v1/admin/accounts/{id}`) | [`PasswordPolicy.kt`](../src/main/kotlin/app/hononeko/miru/auth/PasswordPolicy.kt), [`AuthService.kt`](../src/main/kotlin/app/hononeko/miru/auth/AuthService.kt) |
| **Session & OIDC cookies** | 256-bit `SecureRandom` tokens signed with `HmacSHA256` (`AUTH_SESSION_SECRET`); constant-time verification; stored as SHA-256 hex digests in `auth_sessions` with 7-day TTL and `HttpOnly; SameSite=Lax`; revoked immediately on logout and pruned on startup and hourly (`SessionCleanupService`). `AUTH_COOKIE_SECURE` (default `auto`) sets `Secure` on `miru_session`, `miru_oidc_tx`, and `miru_oidc_hint` whenever requests arrive over TLS or carry `X-Forwarded-Proto: https` / `Forwarded: proto=https` | [`TokenCrypto.kt`](../src/main/kotlin/app/hononeko/miru/auth/TokenCrypto.kt), [`AuthRoutes.kt`](../src/main/kotlin/app/hononeko/miru/http/AuthRoutes.kt), [`SessionCleanupService.kt`](../src/main/kotlin/app/hononeko/miru/auth/SessionCleanupService.kt) |
| **Admin & account lifecycle guards** | `setupMutex` serializes first-run setup and rejects setup once any account exists, so losing the last admin (for example to a change of SSO groups) does not reopen it; blocks self-deletion, self-demotion, self-disable, and deleting, demoting, or disabling the last remaining active admin; self-service password changes verify the current password via Argon2id and revoke all prior sessions (re-issuing a fresh session cookie for browser callers); disabling an account, downgrading `ADMIN` → `USER`, or resetting its password revokes all active sessions for that account | [`AuthService.kt`](../src/main/kotlin/app/hononeko/miru/auth/AuthService.kt), [`AuthRoutes.kt`](../src/main/kotlin/app/hononeko/miru/http/AuthRoutes.kt) |
| **Native OIDC (SSO)** | Mandatory PKCE `S256`; 256-bit `state` & `nonce`; HMAC-signed `HttpOnly; SameSite=Lax` transaction cookie (`miru_oidc_tx`) scoped to `/api/v1/auth/oidc`; single-use bounded transaction store (10-min TTL, max 1,000 entries); constant-time `state`/`nonce` validation | [`OidcAuthService.kt`](../src/main/kotlin/app/hononeko/miru/auth/oidc/OidcAuthService.kt), [`AuthRoutes.kt`](../src/main/kotlin/app/hononeko/miru/http/AuthRoutes.kt) |
| **ID token JWS verification** | Explicit asymmetric algorithm allowlist (`RS256`, `RS384`, `RS512`, `ES256`, `ES384`, `ES512`, `EdDSA`); explicitly rejects `alg=none` and `HS*` symmetric algorithms; verifies `iss`, `sub`, `aud`, `azp`, `exp`, `nbf`, and `iat` (±60s clock skew); refreshes JWKS on unknown `kid`; verifies `userinfo.sub == id_token.sub` (OIDC Core 5.3.2) | [`OidcJwtVerifier.kt`](../src/main/kotlin/app/hononeko/miru/auth/oidc/OidcJwtVerifier.kt), [`OidcAuthService.kt`](../src/main/kotlin/app/hononeko/miru/auth/oidc/OidcAuthService.kt) |
| **Group RBAC & email linking** | Deny-by-default group-to-role mapping (`OIDC_ADMIN_GROUPS` / `OIDC_USER_GROUPS`), applied at every sign-in unless an admin set the account's role (`MANUAL`), which still takes an authorized group to sign in; OIDC email auto-linking requires `email_verified == true` and respects `MANUAL` link locks set by admins | [`OidcAuthService.kt`](../src/main/kotlin/app/hononeko/miru/auth/oidc/OidcAuthService.kt), [`AuthService.kt`](../src/main/kotlin/app/hononeko/miru/auth/AuthService.kt) |
| **API keys** | `miru_<256-bit base64url>` key shown once at creation; stored as SHA-256 hash + 12-char lookup prefix; supports revocation & expiration; effective role capped by owning account's role | [`TokenCrypto.kt`](../src/main/kotlin/app/hononeko/miru/auth/TokenCrypto.kt), [`AuthService.kt`](../src/main/kotlin/app/hononeko/miru/auth/AuthService.kt) |
| **End-to-end RBAC & library-section scoping** | `ADMIN` vs `USER` enforcement at route level (`authenticated(..., requiredRole = MiruRole.ADMIN)`) across `/api/v1/admin/*` (accounts, API keys, imports, Plex OAuth, guardrails, webhooks), `/api/v1/users*`, `/api/v1/streams/*/terminate`, `POST /api/v1/libraries/sync`, and `GET /api/v1/libraries/{id}/stale`; `USER` accounts scoped to `linkedMediaUserIds` across history, stats, user avatars, and live SSE; `MediaSectionAccessChecker` enforces Plex shared-library section access (fail-closed `403` for unlinked users or unshared sections, backed by DB lookup + bounded 4,096-entry / 10-min LRU cache) on `GET /api/v1/media/{ratingKey}` and `GET /api/v1/media/{ratingKey}/{thumb\|art}` | [`AuthMiddleware.kt`](../src/main/kotlin/app/hononeko/miru/auth/AuthMiddleware.kt), [`MediaSectionAccessChecker.kt`](../src/main/kotlin/app/hononeko/miru/service/MediaSectionAccessChecker.kt), [`MediaDetailsService.kt`](../src/main/kotlin/app/hononeko/miru/service/MediaDetailsService.kt), [`ArtworkProxyService.kt`](../src/main/kotlin/app/hononeko/miru/artwork/ArtworkProxyService.kt) |

---

## 3. Credential & Secret Handling

- **Encryption at rest ([`CredentialCrypto.kt`](../src/main/kotlin/app/hononeko/miru/auth/CredentialCrypto.kt), [`WebhookService.kt`](../src/main/kotlin/app/hononeko/miru/service/WebhookService.kt))**: Persisted Plex OAuth tokens, outbound webhook signing secrets, and custom webhook auth header values are encrypted with **AES-256-GCM** (`v1:<iv>.<ciphertext+tag>`), using a 12-byte random IV per write, 128-bit authentication tag, and a 256-bit key derived via SHA-256 from `MIRU_ENCRYPTION_KEY` (minimum 32 characters).
- **No secret leakage in logs, responses, or models**: Configuration, auth, and webhook models ([`AppConfig.kt`](../src/main/kotlin/app/hononeko/miru/config/AppConfig.kt), [`AuthService.kt`](../src/main/kotlin/app/hononeko/miru/auth/AuthService.kt), [`AuthDtos.kt`](../src/main/kotlin/app/hononeko/miru/http/AuthDtos.kt), [`WebhookDtos.kt`](../src/main/kotlin/app/hononeko/miru/http/WebhookDtos.kt), [`WebhookPersistenceModels.kt`](../src/main/kotlin/app/hononeko/miru/persistence/model/WebhookPersistenceModels.kt)) override `toString()` to redact passwords, session tokens, database passwords, encryption keys, raw API keys, and webhook secrets (`***`). Webhook API responses expose only presence booleans (`hasSigningSecret`, `hasAuthHeader`) and strip URL query strings/userinfo (`WebhookUrlSafety.redactUrl()`), while `WebhookUrlSafety.sanitizeErrorMessage()` scrubs secrets and URLs from delivery errors. [`OidcClient.kt`](../src/main/kotlin/app/hononeko/miru/auth/oidc/OidcClient.kt) sanitizes error messages via `redactSecrets()` before propagation or logging. The request log ([`RequestLogging.kt`](../src/main/kotlin/app/hononeko/miru/http/RequestLogging.kt)) leaves out query strings, including those of redirect targets, which carry OIDC `state`, `nonce` and ID tokens.
- **Server-side Plex authentication**: Upstream Plex requests pass `PLEX_TOKEN` / stored OAuth token in the `X-Plex-Token` HTTP header (never forwarded in browser redirects or JSON payloads).

---

## 4. HTTP Security Headers, SSRF, XML & Upload Boundaries

- **Global HTTP security headers & strict CSP ([`SecurityHeaders.kt`](../src/main/kotlin/app/hononeko/miru/http/SecurityHeaders.kt), [`web/src/app.html`](../web/src/app.html), [`web/static/theme-init.js`](../web/static/theme-init.js))**:
  - Sets `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: camera=(), microphone=(), geolocation=()`, and `Content-Security-Policy` on every response.
  - API & probe paths (`/api/*`, `/healthz`, `/readyz`): `default-src 'none'; frame-ancestors 'none'`.
  - Web SPA routes: `default-src 'self'`, `script-src 'self'` plus SHA-256 hashes of SvelteKit's bootstrap inline script extracted automatically at startup (`resolveWebContentSecurityPolicy`), `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, and `frame-ancestors 'none'`. Theme initialization is served as an external `/theme-init.js` script to eliminate inline script execution.
- **External avatar proxy SSRF & DNS-rebinding defense ([`ArtworkProxyService.kt`](../src/main/kotlin/app/hononeko/miru/artwork/ArtworkProxyService.kt), [`IpAddressSafety.kt`](../src/main/kotlin/app/hononeko/miru/artwork/IpAddressSafety.kt), [`PinnedHttpsAvatarFetcher.kt`](../src/main/kotlin/app/hononeko/miru/artwork/PinnedHttpsAvatarFetcher.kt))**:
  - Restricts targets to relative Plex paths (`/library/...`), the active Plex origin, or allowlisted HTTPS hosts (`plex.tv`, `gravatar.com`, `secure.gravatar.com`, plus Gravatar Photon `/plex.tv/avatars/` on `i0`–`i3.wp.com`).
  - Rejects plain HTTP external URLs, IP literals (IPv4, bracketed/unbracketed IPv6, decimal/hex), userinfo (`@`), fragments (`#`), backslashes/control characters, and path traversal (`..`, `%2e%2e`).
  - Resolves all DNS A/AAAA records prior to connecting and rejects loopback, RFC 1918 private, link-local / cloud metadata (`169.254.0.0/16`), CGNAT (`100.64.0.0/10`), IPv6 ULA (`fc00::/7`), multicast, documentation, and embedded/transition private addresses (IPv4-mapped, NAT64, 6to4).
  - Connects directly to the validated `InetAddress` with `Proxy.NO_PROXY`, explicit TLS SNI (`SNIHostName`), and strict `HTTPS` hostname/certificate verification (`PinnedHttpsAvatarFetcher`), closing DNS-rebinding TOCTOU windows.
  - Disables automatic redirects (`followRedirects = false`) and re-validates every `Location` target across at most 3 hops; enforces an 8 MiB payload cap, `image/*` content-type sanitization, `ratingKey` regex `^[A-Za-z0-9_-]+$`, and `w`/`h` bounds `1..2000`.
- **Outbound webhook SSRF, signing & delivery hardening ([`WebhookUrlSafety.kt`](../src/main/kotlin/app/hononeko/miru/webhook/WebhookUrlSafety.kt), [`WebhookDispatcher.kt`](../src/main/kotlin/app/hononeko/miru/webhook/WebhookDispatcher.kt), [`WebhookService.kt`](../src/main/kotlin/app/hononeko/miru/service/WebhookService.kt))**:
  - Restricted to `MiruRole.ADMIN`; validates `http://`/`https://` targets (≤ 2,048 chars), rejects control characters, embedded userinfo (`@`), and reserved custom auth header names (`Host`, `Content-Length`, `X-Miru-*`, etc.).
  - Blocks cloud metadata hostnames (`metadata.google.internal`, `metadata`, `metadata.goog`, `instance-data`) and resolves all DNS A/AAAA records before every delivery attempt, rejecting link-local / IMDS (`169.254.0.0/16`, `fe80::/10`), unspecified (`0.0.0.0/8`, `::`), multicast/reserved (`224.0.0.0/4`, `240.0.0.0/4`, `ff00::/8`), and embedded IPv4-in-IPv6 equivalents (IPv4-mapped, IPv4-compatible, NAT64, 6to4) while intentionally permitting RFC 1918 LAN and Kubernetes cluster-internal targets.
  - Disables HTTP redirects (`followRedirects = false`), signs payloads with **HMAC-SHA256** (`X-Miru-Signature-256: sha256=<hex>`) when a signing secret is configured, and bounds concurrency (`Semaphore(4)`), queue capacity (`128`), connect/request timeouts (`5s` / `10s`), error body snippets (`1 KiB`), and retained delivery rows (`1,000`).
- **XXE-hardened XML parsing**: Both [`PlexTvUsersXmlParser.kt`](../src/main/kotlin/app/hononeko/miru/adapter/plex/PlexTvUsersXmlParser.kt) and [`PlexTvSharedServersXmlParser.kt`](../src/main/kotlin/app/hononeko/miru/adapter/plex/PlexTvSharedServersXmlParser.kt) configure `DocumentBuilderFactory` with `XMLConstants.FEATURE_SECURE_PROCESSING`, `disallow-doctype-decl = true`, external general/parameter entities disabled, external DTD loading disabled, and XInclude disabled.
- **Admin Tautulli SQLite upload hardening ([`AdminImportRoutes.kt`](../src/main/kotlin/app/hononeko/miru/http/AdminImportRoutes.kt), [`TautulliSqliteReader.kt`](../src/main/kotlin/app/hononeko/miru/importer/TautulliSqliteReader.kt))**:
  - Restricted to `MiruRole.ADMIN`, streams uploads to a temp file capped at **256 MiB** (`copyPartWithLimit`), cleans up in `finally`, verifies the 16-byte `SQLite format 3\0` header and required tables against `sqlite_master`, and opens the file `SQLiteOpenMode.READONLY`.
- **Static SPA path-traversal guards ([`StaticWebRoutes.kt`](../src/main/kotlin/app/hononeko/miru/http/StaticWebRoutes.kt))**: Rejects `..`, `/`, and `\` inside route segments and verifies normalized `candidate.startsWith(root)`.
- **CORS fail-closed by default ([`Application.kt`](../src/main/kotlin/app/hononeko/miru/Application.kt))**: CORS middleware is only installed when `MIRU_CORS_ALLOWED_ORIGINS` is explicitly configured; no wildcard origin is allowed.

---

## 5. Supply Chain & Runtime Hardening

- **Hardened Distroless `jlink` container ([`Dockerfile`](../Dockerfile))**:
  - Multi-stage build using Bun (`oven/bun:1.4.2-alpine`) and Temurin JDK 25 (`eclipse-temurin:25.0.5_8-jdk-noble`), assembling a trimmed **14-module `jlink` JRE** on digest-pinned **`gcr.io/distroless/base-nossl-debian13:nonroot`** (7 Debian 13 packages; no shell, no package manager, no coreutils, no OpenSSL).
  - Pre-extracts target-architecture `libsqlitejdbc.so` to read-only `/opt/miru/native` (`0555`) and strips unused cross-platform native libraries and Netty Quiche from the fat JAR.
  - Owns `/opt/java/openjdk` and `/opt/miru` as `0:0` (read-only to runtime user `10001:10001`), while UID/GID `10001:10001` only writes to `/data` — enabling `--read-only --tmpfs /tmp:noexec,nosuid,nodev` in both SQLite and PostgreSQL deployments.
  - Eliminates Jackson and `logstash-logback-encoder` completely in favor of a zero-dependency structured NDJSON encoder ([`MiruJsonLogEncoder.kt`](../src/main/kotlin/app/hononeko/miru/logging/MiruJsonLogEncoder.kt)), leaving zero Jackson classes in the runtime classpath ([`build.gradle.kts`](../build.gradle.kts), [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)).
- **Kubernetes & Compose hardening ([`docs/k8s/deployment.yaml`](k8s/deployment.yaml), [`docs/k8s/network-policy.yaml`](k8s/network-policy.yaml), [`docs/docker-compose.yml`](docker-compose.yml))**:
  - Example manifests enforce Kubernetes `restricted` Pod Security Standard settings (`runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`, `seccompProfile.type: RuntimeDefault`, `automountServiceAccountToken: false`), non-executable `/tmp` (`tmpfs: /tmp:noexec,nosuid,nodev`), and default-deny `NetworkPolicy` (egress limited to DNS `53`, Plex `32400`, HTTPS `443`, and PostgreSQL `5432`).
- **CI/CD security gates ([`.github/workflows/`](../.github/workflows/))**:
  - **Container CVE gate**: [`image.yml`](../.github/workflows/image.yml) runs `aquasecurity/trivy-action` (`os,library`, `CRITICAL,HIGH`, `exit-code: 1`) on every `amd64` and `arm64` build before publishing.
  - **Static & supply-chain hygiene**: Third-party GitHub Actions pinned to full commit SHAs (enforced by `lint-actions` / `zizmor`); `lint-docker` (Hadolint), `lint-k8s`, and type-resolved `detekt` run on PRs and `main`.
  - **SAST**: [`codeql.yml`](../.github/workflows/codeql.yml) runs GitHub CodeQL `security-extended` across `actions`, `java-kotlin`, and `javascript-typescript`.
  - **Testing & coverage**: Unit tests, Kover (≥ 80% changed-line coverage on backend source changes), PostgreSQL Testcontainers integration tests, and Playwright Chromium E2E tests gate image publishing.
  - **Image provenance**: Multi-arch (`amd64` / `arm64`) images are built and pushed by digest to GHCR with GitHub Artifact Attestations (`attest: "true"`). A release ([`cd.yml`](../.github/workflows/cd.yml)) has the layers of its tested release candidate and an attestation of its own.
