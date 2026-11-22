# Example Kubernetes manifests

Example Kustomize manifests for running Miru on Kubernetes: one pod with a SQLite database on a persistent volume, in its own `miru` namespace.

These files are documentation. Nothing deploys them from this repository: copy this directory into the repository you deploy from, and adapt it there.

| File | Creates |
| --- | --- |
| `namespace.yaml` | The `miru` namespace, enforcing the `restricted` Pod Security Standard |
| `secret.yaml` | `miru-secrets`: session secret, encryption key and other credentials |
| `configmap.yaml` | `miru-config`: database, Plex and authentication settings |
| `pvc.yaml` | `miru-data`: a 1 GiB volume for the SQLite database |
| `deployment.yaml` | The Miru pod |
| `service.yaml` | The `miru` ClusterIP service on port 8080 |
| `httproute.yaml` | Optional Gateway API route |
| `ingress.yaml` | Optional Ingress |
| `network-policy.yaml` | Optional NetworkPolicy |

## Quick start

1. **Copy.** Copy this directory into your deployment repository, for example as `apps/miru/`. The steps below edit that copy.

2. **Secrets.** In `secret.yaml`, set `AUTH_SESSION_SECRET` and `MIRU_ENCRYPTION_KEY` to two different random values of at least 32 characters:

   ```sh
   openssl rand -base64 48
   ```

   Keep the filled-in file out of version control. To use External Secrets, Sealed Secrets, SOPS or `kubectl create secret` instead, remove `secret.yaml` from `kustomization.yaml` and provide a Secret named `miru-secrets` with the same keys.

3. **Settings.** Review `configmap.yaml`. The defaults use SQLite and local logins, and leave Plex to be connected from the Admin UI.

4. **Image.** Set `images[0].newTag` in `kustomization.yaml` to the release you want from [`ghcr.io/hononeko/miru`](https://github.com/hononeko/miru/pkgs/container/miru). Pin a digest (`<version>@sha256:<digest>`) for repeatable deploys.

5. **Access.** To reach Miru from outside the cluster, uncomment `httproute.yaml` (Gateway API) or `ingress.yaml` in `kustomization.yaml` and set your hostname in that file. To try it without either, port-forward after deploying:

   ```sh
   kubectl -n miru port-forward svc/miru 8080:8080
   ```

6. **Deploy.**

   ```sh
   kubectl apply -k apps/miru/
   kubectl -n miru rollout status deployment/miru
   ```

7. **Set up.** Open Miru, create the first admin account (or set `MIRU_ADMIN_USERNAME` and `MIRU_ADMIN_PASSWORD` in the secret beforehand), then connect your Plex server from **Admin**.

## Connecting Plex

Miru connects to Plex in one of two ways:

- **Plex sign-in (default).** Leave `PLEX_URL` unset and sign in to Plex from the Admin UI. Miru discovers your servers and stores the credentials encrypted with `MIRU_ENCRYPTION_KEY`.
- **Static token.** Set `PLEX_URL` in `configmap.yaml` and `PLEX_TOKEN` in `secret.yaml`. For Plex running in the same cluster, use its service address, e.g. `http://plex.<namespace>.svc.cluster.local:32400`.

## Using PostgreSQL

1. Set `DB_ENGINE: "postgres"` in `configmap.yaml`.
2. Add `DATABASE_URL` (`jdbc:postgresql://<host>:5432/<database>`), `DATABASE_USER` and `DATABASE_PASSWORD` to `secret.yaml`.
3. Optionally drop the volume: with PostgreSQL, Miru keeps nothing on disk. Remove `pvc.yaml` from `kustomization.yaml` and replace the `data` volume in `deployment.yaml` with `emptyDir: {}`. You can then switch the Deployment strategy to `RollingUpdate`.

## Single sign-on

Set `AUTH_MODE: "oidc"` in `configmap.yaml` (or `OIDC_ENABLED: "true"` to offer SSO alongside local logins), fill in the `OIDC_*` settings, and add `OIDC_CLIENT_SECRET` to `secret.yaml` if your provider issues one. Register `https://<your-host>/api/v1/auth/oidc/callback` as the redirect URI with your provider.

## Notes

- **One replica.** Miru holds the live Plex connection and session state in memory, so the Deployment runs a single replica with the `Recreate` strategy, which also lets the new pod attach the `ReadWriteOnce` volume.
- **Live updates.** The dashboard receives live updates over server-sent events at `/api/v1/streams/events`, with a keepalive every 15 seconds. If your ingress or gateway buffers responses or closes idle connections sooner than that, disable buffering for this path or raise the idle timeout.
- **Security.** The pod runs as UID/GID 10001 with a read-only root filesystem, all capabilities dropped, the `RuntimeDefault` seccomp profile and no service account token.
- **Network policy.** `network-policy.yaml` allows inbound traffic to the web port, and outbound DNS, Plex (32400), HTTPS (443, for plex.tv and OIDC providers) and PostgreSQL (5432). Adjust the ports to match your setup, and narrow the inbound rule to your ingress controller or gateway.
