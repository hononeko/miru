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

