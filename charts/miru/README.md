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

### 2. PostgreSQL

With PostgreSQL, Miru keeps no SQLite database on disk, so the SQLite `PersistentVolumeClaim` is automatically omitted:

```yaml
database:
  engine: postgres
  postgres:
    url: jdbc:postgresql://postgres.database.svc.cluster.local:5432/miru
    user: miru
    maxPoolSize: 10
```

Set the password with `--set database.postgres.password=...`, or as `DATABASE_PASSWORD` in your `existingSecret`. Keep it out of the URL, which goes into the `ConfigMap`.

Even with PostgreSQL, `Deployment` still requires `strategy.type: Recreate` (and `replicaCount <= 1`): Miru has no leader election, so overlapping pods during a `Deployment` `RollingUpdate` would record duplicate streams, trigger duplicate guardrails, and emit duplicate webhooks. If you prefer a `StatefulSet` controller, `workload.kind: StatefulSet` with `updateStrategy.type: RollingUpdate` terminates `pod-0` before recreating it.

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
>
> When Miru is behind a single trusted ingress controller or gateway, set `server.trustProxyHeaders: true` so rate limiting uses the real client IP from `X-Forwarded-For` (and optionally `auth.cookieSecure: true` if always served over HTTPS).

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

Local username/password login (`auth.mode: local`, `auth.oidc.enabled: false`) is the default; OIDC is optional. Enable it alongside local login (`auth.mode: local` + `auth.oidc.enabled: true`) or as the only login method (`auth.mode: oidc`):

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

### 7. Disabling Built-In Auth (Forward-Auth / Trusted Network)

If an external proxy (Authelia, Authentik Outpost, oauth2-proxy, Cloudflare Access, Tailscale, etc.) handles authentication in front of Miru, set `auth.mode: none` so users are not prompted twice. No `auth.sessionSecret` is required (keep `security.encryptionKey` if you sign into Plex from the Admin UI).

> [!IMPORTANT]
> With `auth.mode: none`, any pod in the cluster can reach `svc/miru:8080` directly and bypass your forward-auth proxy unless you keep `service.type: ClusterIP` and restrict `networkPolicy.ingressFrom` to the ingress controller or gateway namespace (plus Kometa if `tautulliCompat.enabled: true`).

```yaml
auth:
  mode: none

server:
  trustProxyHeaders: true

ingress:
  enabled: true
  className: traefik
  annotations:
    traefik.ingress.kubernetes.io/router.middlewares: auth-forwardauth@kubernetescrd
  hosts:
    - host: miru.example.com
      paths:
        - path: /
          pathType: Prefix

networkPolicy:
  enabled: true
  ingressFrom:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: ingress-system
```

## Workload Kinds (`Deployment` vs `StatefulSet`)

Miru runs as a single pod (`replicaCount` is capped at `0` or `1` because Miru has no leader election). By default (`workload.kind: Deployment`), the chart renders a `Deployment` and a standalone `PersistentVolumeClaim`. When `workload.kind: StatefulSet` is set, the chart renders a `StatefulSet` with a headless `Service` and `spec.volumeClaimTemplates` (when SQLite persistence is active and `persistence.existingClaim` is unset).

### Comparison

| Aspect | `Deployment` (default) | `StatefulSet` |
| --- | --- | --- |
| **Rendered workload** | `Deployment` (`<fullname>`) | `StatefulSet` (`<fullname>`) |
| **Headless Service** | None | `Service` (`<fullname>-headless`, `clusterIP: None`) |
| **SQLite PVC (default)** | Standalone `PersistentVolumeClaim` (`<fullname>-data`) | `spec.volumeClaimTemplates` (`data`, creating PVC `data-<fullname>-0`) |
| **`existingClaim` / `postgres` / `persistence.enabled: false`** | Omits standalone PVC; mounts `existingClaim` or `emptyDir` | Omits `volumeClaimTemplates`; mounts `existingClaim` or `emptyDir` |
| **Upgrade behavior** | `strategy.type: Recreate` (refuses `RollingUpdate` for both SQLite and PostgreSQL) | `workload.statefulSet.updateStrategy.type: RollingUpdate` or `OnDelete` (terminates `pod-0` before recreating it; `strategy.*` is ignored) |
| **PVC retention on uninstall** | `persistence.keep: true` adds `helm.sh/resource-policy: keep` to `<fullname>-data` | `workload.statefulSet.persistentVolumeClaimRetentionPolicy.whenDeleted` (`Retain` or `Delete`) |
| **When to choose** | Standard single-pod installs, PostgreSQL deployments, or simple Helm-managed PVCs | GitOps / backup tools expecting `StatefulSet` `volumeClaimTemplates` (e.g. VolSync / Kopiur) or stable pod ordinal DNS (`<fullname>-0.<fullname>-headless`) |

### `workload.*` Values

| Key | Type | Default | Allowed / Notes |
| --- | --- | --- | --- |
| `workload.kind` | `string` | `"Deployment"` | `"Deployment"` or `"StatefulSet"`. |
| `workload.annotations` | `object` | `{}` | Optional metadata annotations applied to the `Deployment` or `StatefulSet` (e.g. `reloader.stakater.com/auto: "true"`). |
| `workload.statefulSet.podManagementPolicy` | `string` | `"OrderedReady"` | `"OrderedReady"` or `"Parallel"` (`StatefulSet` only). |
| `workload.statefulSet.updateStrategy.type` | `string` | `"RollingUpdate"` | `"RollingUpdate"` or `"OnDelete"` (`StatefulSet` only). |
| `workload.statefulSet.updateStrategy.rollingUpdate` | `object` | unset | Optional `StatefulSet` `rollingUpdate` settings (e.g. `partition`). |
| `workload.statefulSet.persistentVolumeClaimRetentionPolicy.whenDeleted` | `string` | `"Retain"` | `"Retain"` or `"Delete"` for `volumeClaimTemplates` PVCs when the `StatefulSet` is deleted. |
| `workload.statefulSet.persistentVolumeClaimRetentionPolicy.whenScaled` | `string` | `"Retain"` | `"Retain"` or `"Delete"` for `volumeClaimTemplates` PVCs when scaled down. |

### `persistence.*` Values

| Key | Type | Default | Allowed / Notes |
| --- | --- | --- | --- |
| `persistence.enabled` | `bool` | `true` | Creates/mounts persistent SQLite storage. Ignored (uses `emptyDir`) when `database.engine` is `postgres`/`postgresql`. |
| `persistence.existingClaim` | `string` | `""` | Existing PVC name to mount at `/data`. Omits standalone PVC / `volumeClaimTemplates`. Mutually exclusive with `dataSource` and `dataSourceRef`. |
| `persistence.storageClassName` | `string` | `""` | StorageClass name; empty uses cluster default, `"-"` renders `storageClassName: ""` to disable dynamic provisioning. |
| `persistence.accessModes` | `list[string]` | `["ReadWriteOnce"]` | `ReadWriteOnce`, `ReadOnlyMany`, `ReadWriteMany`, `ReadWriteOncePod`. |
| `persistence.size` | `string` | `"1Gi"` | PVC requested storage size (`resources.requests.storage`). |
| `persistence.keep` | `bool` | `true` | Adds `helm.sh/resource-policy: keep` to the standalone PVC in `Deployment` mode. Ignored for `StatefulSet` `volumeClaimTemplates` (use `whenDeleted`). |
| `persistence.annotations` | `object` | `{}` | Metadata annotations applied to the standalone PVC or `volumeClaimTemplates[0].metadata.annotations`. |
| `persistence.dataSourceRef` | `object` | `{}` | `TypedObjectReference` (`kind`, `name`, optional `apiGroup`, optional `namespace`). Rendered on standalone PVC and `volumeClaimTemplates[0].spec`. Mutually exclusive with `dataSource` and `existingClaim`. |
| `persistence.dataSource` | `object` | `{}` | `TypedLocalObjectReference` (`kind`, `name`, optional `apiGroup`). Rendered on standalone PVC and `volumeClaimTemplates[0].spec`. Mutually exclusive with `dataSourceRef` and `existingClaim`. |

### Examples

#### 1. `Deployment` with Standalone PVC and `dataSourceRef` / `VolumeSnapshot`

Restore a standalone `<fullname>-data` PVC from an existing `VolumeSnapshot` (via `dataSourceRef` or `dataSource`):

```yaml
workload:
  kind: Deployment

persistence:
  size: 1Gi
  storageClassName: longhorn
  dataSourceRef:
    apiGroup: snapshot.storage.k8s.io
    kind: VolumeSnapshot
    name: miru-data-snapshot
```

#### 2. `StatefulSet` with `volumeClaimTemplates` and VolSync / Kopiur `dataSourceRef`

Run Miru as a `StatefulSet` backed by `volumeClaimTemplates` (`data-<fullname>-0`) populated from a VolSync `ReplicationDestination` (or Kopiur restore source):

```yaml
workload:
  kind: StatefulSet
  annotations:
    reloader.stakater.com/auto: "true"
  statefulSet:
    podManagementPolicy: OrderedReady
    updateStrategy:
      type: RollingUpdate
    persistentVolumeClaimRetentionPolicy:
      whenDeleted: Retain
      whenScaled: Retain

persistence:
  size: 1Gi
  storageClassName: longhorn
  dataSourceRef:
    apiGroup: volsync.backube
    kind: ReplicationDestination
    name: miru-data
```

#### 3. `StatefulSet` with `persistence.existingClaim` (Omits `volumeClaimTemplates`)

Use `StatefulSet` pod scheduling while mounting a pre-existing PVC directly (omits `spec.volumeClaimTemplates`):

```yaml
workload:
  kind: StatefulSet

persistence:
  existingClaim: miru-data
```

#### 4. `StatefulSet` with PostgreSQL or `persistence.enabled: false` (Omits `volumeClaimTemplates`)

When `database.engine` is `postgres` (or `persistence.enabled: false`), `volumeClaimTemplates` is omitted and `/data` uses an `emptyDir` volume:

```yaml
workload:
  kind: StatefulSet

database:
  engine: postgres
  postgres:
    url: jdbc:postgresql://postgres.database.svc.cluster.local:5432/miru
    user: miru

existingSecret: miru-secrets
```

### Migrating from `Deployment` to `StatefulSet`

A `Deployment` mounts a standalone PVC named `<fullname>-data` (for example `miru-data`), whereas a `StatefulSet` with `volumeClaimTemplates` creates and mounts `data-<fullname>-0` (for example `data-miru-0`). Switching `workload.kind` from `Deployment` to `StatefulSet` in place will **not** automatically attach `miru-data`; instead, Kubernetes will provision a new empty `data-miru-0` PVC unless you follow one of these paths:

- **Option A — Reuse `<fullname>-data` via `persistence.existingClaim`:**
  1. Scale the `Deployment` down or accept brief downtime during the switch so two pods never open the SQLite file simultaneously.
  2. Set `workload.kind: StatefulSet` and `persistence.existingClaim: miru-data` (replace `miru` with your release `<fullname>`).
  3. Run `helm upgrade`. The `StatefulSet` omits `volumeClaimTemplates` and mounts `miru-data` directly.
- **Option B — Populate `data-<fullname>-0` via snapshot / `dataSourceRef`:**
  1. Scale the `Deployment` to `0` (`--set replicaCount=0`) so SQLite flushes cleanly to disk.
  2. Create a `VolumeSnapshot` (or VolSync backup) of `<fullname>-data` (`miru-data`).
  3. Set `workload.kind: StatefulSet`, `replicaCount: 1`, and point `persistence.dataSourceRef` (or `persistence.dataSource`) at the snapshot / `ReplicationDestination`.
  4. Run `helm upgrade`. Kubernetes provisions `data-<fullname>-0` pre-populated from the snapshot, and mounts it at `/data`. (Remove `dataSourceRef` only if re-creating the `StatefulSet`, as `volumeClaimTemplates` is immutable.)

> [!WARNING]
> **`StatefulSet.spec.volumeClaimTemplates` immutability:** Kubernetes forbids modifying `spec.volumeClaimTemplates` (including `size`, `storageClassName`, `accessModes`, `annotations`, `dataSourceRef`, or `dataSource`) on an existing `StatefulSet` in place. To change any of those fields after initial creation, delete the `StatefulSet` without deleting its pod or PVC (`kubectl -n miru delete sts miru --cascade=orphan`) before running `helm upgrade` (and resize the underlying `data-<fullname>-0` PVC separately if supported by your `StorageClass`).

### Validation Rules

Both `values.schema.json` and the chart's `miru.validate` helper reject unsafe or conflicting settings at render/lint time:

- `workload.kind` must be `Deployment` or `StatefulSet`.
- `replicaCount` must be `0` or `1` (multiple pods would duplicate playback history).
- `strategy.type: RollingUpdate` is rejected when `workload.kind` is `Deployment` regardless of database engine (`sqlite` or `postgres`), because Miru has no leader election and overlapping pods would record duplicate streams, guardrails, and webhooks (ignored when `workload.kind` is `StatefulSet`, whose `updateStrategy.type: RollingUpdate` terminates `pod-0` before recreating it).
- `strategy.rollingUpdate` cannot be set when `strategy.type` is `Recreate` in `Deployment` mode.
- `persistence.dataSource` and `persistence.dataSourceRef` cannot be set simultaneously.
- Neither `persistence.dataSource` nor `persistence.dataSourceRef` may be set alongside `persistence.existingClaim`.
- When `persistence.dataSourceRef` or `persistence.dataSource` is non-empty, both `kind` and `name` are required.

## Storage

With `workload.kind: Deployment` and SQLite, the playback history lives on `<fullname>-data`, which carries `helm.sh/resource-policy: keep` (`persistence.keep: true`) so `helm uninstall` leaves it in place:

```sh
kubectl -n miru delete pvc miru-data
```

With `workload.kind: StatefulSet` and `volumeClaimTemplates`, the claim is named `data-<fullname>-0` and its lifecycle is governed by `workload.statefulSet.persistentVolumeClaimRetentionPolicy`:

```sh
kubectl -n miru delete pvc data-miru-0
```

## Pod Security Standard

The chart enforces Kubernetes `restricted` Pod Security Standard settings by default (`runAsNonRoot: true`, `runAsUser: 10001`, `runAsGroup: 10001`, `fsGroup: 10001`, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: ["ALL"]`, `seccompProfile.type: RuntimeDefault`, `automountServiceAccountToken: false`, `enableServiceLinks: false`), bounds `/tmp` via `tmpVolume.sizeLimit` (`512Mi`), and supports SHA-256 image digest pinning (`image.digest`).

The chart doesn't create the namespace. Label it to have Kubernetes enforce the `restricted` profile for every pod in it, as in the [Quick Start](#quick-start-sqlite):

```sh
kubectl label namespace miru pod-security.kubernetes.io/enforce=restricted
```

For the full environment variable reference, see [`docs/environment-variables.md`](../../docs/environment-variables.md).
