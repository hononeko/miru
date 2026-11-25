# Compatibility

From 1.0.0 on, Miru follows [semantic versioning](https://semver.org/). Anything listed as covered is not renamed, removed or given another meaning before the next major version.

## Covered

- **REST API:** the endpoints under `/api/v1`, `/healthz` and `/readyz` as described by [`openapi.yaml`](../src/main/resources/openapi/openapi.yaml), which Miru serves at `/api/v1/openapi.yaml`: paths, parameters, fields and enum values.
- **API authentication:** the `X-Api-Key` and `Authorization: Bearer` headers.
- **Errors:** the Problem Details format and its `type` URIs under `https://miru.hononeko.app/errors/`.
- **Live updates:** the events on `/api/v1/streams/events` (`snapshot`, `session_started`, `session_updated` and `session_stopped`) and their data.
- **Webhooks:** the events, headers, signature and payloads in [webhooks.md](webhooks.md).
- **Configuration:** the [environment variables](environment-variables.md) and the keys in [`application.conf`](../src/main/resources/application.conf).

A newer version migrates the database of any older one. Going back to an older version is not supported.

## Not breaking

Any release can add to what is covered: endpoints, optional parameters and request fields, response fields, enum values, live update and webhook events, error types, and settings.

Clients therefore need to:

- ignore fields and events they do not know,
- cope with enum values and error types they do not know,
- leave fields out of a request rather than send ones they do not know the value of.

## Not covered

- The web app, and `/api` paths that `openapi.yaml` does not list
- The database schema
- Log messages, and the `title` and `detail` texts of errors
- The session and sign-in cookies
- The example deployments in [`docker-compose.yml`](docker-compose.yml) and [`k8s`](k8s/README.md)

## Conventions

- **Missing values:** a field without a value is left out of the response rather than sent as `null`.
- **Times and durations:** the unit is in the name. `…AtMs` is milliseconds since the epoch, `…Ms` milliseconds and `…Sec` seconds. Dates and hours of statistics are in UTC.
- **Watch time:** `watchedDurationSec` belongs to one play, and `watchDurationSec` is a total.
- **Library sections:** `librarySectionKey` on media and plays, and `sectionKey` on responses about libraries, are the same key. Treat the `id` of a section as opaque.
- **Lists:** history pages with `page` and `pageSize`. The guardrail audit and the webhook deliveries page with `limit` and `offset`.
- **Enum values:** roles, authentication sources, sync modes, guardrail rules and modes, and webhook delivery statuses are in upper case (`ADMIN`, `TRANSCODE_4K`). Media types, stream decisions, sort fields and preferences are in lower case (`direct_play`), and webhook events have dots (`stream.terminated`). Guardrail modes are in lower case in the configuration.
