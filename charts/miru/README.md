# Miru Helm Chart

Helm chart for deploying [Miru](https://github.com/hononeko/miru) on Kubernetes with `restricted` Pod Security Standard defaults, optional Ingress or Gateway API `HTTPRoute`, `NetworkPolicy`, and Prometheus/Grafana monitoring.

## Prerequisites

- Kubernetes 1.26+
- Helm 3.14+ (tested with Helm 4)
- Optional: Gateway API CRDs (`gateway.networking.k8s.io/v1`) when `httpRoute.enabled=true`
- Optional: Prometheus Operator CRDs (`monitoring.coreos.com/v1`) when `metrics.serviceMonitor.enabled=true`

## Quick Start (SQLite)

Create the namespace with the `restricted` Pod Security Standard enforced (`--create-namespace` would create it without the label):

```sh
kubectl create namespace miru
kubectl label namespace miru pod-security.kubernetes.io/enforce=restricted
```

Generate two 32+ character secrets (`AUTH_SESSION_SECRET` and `MIRU_ENCRYPTION_KEY`) and install the chart from GHCR:

```sh
helm install miru oci://ghcr.io/hononeko/charts/miru \
  --namespace miru \
  --set auth.sessionSecret="$(openssl rand -base64 48)" \
  --set security.encryptionKey="$(openssl rand -base64 48)"
```

Each Miru release publishes the chart with the same version, and the chart's default image is that release, so chart `1.1.0` runs `ghcr.io/hononeko/miru:1.1.0`. Without `--version`, Helm installs the newest release; pass `--version` to pin one. The [releases page](https://github.com/hononeko/miru/releases) also attaches the packaged chart.

Installing from a checkout (`helm install miru ./charts/miru ...`) runs the `edge` image, the latest build of `main`, to match the chart on `main`.

The chart refuses to render without a session secret of at least 32 characters (unless `auth.mode=none`). Later `helm upgrade` runs keep both values when you don't pass them again: the chart reads them back from the Secret it created. `helm template`, `--dry-run=client` and Argo CD can't read the cluster, so with those, set the values in your values file or use `existingSecret`. Keep `MIRU_ENCRYPTION_KEY` stable: a new key can't read the credentials saved with the old one.

Port-forward to access the UI:

```sh
kubectl -n miru port-forward svc/miru 8080:8080
```

Open <http://127.0.0.1:8080>, create the first admin account, and connect your Plex or Jellyfin server from **Admin**.

## Common Configurations

### 1. External Secret (External Secrets, Sealed Secrets, SOPS)

Create a Secret containing `AUTH_SESSION_SECRET`, `MIRU_ENCRYPTION_KEY`, and any optional credentials (`PLEX_TOKEN`, `JELLYFIN_API_KEY`, `DATABASE_PASSWORD`, `OIDC_CLIENT_SECRET`, `MIRU_ADMIN_PASSWORD`), then reference it via `existingSecret`:

```yaml
existingSecret: miru-secrets
```

When `existingSecret` is set, the chart creates no `Secret` of its own, and it refuses to render if one of the secret values above is also set in your values, since it would be ignored. Settings that aren't secret (`database.postgres.url`, `database.postgres.user`, `auth.adminUsername`) still come from your values and go into the `ConfigMap`. A key in your Secret overrides the `ConfigMap`, so a `DATABASE_URL` kept in the Secret still works.

### 2. PostgreSQL with `RollingUpdate`

With PostgreSQL, Miru keeps no state on disk, so the SQLite `PersistentVolumeClaim` is automatically omitted and you can use `RollingUpdate`:

```yaml
strategy:
  type: RollingUpdate

database:
  engine: postgres
  postgres:
    url: jdbc:postgresql://postgres.database.svc.cluster.local:5432/miru
    user: miru
    maxPoolSize: 10
```

Set the password with `--set database.postgres.password=...`, or as `DATABASE_PASSWORD` in your `existingSecret`. Keep it out of the URL, which goes into the `ConfigMap`.

`RollingUpdate` keeps the UI up during upgrades, but the old and new pod both track sessions for a few seconds, so a stream that ends in that window can be recorded twice. The chart refuses `RollingUpdate` with SQLite persistence, and `replicaCount` above `1`: Miru has no leader election, so every pod records every stream.

### 3. Ingress or Gateway API (`HTTPRoute`)

#### Ingress

```yaml
ingress:
  enabled: true
  className: traefik
  hosts:
    - host: miru.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: miru-tls
      hosts:
        - miru.example.com
```

#### Gateway API `HTTPRoute`

```yaml
httpRoute:
  enabled: true
  parentRefs:
    - name: gateway
      namespace: gateway-system
  hostnames:
    - miru.example.com
```

> [!NOTE]
> Live dashboard updates use Server-Sent Events (`/api/v1/streams/events`) with a 15-second keepalive. Disable response buffering or increase idle timeouts on your ingress controller or gateway if connections close early.

Each entry in `httpRoute.rules` is passed through as written (`matches`, `filters`, `timeouts`, `name`, ...), and gets a `backendRefs` to the Miru Service when it has none. For example, to turn off the gateway's request timeout for the SSE stream:

```yaml
httpRoute:
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /
      timeouts:
        request: 0s
```

### 4. `NetworkPolicy`

Enable `networkPolicy.enabled=true` to restrict inbound traffic to the `http` port (and `metrics` port when `metrics.enabled=true`) and outbound traffic to DNS (`53`), Plex (`32400`), Jellyfin (`8096`), HTTPS (`443`), plus PostgreSQL (`5432`) when `database.engine=postgres`. Plex and Jellyfin are allowed by default so a server connected from the Admin UI is reachable; turn off the one you don't use with `networkPolicy.egress.plex.enabled=false` or `networkPolicy.egress.jellyfin.enabled=false`, change `networkPolicy.egress.*.port` if your server listens elsewhere, and optionally restrict destinations with `networkPolicy.egress.<target>.to`:

```yaml
networkPolicy:
  enabled: true
  ingressFrom:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: ingress-system
  metricsFrom:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: monitoring
  egress:
    postgres:
      to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: database
```

### 5. Prometheus & Grafana

Enable Prometheus metrics on dedicated internal port `9090`, a `ServiceMonitor`, and the Grafana sidecar dashboard `ConfigMap`:

```yaml
metrics:
  enabled: true
  serviceMonitor:
    enabled: true
    # Optional: create the ServiceMonitor where Prometheus runs
    namespace: monitoring
  grafanaDashboard:
    enabled: true
```

The metrics port is served by a separate `ClusterIP` Service, `<release>-metrics`, so `/metrics` stays inside the cluster even when `service.type` is `LoadBalancer` or `NodePort`. When `metrics.serviceMonitor.namespace` is set, the `ServiceMonitor` gets a `namespaceSelector` for the release namespace.

### 6. Single Sign-On (OIDC)

```yaml
auth:
  mode: oidc # or "local" with oidc.enabled: true
  oidc:
    enabled: true
    issuerUrl: https://auth.example.com/application/o/miru/
    clientId: miru
    # Set clientSecret via --set or existingSecret
    redirectUri: https://miru.example.com/api/v1/auth/oidc/callback
    adminGroups: miru-admins
    userGroups: miru-users
```

## Storage

With SQLite, the playback history lives on the `<release>-data` `PersistentVolumeClaim`. It has `helm.sh/resource-policy: keep` (`persistence.keep: true`), so `helm uninstall` leaves it in place, and reinstalling with the same release name and namespace picks it up again. Delete it yourself to drop the data:

```sh
kubectl -n miru delete pvc miru-data
```

## Pod Security Standard

The chart enforces Kubernetes `restricted` Pod Security Standard settings by default (`runAsNonRoot: true`, `runAsUser: 10001`, `runAsGroup: 10001`, `fsGroup: 10001`, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: ["ALL"]`, `seccompProfile.type: RuntimeDefault`, `automountServiceAccountToken: false`, `enableServiceLinks: false`), bounds `/tmp` via `tmpVolume.sizeLimit` (`512Mi`), and supports SHA-256 image digest pinning (`image.digest`).

The chart doesn't create the namespace. Label it to have Kubernetes enforce the `restricted` profile for every pod in it, as in the [Quick Start](#quick-start-sqlite):

```sh
kubectl label namespace miru pod-security.kubernetes.io/enforce=restricted
```

For the full environment variable reference, see [`docs/environment-variables.md`](../../docs/environment-variables.md).
