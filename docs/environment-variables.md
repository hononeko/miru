# Environment variables

Miru is configured entirely through environment variables. All of them are optional except where marked **required**; secrets are marked 🔒 and are never logged or returned by the API.

Miru validates every setting on startup. If any are invalid, it logs each problem with the variable's name and exits with status 1, so a bad configuration fails at once instead of at first use.

A variable that is empty or contains only whitespace counts as unset. Apart from the logging and container image variables, each setting can also be given by its key in [`application.conf`](../src/main/resources/application.conf), for example `miru.server.port`; the environment variable takes precedence.

For examples, see [`docker-compose.yml`](docker-compose.yml), the [Kubernetes manifests](k8s/README.md) and [`.env.example`](../.env.example) for local development.

## Server

| Variable | Default | Description |
| --- | --- | --- |
| `MIRU_HOST` | `0.0.0.0` | Address the HTTP server binds to. |
| `MIRU_PORT` | `8080` | Port the HTTP server listens on, from 1 to 65535. Falls back to `PORT` when unset. |
| `MIRU_CORS_ALLOWED_ORIGINS` | *(none)* | Comma-separated origins allowed to call the API from another origin with credentials, for example `http://localhost:5173` for the Vite dev server. An origin given without a scheme allows both `http` and `https`. Leave unset when the web UI is served by Miru itself. |
| `MIRU_TRUST_PROXY_HEADERS` | `false` | Set `true` only behind a single trusted reverse proxy. When enabled, Miru honors the rightmost entry in `X-Forwarded-For` (falling back to `X-Real-IP`) for authentication rate limiting. Leave `false` when Miru is exposed directly so clients cannot spoof forwarding headers. |
| `STATIC_WEB_DIR` | *(none)*; `/opt/miru/static` in the container image | Directory of the built web UI (`web/build`) to serve, with SPA fallback to `index.html`. When unset, Miru serves only the API. |

## Database

| Variable | Default | Description |
| --- | --- | --- |
| `DB_ENGINE` | `sqlite` | `sqlite` or `postgres` (`postgresql` is accepted too). |
| `SQLITE_PATH` | `./data/miru.db`; `/data/miru.db` in the container image | Path of the SQLite database file. Must not be blank when `DB_ENGINE=sqlite`. |
| `DATABASE_URL` | *(none)* | JDBC URL, starting with `jdbc:postgresql://`, for example `jdbc:postgresql://postgres:5432/miru`. **Required** when `DB_ENGINE=postgres`. |
| `DATABASE_USER` | *(none)* | PostgreSQL user. **Required** when `DB_ENGINE=postgres`. |
| `DATABASE_PASSWORD` 🔒 | *(none)* | PostgreSQL password. **Required** when `DB_ENGINE=postgres`. |
| `DATABASE_MAX_POOL_SIZE` | `10` | Maximum number of pooled PostgreSQL connections, at least 1. |

Miru applies its database migrations on startup for both engines.

## Plex

Miru connects to Plex in one of two ways:

- **Plex sign-in (default).** Leave `PLEX_URL` and `PLEX_TOKEN` unset and sign in to Plex from the Admin UI. Miru discovers your servers and saves the credentials encrypted with `MIRU_ENCRYPTION_KEY`.
- **Static token.** Set `PLEX_URL` and `PLEX_TOKEN` together.

| Variable | Default | Description |
| --- | --- | --- |
| `PLEX_URL` | *(none)* | Base URL of the Plex Media Server, for example `http://plex:32400`. Must be an `http://` or `https://` URL. Set it together with `PLEX_TOKEN`. |
| `PLEX_TOKEN` 🔒 | *(none)* | Plex token for `PLEX_URL`. Set it together with `PLEX_URL`. |
| `PLEX_POLL_INTERVAL_SECONDS` | `20` | How often, in seconds, Miru re-fetches active sessions when the notification socket has been quiet. Catches clients that stop without telling Plex. At least 5. |
| `PLEX_WEBSOCKET_ENABLED` | `true` | `true` to receive live updates over the Plex notification socket. `false` runs Miru in polling-only mode. |
| `PLEX_CLIENT_IDENTIFIER` | *(generated)* | Client identifier Miru presents to plex.tv when you sign in to Plex. When unset, Miru reuses the one saved with an already connected server, or generates a new `miru-…` identifier. |
| `PLEX_TV_BASE_URL` | `https://plex.tv` | Base URL of the plex.tv API, used for sign-in, server discovery, users and avatars. Change it only to route plex.tv traffic through a proxy or a test double. |

## Security

| Variable | Default | Description |
| --- | --- | --- |
| `MIRU_ENCRYPTION_KEY` 🔒 | *(none)* | Key that encrypts the Plex credentials saved when you sign in to Plex from the Admin UI. At least 32 characters when set, for example `openssl rand -base64 48`. Plex sign-in can't save credentials without it. Keep it stable: credentials saved with one key can't be read with another. |

## Playback history

| Variable | Default | Description |
| --- | --- | --- |
| `MIN_PLAY_DURATION_SECONDS` | `120` | Plays shorter than this, in seconds, are not recorded in the history. At least 0. |
| `RESUME_MERGE_WINDOW_SECONDS` | `120` | When a user resumes the same item on the same player within this many seconds of stopping, Miru extends the earlier history entry instead of adding a new one. `0` turns merging off. |
| `WATCHED_THRESHOLD_PERCENT` | `85` | Progress, in percent, at which a play counts as watched. From 1 to 100. Also the default for Tautulli imports. |

## Stream Guardrails

Automated stream guardrails default to `disabled`. Setting a rule's mode to `warn` runs it in dry-run mode (recording structured warnings and audit log entries without terminating Plex sessions), while `enforce` automatically terminates matching Plex streams and sends the configured message to the viewer. Rules can also be adjusted at runtime from the Admin Console.

| Variable | Default | Description |
| --- | --- | --- |
| `GUARDRAIL_4K_TRANSCODE_MODE` | `disabled` | `disabled`, `warn` (dry-run), or `enforce` for 4K/UHD video transcode protection. Audio-only transcodes of 4K direct-stream video do not trigger this rule. |
| `GUARDRAIL_4K_TRANSCODE_MESSAGE` | `4K video transcoding is disabled on this server. Please select Direct Play or a 1080p version.` | Message displayed on the viewer's Plex client when terminated by the 4K transcode guardrail (max 500 characters). |
| `GUARDRAIL_PAUSED_TIMEOUT_MODE` | `disabled` | `disabled`, `warn` (dry-run), or `enforce` for streams paused longer than the configured threshold. |
| `GUARDRAIL_PAUSED_TIMEOUT_MINUTES` | `30` | Continuous pause duration threshold in minutes (`1` to `1440`). Resets when playback resumes. |
| `GUARDRAIL_PAUSED_TIMEOUT_MESSAGE` | `Your stream was terminated because it remained paused for too long.` | Message displayed on the viewer's Plex client when terminated for remaining paused too long (max 500 characters). |
| `GUARDRAIL_CONCURRENT_STREAMS_MODE` | `disabled` | `disabled`, `warn` (dry-run), or `enforce` for enforcing a per-user simultaneous stream limit. |
| `GUARDRAIL_CONCURRENT_STREAMS_MAX` | `2` | Maximum concurrent active streams allowed per media user (`1` to `50`). Excess newest streams are targeted first. |
| `GUARDRAIL_CONCURRENT_STREAMS_MESSAGE` | `You have exceeded the maximum number of simultaneous streams allowed for your account.` | Message displayed on the viewer's Plex client when terminated for exceeding the concurrent stream limit (max 500 characters). |

## Authentication

| Variable | Default | Description |
| --- | --- | --- |
| `AUTH_MODE` | `local` | `local` (username and password), `oidc` (single sign-on) or `none` (no login at all; only for local development or trusted networks). |
| `AUTH_SESSION_SECRET` 🔒 | *(none)* | Key that signs session cookies. At least 32 characters, for example `openssl rand -base64 48`. **Required** unless `AUTH_MODE=none`. Changing it signs everyone out. |
| `AUTH_ALLOW_LOCAL_LOGIN` | `true` | With `AUTH_MODE=local`, `false` turns off username and password logins, leaving single sign-on (which then must be enabled with `OIDC_ENABLED=true`). Local logins are always off with `AUTH_MODE=oidc`. |
| `MIRU_ADMIN_USERNAME` | *(none)* | Creates this admin account on startup if no admin exists yet, as an alternative to the first-run setup page. Set it together with `MIRU_ADMIN_PASSWORD`. Ignored when local logins are off. |
| `MIRU_ADMIN_PASSWORD` 🔒 | *(none)* | Password for `MIRU_ADMIN_USERNAME`: at least 12 characters, with an uppercase letter, a lowercase letter and a digit. Used only to create the account; changing it later has no effect. |
| `AUTH_COOKIE_SECURE` | `auto` | Controls the `Secure` attribute on session and OIDC cookies (`COOKIE_SECURE` fallback also accepted). `auto` sets `Secure` when the request arrives over TLS or includes `X-Forwarded-Proto=https` / `Forwarded: proto=https`; `true` (`always`) always sets `Secure`; `false` (`never`) omits `Secure` (only for plain-HTTP local development). |
| `AUTH_RATE_LIMIT_ENABLED` | `true` | Enables fixed-window rate limiting on `/api/v1/auth/login` and `/api/v1/auth/setup` (`429 Too Many Requests` + `Retry-After`). |
| `AUTH_RATE_LIMIT_WINDOW_SECONDS` | `60` | Rate-limit fixed window duration in seconds. |
| `AUTH_RATE_LIMIT_LOGIN_MAX_ATTEMPTS` | `20` | Maximum `/api/v1/auth/login` requests per client IP within `AUTH_RATE_LIMIT_WINDOW_SECONDS`. |
| `AUTH_RATE_LIMIT_ACCOUNT_MAX_FAILURES` | `10` | Maximum failed password attempts per username within `AUTH_RATE_LIMIT_WINDOW_SECONDS`. |
| `AUTH_RATE_LIMIT_SETUP_MAX_ATTEMPTS` | `10` | Maximum `/api/v1/auth/setup` attempts per client IP within `AUTH_RATE_LIMIT_WINDOW_SECONDS`. |

When `AUTH_MODE` is not `none`, at least one login method (local or single sign-on) must be enabled.

## Single sign-on (OIDC)

Single sign-on is on with `AUTH_MODE=oidc`, or with `AUTH_MODE=local` and `OIDC_ENABLED=true` to offer it next to local logins. Register `https://<your-host>/api/v1/auth/oidc/callback` as the redirect URI with your provider.

| Variable | Default | Description |
| --- | --- | --- |
| `OIDC_ENABLED` | `true` with `AUTH_MODE=oidc`, otherwise `false` | Turns on single sign-on. Must not be `false` when `AUTH_MODE=oidc`. |
| `OIDC_ISSUER_URL` | *(none)* | Issuer URL of your provider, for example `https://auth.example.com/application/o/miru/`. **Required** when single sign-on is on. |
| `OIDC_CLIENT_ID` | *(none)* | Client ID registered with your provider. **Required** when single sign-on is on. |
| `OIDC_CLIENT_SECRET` 🔒 | *(none)* | Client secret, if your provider issues one. |
| `OIDC_REDIRECT_URI` | *(none)* | Miru's callback URL, `https://<your-host>/api/v1/auth/oidc/callback`. **Required** when single sign-on is on. |
| `OIDC_DISPLAY_NAME` | `SSO` | Label of the single sign-on button on the login page. |
| `OIDC_SCOPES` | `openid,profile,email,groups` | Scopes to request, separated by commas or spaces. |
| `OIDC_GROUPS_CLAIM` | `groups` | Claim that lists the user's groups. |
| `OIDC_ADMIN_GROUPS` | *(none)* | Comma-separated groups whose members sign in as `ADMIN`. |
| `OIDC_USER_GROUPS` | *(none)* | Comma-separated groups whose members sign in as `USER`. Users in neither these nor the admin groups are refused. At least one group must be set across `OIDC_ADMIN_GROUPS` and `OIDC_USER_GROUPS`. |
| `OIDC_AUTO_LINK_BY_EMAIL` | `true` | Automatically links SSO accounts to the Plex users with the same verified email address, which scopes what a `USER` account sees. Only an email the identity provider marks as verified (`email_verified`, from the ID token or userinfo) is used, and an account without one is unlinked. Sign-in records the email and whether it is verified; links are recomputed when that changes and after every hourly Plex user sync. Once an admin edits an account's links, Miru stops changing them until the admin switches the account back to email linking. Set to `false` to disable automatic email linking. |
| `OIDC_POST_LOGOUT_REDIRECT_URI` | *(none)* | Where your provider sends the browser after logout, for example `https://<your-host>/login`. Must be an `http://` or `https://` URL. |

## Logging

Miru logs JSON lines to standard output.

| Variable | Default | Description |
| --- | --- | --- |
| `LOG_LEVEL` | `INFO` | Log level for Miru's own loggers (`app.hononeko.miru`): `TRACE`, `DEBUG`, `INFO`, `WARN` or `ERROR`. |
| `ROOT_LOG_LEVEL` | `INFO` | Log level for everything else, such as Ktor, HikariCP and Flyway. |

## Container image

| Variable | Default | Description |
| --- | --- | --- |
| `JDK_JAVA_OPTIONS` | `-XX:MaxRAMPercentage=50.0 -XX:MinRAMPercentage=25.0 -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError` | Standard JVM environment variable read directly by the Java launcher (no shell required). Setting it replaces the defaults, so repeat any you want to keep. |

The container image pairs a trimmed `jlink` Java runtime with Google Distroless `base-nossl-debian13:nonroot` (no shell, package manager, coreutils, or OpenSSL). The JRE, application JAR, static web assets, and pre-extracted SQLite native library (`/opt/miru/native/libsqlitejdbc.so`) are owned by `root` and read-only to the runtime user (`10001:10001`), which only owns `/data`. Because `libsqlitejdbc.so` is pre-extracted at build time rather than unpacked into `/tmp` at startup, `/tmp` can be mounted non-executable (`--read-only --tmpfs /tmp:noexec,nosuid,nodev`).
