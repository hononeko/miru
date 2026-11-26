# Miru Helm Chart

Helm chart for deploying [Miru](https://github.com/hononeko/miru) on Kubernetes with `restricted` Pod Security Standard defaults, optional Ingress or Gateway API `HTTPRoute`, `NetworkPolicy`, and Prometheus/Grafana monitoring.

## Prerequisites

- Kubernetes 1.26+
- Helm 3.14+ (tested with Helm 4)
- Optional: Gateway API CRDs (`gateway.networking.k8s.io/v1`) when `httpRoute.enabled=true`
- Optional: Prometheus Operator CRDs (`monitoring.coreos.com/v1`) when `metrics.serviceMonitor.enabled=true`

## Quick Start (SQLite)

Generate two 32+ character secrets (`AUTH_SESSION_SECRET` and `MIRU_ENCRYPTION_KEY`) and install the chart:

```sh
helm install miru ./charts/miru \
  --namespace miru \
  --create-namespace \
  --set auth.sessionSecret="$(openssl rand -base64 48)" \
  --set security.encryptionKey="$(openssl rand -base64 48)"
```

Port-forward to access the UI:

```sh
kubectl -n miru port-forward svc/miru 8080:8080
```

Open <http://127.0.0.1:8080>, create the first admin account, and connect your Plex or Jellyfin server from **Admin**.

## Common Configurations

### 1. External Secret (External Secrets, Sealed Secrets, SOPS)

Create a Secret containing `AUTH_SESSION_SECRET`, `MIRU_ENCRYPTION_KEY`, and any optional credentials (`PLEX_TOKEN`, `JELLYFIN_API_KEY`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `OIDC_CLIENT_SECRET`, `MIRU_ADMIN_USERNAME`, `MIRU_ADMIN_PASSWORD`), then reference it via `existingSecret`:

```yaml
existingSecret: miru-secrets
```

When `existingSecret` is set, the chart skips creating its own `Secret` resource.

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
    # Set password via --set, an encrypted values file, or existingSecret
    password: ""
    maxPoolSize: 10
```

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

### 4. `NetworkPolicy`

Enable `networkPolicy.enabled=true` to restrict inbound traffic to the `http` port (and `metrics` port when `metrics.enabled=true`) and outbound traffic to DNS (`53`), Plex (`32400`), HTTPS (`443`), plus Jellyfin (`8096`) and PostgreSQL (`5432`) when configured:

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
```

### 5. Prometheus & Grafana

Enable Prometheus metrics on dedicated internal port `9090`, a `ServiceMonitor`, and the Grafana sidecar dashboard `ConfigMap`:

```yaml
metrics:
  enabled: true
  serviceMonitor:
    enabled: true
  grafanaDashboard:
    enabled: true
```

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

## Pod Security Standard

The chart enforces Kubernetes `restricted` Pod Security Standard settings by default (`runAsNonRoot: true`, `runAsUser: 10001`, `runAsGroup: 10001`, `fsGroup: 10001`, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: ["ALL"]`, `seccompProfile.type: RuntimeDefault`, `automountServiceAccountToken: false`).

If creating a namespace manually, enforce the `restricted` profile:

```sh
kubectl label namespace miru pod-security.kubernetes.io/enforce=restricted
```

For the full environment variable reference, see [`docs/environment-variables.md`](../../docs/environment-variables.md).
