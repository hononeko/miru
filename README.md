# Miru (見る)

Lightweight media server telemetry, live stream monitor, playback history tracker, and library stats engine.

Built with **Kotlin 2.4 / JDK 25**, **Ktor 3**, **Arrow-kt**, and dual **SQLite / PostgreSQL** persistence — exposing a versioned REST + SSE API (with OpenAPI 3.1 contract) for a decoupled frontend.

## Development

Enable the `commit-msg` hook, which enforces [Conventional Commits](https://www.conventionalcommits.org/):

```sh
git config core.hooksPath .githooks
```

Copy `.env.example` to `.env` (git-ignored) and export your local settings, then run:

```sh
./gradlew check
./gradlew run
```

## System Endpoints

- `GET /healthz` — liveness probe
- `GET /readyz` — readiness probe aggregating registered dependency checks
- `GET /api/v1/openapi.yaml` — OpenAPI 3.1 specification

## Real-time Plex Monitoring

Miru keeps live sessions in sync with Plex through two complementary paths:

- **Notification socket** (`PLEX_WEBSOCKET_ENABLED`, default `true`): a WebSocket client on
  `ws(s)://<plex>/:/websockets/notifications` (token sent as the `X-Plex-Token` header) parses `playing`,
  `timeline`, and `activity` notifications. Bursts of `playing` events are debounced before the active sessions
  are re-fetched. Dropped sockets reconnect with exponential backoff (1s → 60s cap, full jitter), and every
  reconnect triggers an immediate re-fetch.
- **Fallback reconciler** (`PLEX_POLL_INTERVAL_SECONDS`, default `20`, minimum `5`): re-fetches active sessions
  whenever the interval elapses without a reconcile, catching clients that crash without emitting `stopped`.
  With `PLEX_WEBSOCKET_ENABLED=false` Miru runs in polling-only mode.

Both stop cleanly when the Ktor application shuts down.

## Container & Kubernetes Deployment

Build and smoke-test the container image locally:

```sh
docker build -t miru:local .
docker run --rm -p 8080:8080 \
  -e PLEX_URL=http://host.docker.internal:32400 \
  -e PLEX_TOKEN=your-plex-token \
  -e AUTH_MODE=none \
  miru:local
```

Ready-to-adapt Kubernetes Kustomize manifests live under [`k8s/`](k8s/README.md).

