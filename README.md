# Miru (見る)

Lightweight, security-hardened media server telemetry, live stream monitor, playback history tracker, and library analytics engine for Plex.

Built with **Kotlin 2.4 / JDK 25**, **Ktor 3**, **Arrow-kt**, and dual **SQLite / PostgreSQL** persistence — bundling a decoupled SvelteKit 2 + Svelte 5 PWA and OpenAPI 3.1 API into a **68.5 MiB** shell-less Distroless container.

## Screenshots

### Desktop — Live Activity

![Miru desktop dashboard showing live Plex streams, bandwidth, direct play ratio, watch time, session breakdown, plays per day, and top watched titles](docs/screenshots/dashboard_web.png)

### Mobile PWA — Dark & Light Themes

<p align="center">
  <img src="docs/screenshots/dashboard_mobile_dark.png" alt="Miru mobile PWA dark theme showing Live Activity summary cards and active Plex streams" width="280" />
  <img src="docs/screenshots/dashboard_mobile_light.png" alt="Miru mobile PWA light theme showing Live Activity summary cards and active Plex streams" width="280" />
</p>

## Performance

Benchmarked side-by-side against **Tautulli v2.19.1** on a shared **10,000-play** dataset (`2 vCPU / 512 MiB` containers, Mock Plex HTTP + WebSocket server):

| Metric | Miru (SQLite) | Miru (PostgreSQL 17) | Tautulli (SQLite) |
| :--- | :---: | :---: | :---: |
| **Live UI update latency** *(start/pause/stop)* | **`526–530 ms`** *(WS + SSE)* | — | `9.5 s` *(10 s poll)* |
| **30s live traffic** *(3 active streams)* | **`0 reqs / 8.7 KiB`** | — | `3 reqs / 64.7 KiB` |
| **Cold-load transfer** *(browser, uncached)* | **`334.5 KiB`** *(Brotli `.br`)* | — | `504.2 KiB` *(gzip)* |
| **History p.1 (`c=10`, p50 / p99)** | `29.9 / 67.5 ms` (`302 req/s`) | **`11.5 / 30.3 ms`** (`757 req/s`) | `247.9 / 395.0 ms` (`41 req/s`) |
| **30d stats (`c=10`, p50)** | `133.0 ms` (`72 req/s`) | **`19.7 ms`** (`484 req/s`) | `853.7 ms` (`12 req/s`) |
| **All-time stats (`c=10`, p50)** | `367.2 ms` (`26 req/s`) | **`101.2 ms`** (`99 req/s`) | `1,712.9 ms` (`6 req/s`) |
| **Cached artwork (`c=10`, p50)** | **`2.87 ms`** (`3,197 req/s`) | — | `10.8 ms` (`900 req/s`) |
| **Cold start / 10k Tautulli import** | **`1.15 s`** / **`773 ms`** | **`1.22 s`** / **`1.92 s`** | `1.49 s` / — |
| **Image size (`amd64`, compressed / unpacked)** | **`68.5 MiB` / `176.8 MiB`** | **`68.5 MiB` / `176.8 MiB`** *(+ PG)* | `72.8 MiB` / `296.1 MiB` |

Miru delivers **18× faster live UI updates** with **7.4× less live traffic**, **34% smaller cold-load wire transfer**, **17×–42× higher concurrent query throughput** on PostgreSQL (with API gzip active), **3.6× higher concurrent cached artwork throughput**, **23% faster cold startup**, and a **40% smaller unpacked Distroless image** (while Tautulli keeps a smaller idle RAM footprint at `82 MiB` vs Miru's `164–185 MiB` working set under load). See [`docs/benchmarks.md`](docs/benchmarks.md) for full methodology and trade-offs.

## Security

Miru isolates Plex credentials, sessions, and outbound fetches behind a hardened Ktor API boundary:

- **Auth, rate limiting & RBAC:** BouncyCastle **Argon2id** (`Semaphore(4)` concurrency cap, zeroized `CharArray`, constant-time timing parity), bounded per-IP/per-account rate limiting (`429` + `Retry-After`), proxy-aware `Secure` + `HttpOnly; SameSite=Lax` HMAC-SHA256 session cookies, native **OIDC (PKCE `S256`)** with asymmetric JWS allowlists and verified-email linking, SHA-256 hashed API keys, and fail-closed per-user & Plex library-section RBAC across history, stats, SSE, metadata, and artwork.
- **Zero Plex token exposure:** Plex OAuth tokens and outbound webhook secrets are encrypted at rest with **AES-256-GCM** (`MIRU_ENCRYPTION_KEY`); `PLEX_TOKEN` stays server-side, secrets are redacted in logs/`toString()`, and errors return RFC 7807 Problem Details without stack traces.
- **Headers, SSRF & parser hardening:** Global HTTP security headers (`nosniff`, `DENY`, strict API/SPA **CSP** with startup SHA-256 script hashes), DNS public-IP validation + socket-pinned HTTPS avatar proxy (blocking private/metadata IPs & DNS rebinding), SSRF-filtered **HMAC-SHA256** outbound webhooks, XXE-disabled XML parsers, and 256 MiB capped read-only Tautulli SQLite imports.
- **Distroless & supply-chain gates:** Digest-pinned shell-less **`distroless/base-nossl-debian13:nonroot`** + 14-module `jlink` JRE (`UID 10001`), read-only rootfs with `--tmpfs /tmp:noexec,nosuid,nodev`, Kubernetes `restricted` PSS + `NetworkPolicy`, blocking **Trivy** `CRITICAL,HIGH` CVE gate, CodeQL `security-extended`, SHA-pinned Actions, and **GitHub Artifact Attestations**.

See [`docs/security.md`](docs/security.md) for the complete source-verified security profile.

## Quick Deployment

Multi-arch (`amd64`, `arm64`) images are published at [`ghcr.io/hononeko/miru`](https://github.com/hononeko/miru/pkgs/container/miru) (port `8080`, SQLite in `/data` or PostgreSQL via `DB_ENGINE=postgres`).

### Docker Compose

Copy [`docs/docker-compose.yml`](docs/docker-compose.yml) into an empty directory, generate two 32+ char secrets, and start:

```sh
cat > .env <<EOF
AUTH_SESSION_SECRET=$(openssl rand -base64 48)
MIRU_ENCRYPTION_KEY=$(openssl rand -base64 48)
EOF
docker compose up -d
```

Open <http://localhost:8080>, complete first-run admin setup, and connect Plex from **Admin** (or set `PLEX_URL` + `PLEX_TOKEN`).

### Kubernetes & Configuration

- **Kubernetes:** [`docs/k8s/`](docs/k8s/README.md) provides `restricted` PSS Kustomize manifests with optional Gateway API route, Ingress, and `NetworkPolicy`.
- **Configuration:** Every setting is documented with defaults and validation rules in [`docs/environment-variables.md`](docs/environment-variables.md).
- **Real-time Plex sync:** Subscribes to Plex's WebSocket notification stream (`PLEX_WEBSOCKET_ENABLED=true`) with debounced updates, jittered reconnect backoff, and a fallback reconciler (`PLEX_POLL_INTERVAL_SECONDS=20`), pushing live updates to browsers over SSE.
- **System endpoints:** `GET /healthz` (liveness), `GET /readyz` (readiness), `GET /api/v1/openapi.yaml` (OpenAPI 3.1 contract).

## Development

```sh
git config core.hooksPath .githooks   # enforce Conventional Commits
cp .env.example .env                  # configure local environment
./gradlew check && ./gradlew run      # backend checks & server on :8080
```

The SvelteKit 2 + Svelte 5 PWA lives in [`web/`](web/):

```sh
cd web
bun install
bun run api:gen   # regenerate TypeScript client types from OpenAPI spec
bun run dev       # Vite dev server on :5173 (proxies /api, /healthz, /readyz to :8080)
bun run check && bun run test && bun run build
```

Build and smoke-test the container locally:

```sh
docker build -t miru:local .
docker run --rm -p 8080:8080 --read-only --tmpfs /tmp:noexec,nosuid,nodev \
  -e PLEX_URL=http://host.docker.internal:32400 \
  -e PLEX_TOKEN=your-plex-token \
  -e AUTH_MODE=none \
  miru:local
```
