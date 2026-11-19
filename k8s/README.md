# Miru Kubernetes Deployment Blueprint

This directory contains a ready-to-deploy Kustomize blueprint for `homelab-k8s` (`apps/workloads/media/miru`, namespace `media`).

## Integration Checklist (`homelab-k8s`)

1. Copy `k8s/` into `apps/workloads/media/miru/` (excluding this `README.md`) and pin `images[0].newTag` to the released `<version>@sha256:<digest>` from GHCR.
2. Create the OpenBao secret at `media/miru` containing:
   - `PLEX_TOKEN`
   - `AUTH_SESSION_SECRET` (at least 32 random characters)
   - `MIRU_ADMIN_USERNAME`
   - `MIRU_ADMIN_PASSWORD` (at least 12 characters)
3. Allow ingress from Miru to Plex by adding `app: miru` to `fromEndpoints` in `apps/workloads/media/plex/network-policy.yaml` (port `web-ui` / `32400` TCP).
4. Register `miru` in `.context/domains/applications.md` and run `go run .context/sync-inventory.go --check`.

## Switching to NAS PostgreSQL

To use the NAS PostgreSQL instance (`10.10.1.218:15432`) instead of embedded SQLite:

1. Store `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` in OpenBao at `media/miru` and uncomment those entries in `secret.yaml`.
2. Set `DB_ENGINE: "postgres"` in `configmap.yaml`.
3. Optionally remove `persistence/` from `kustomization.yaml` (and switch `Deployment` strategy to `RollingUpdate`) if you do not need persistent disk storage for artwork caching.
