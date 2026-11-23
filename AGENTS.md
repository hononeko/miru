# Agent & Contributor Guidelines (`AGENTS.md`)

**Miru** (見る) is a lightweight media server telemetry, live stream monitor, playback history tracker, and library analytics service built in Kotlin/Ktor with Arrow-kt, supporting both SQLite and PostgreSQL, and exposing a versioned REST + SSE API for a decoupled Svelte/Astro frontend.

All automated agents and human contributors must follow the rules in this document.

## 1. Quality Gates

Before committing, all of these must pass:

```bash
./gradlew check
./gradlew test
./gradlew shadowJar
```

## 2. Rules

* **Arrow-kt typed errors:** Use `Either<MiruError, T>` and `Raise<MiruError>` (`either { ... }`) across adapters, repositories, and services. Avoid exception-driven domain control flow; map `MiruError` to RFC 7807 Problem Details at the Ktor routing boundary.
* **Kotest for all tests:** Write all unit and integration tests using **Kotest** (`FunSpec` / `kotest-assertions-core` / `kotest-assertions-arrow`). Do not add JUnit Jupiter `@Test` suites.
* **Dual-DB parity:** All persistence changes must ship migrations and passing tests for both **SQLite** and **PostgreSQL**.
* **No secrets in code or logs:** Credentials (`PLEX_TOKEN`, `DATABASE_PASSWORD`, `AUTH_SESSION_SECRET`, `MIRU_ADMIN_PASSWORD`) come from the environment, are redacted in `toString()`, and are never logged or returned in API responses.
* **Keep OpenAPI in sync:** Every new or modified HTTP endpoint updates `src/main/resources/openapi/openapi.yaml`.
* **Pinned dependencies:** Third-party GitHub Actions are pinned by commit SHA with a version comment.

## 3. Commits & Releases

[Conventional Commits](https://www.conventionalcommits.org/), enforced locally by the `commit-msg` hook (`git config core.hooksPath .githooks`). Commit subjects become the release notes, so write them for a reader of the release.

Versions follow the rules shared by all hononeko repositories in [`hononeko/actions`](https://github.com/hononeko/actions):

* A `!` in the header (`feat!:`) or a `BREAKING CHANGE:` footer gives a **major** release.
* `feat:` gives a **minor** release.
* `docs:` and `test:` give no release.
* Every other type gives a **patch** release.

## 4. Repository Structure

```
.
├── .githooks/
│   └── commit-msg                # Conventional Commits check
├── .github/
│   ├── codeql/codeql-config.yml  # CodeQL Actions security config
│   └── workflows/                # PR, Main, Image, Version, CD & CodeQL CI
├── config/detekt/detekt.yml      # Detekt rules
├── docs/
│   ├── screenshots/              # README screenshots
│   ├── k8s/                      # Example Kustomize manifests (documentation only, not deployed)
│   ├── docker-compose.yml        # Example Docker Compose setup (documentation only)
│   └── environment-variables.md  # Reference for every configuration variable
├── gradle/libs.versions.toml     # Dependency version catalog
├── src/
│   ├── main/
│   │   ├── kotlin/app/hononeko/miru/
│   │   │   ├── Application.kt
│   │   │   ├── adapter/          # MediaServerAdapter contract + Plex HTTP client
│   │   │   ├── config/           # Typed AppConfig + Arrow validation
│   │   │   ├── domain/error/     # Sealed MiruError hierarchy
│   │   │   ├── health/           # Readiness checks & probes
│   │   │   ├── http/             # RFC 7807 ProblemDetails, Either mapper, routes
│   │   │   └── persistence/      # Exposed DSL, Flyway migrations, HikariCP
│   │   └── resources/
│   │       ├── application.conf
│   │       ├── db/migration/     # sqlite & postgres Flyway SQL scripts
│   │       ├── logback.xml
│   │       └── openapi/openapi.yaml
│   └── test/kotlin/app/hononeko/miru/
├── AGENTS.md
├── Dockerfile
└── README.md
```
