# Miru vs. Tautulli Performance Benchmarks

Reproducible side-by-side comparison of **Miru** (Kotlin 2.4 / Ktor 3 / JDK 25 `jlink` on Distroless, tested with both **SQLite** and **PostgreSQL 17**) against **Tautulli** (`v2.19.1`, Python / CherryPy, SQLite) on a shared **10,000-play** dataset under identical container CPU and memory limits (`2 vCPU`, `512 MiB` RAM).

> [!NOTE]
> All benchmarks were run on 2026-11-28 with the [`benchmarks/run.mjs`](../benchmarks/run.mjs) Docker + Playwright harness against `main` at `d3a940a` (local `amd64` OCI digest `sha256:ad0dcaf03`), which adds the Release 1.4 performance work to `1.3.0`. Both apps were measured in the same run. Precompressed `.br`/`.gz` static assets, dynamic API `gzip`/`deflate` compression, and all security hardening controls were on, as by default. Results cover both where Miru wins clearly and the trade-offs: JVM memory vs Python, narrow single-purpose Tautulli endpoints, and SPA bootstrap vs server-rendered HTML.

> [!IMPORTANT]
> The query benchmarks repeat the same request 200 times after 20 warm-up requests. Since Release 1.4, Miru keeps stats results (overview, user, heatmap, and client stats) in an in-memory cache that every write affecting them clears and that expires after 5 minutes. The measured stats requests are therefore served from memory, and their time is mostly HTTP handling, authentication, JSON encoding, and gzip. The first request after a play is recorded or history is imported is computed from the database again. History pagination and artwork are not part of this cache.

---

## Executive Summary

- **18× faster live UI updates with 6.9× less network traffic:** Miru's Plex WebSocket + Server-Sent Events (SSE) push pipeline updates the browser DOM in **526–532 ms** median (including Miru's intentional `500 ms` notification debounce window) over **1 long-lived SSE connection** carrying **9.3 KiB** in 30 seconds, compared to Tautulli's default 10-second polling interval (**9.5 s** DOM update, **3 requests / 64.7 KiB** over 30 s).
- **11×–130× lower p50 latency under concurrent API load:** Under 10 concurrent clients on 10,000 plays with API gzip active, **Miru + PostgreSQL** serves paginated history in **3.74 ms p50 / 12.47 ms p99** (`2,353.7 req/s`, **13.6×** Tautulli's `173.1 req/s` and **10.9×** lower p50 than Tautulli's `40.61 ms`), `30d` overview stats in **2.69 ms p50** (`3,389.7 req/s`, **126×** Tautulli's `26.8 req/s` and **130×** lower p50 than `350.23 ms`), and all-time stats in **24.4 ms p50** (`404.9 req/s`, **43.5×** Tautulli's `9.3 req/s` / `1,031.99 ms` p50). Miru + SQLite is within a few percent of PostgreSQL on every one of these.
- **33% smaller cold-load browser transfer & 6.2× higher concurrent artwork throughput:** Build-time Brotli (`.br`) and gzip (`.gz`) static assets keep Miru's uncached initial page transfer at **337.0 KiB**, **33.2% smaller than Tautulli (`504.2 KiB`)**. Miru's in-memory `BoundedArtworkCache` serves repeated poster thumbnails at **0.34 ms p50 / 2,879.4 req/s** (`c=1`) and **0.81 ms p50 / 9,632 req/s** (`c=10`) vs Tautulli's disk-backed proxy at **0.58 ms / 1,117.2 req/s** (`c=1`) and **6.39 ms / 1,549.9 req/s** (`c=10`).
- **37% smaller unpacked image, about the same size compressed:** Using digest-pinned Google Distroless (`base-nossl-debian13:nonroot`), a trimmed 14-module JDK 25 `jlink` runtime, and precompressed web assets, Miru's image is **73.5 MiB** compressed / **187.4 MiB** unpacked (`linux/amd64`), against Tautulli's **72.8 MiB / 296.1 MiB**.
- **20% faster cold startup & fast streaming migration:** Miru reaches `/readyz` healthy in **1.02 s** (SQLite) / **1.09 s** (PostgreSQL) vs **1.27 s** for Tautulli `/status`, and streams a 10,000-play Tautulli database via API in bounded batches in **808 ms** (SQLite) / **1.96 s** (PostgreSQL).
- **Trade-offs:** Tautulli keeps roughly **2× lower RAM** (**82–83 MiB** working set vs Miru's **171–198 MiB** JVM working set under `512 MiB` limits), slightly faster narrow user endpoints (`get_user_watch_time_stats` in **6.91 ms** at `c=10` vs Miru's combined `/api/v1/users/{id}/stats` analytics bundle in **7.40 ms** on PostgreSQL), a faster artwork cache miss (**1.10 ms** vs **2.25 ms** p50), and an earlier uncached first paint (**40 ms** FCP / **140 ms** LCP on server-rendered HTML vs **224 ms** for Miru's client SPA bootstrap).

---

## Test Environment & Methodology

| Parameter | Value |
| :--- | :--- |
| **Host Hardware** | Intel Core i9-14900K (32 CPUs), 62.5 GiB RAM, Linux `7.3.6-1-cachyos-custom` |
| **Docker Engine** | `29.10.1` (single isolated bridge network `miru-bench-net`) |
| **Container Limits** | `--cpus=2 --memory=512m` applied identically to Miru and Tautulli app containers (`postgres:17-alpine` alongside for PG tests) |
| **Miru Version** | `main` `d3a940a` (`miru:bench`, local `amd64` OCI `sha256:ad0dcaf03`, built from [`Dockerfile`](../Dockerfile): Distroless `base-nossl-debian13:nonroot`, 14-module Temurin JDK 25 `jlink`, `-XX:+UseCompactObjectHeaders`, G1GC periodic uncommit, `.br`/`.gz` precompressed web assets, Ktor `Compression` for JSON/text API) |
| **Tautulli Version** | `ghcr.io/tautulli/tautulli:latest` (`v2.19.1`, digest `sha256:96eaa1659...` amd64) |
| **Mock Plex Server** | Local Bun/Node content-negotiating JSON + Plex XML server with WebSocket `/:/websockets/notifications` and deterministic `300×450` PNG posters |
| **Dataset** | `10,000` plays across `365` days (`10` users, `300` movies/episodes, mixed Direct Play / Direct Stream / HW & SW Transcode) seeded into Tautulli SQLite and imported into Miru SQLite & PostgreSQL |
| **Auth & Security State** | Both Miru (`AUTH_MODE=local`, global CSP/security headers, rate limiting, library-section RBAC) and Tautulli (`http_password` + JWT session) ran with authentication enabled; API benchmarks used `X-Api-Key` (Miru) and `apikey=` (Tautulli); Playwright benchmarks used authenticated session cookies |

---

## 1. Resource Footprint & Container Image Size

Sampled at 1-second intervals for **30 seconds** starting 5 seconds after startup + 10k-play import (idle window, then immediately followed by **3 concurrent active streams**: 1 Direct Play, 1 Direct Stream, 1 HW Transcode).

| Workload / Metric | Miru (SQLite) | Miru (PostgreSQL 17) | Tautulli (SQLite) |
| :--- | :---: | :---: | :---: |
| **Image size — compressed (`linux/amd64`)** | `73.5 MiB` *(local OCI export)* | `73.5 MiB` *(+ PG 17)* | **`72.8 MiB`** |
| **Image size — uncompressed (`linux/amd64`)** | **`187.4 MiB`** | **`187.4 MiB`** *(+ PG 17)* | `296.1 MiB` |
| **Post-import 30s idle working set (median)** | `198.4 MiB` *(min `169.9 MiB`)* | `184.7 MiB` *(+ `54.0 MiB` PG)* | **`81.9 MiB`** |
| **Post-import 30s idle anon RSS (cgroup `anon`)** | `182.8 MiB` *(min `154.9 MiB`)* | `177.4 MiB` *(+ `19.4 MiB` PG)* | **`79.0 MiB`** |
| **Idle CPU % (median / mean)** | `0.11%` / `0.35%` | `0.19%` / `0.66%` | **`0.03%`** / **`0.19%`** |
| **Active 3-stream working set (median)** | `171.4 MiB` | `175.0 MiB` *(+ `54.8 MiB` PG)* | **`83.0 MiB`** |
| **Active 3-stream anon RSS (median)** | `156.1 MiB` | `173.0 MiB` *(+ `19.5 MiB` PG)* | **`80.0 MiB`** |
| **Active 3-stream CPU % (median / mean)** | `0.10%` / `0.45%` | `0.11%` / **`0.27%`** | **`0.03%`** / `0.30%` |

- **Takeaway:** Miru's image grew from `68.5 / 176.8 MiB` in the previous (pre-1.0) run to **73.5 MiB compressed / 187.4 MiB unpacked** (`amd64`) as features were added. It is now about the same compressed size as Tautulli while staying **36.7% smaller unpacked**. With G1 periodic heap uncommit (`-XX:G1PeriodicGCInterval=30000 -XX:G1PeriodicGCSystemLoadThreshold=0.0`), JDK 25 Compact Object Headers (`-XX:+UseCompactObjectHeaders`), and `MALLOC_ARENA_MAX=2`, Miru stays around `171–198 MiB` after import and under load, including the Release 1.4 in-memory caches. Python/CherryPy keeps roughly **2× lower RAM** (`82–83 MiB`).

---

## 2. Startup, Readiness & Tautulli Migration Speed

Measured across **5 cold `docker run` invocations** per configuration up to HTTP `200` on `/readyz` (Miru) or `/status` (Tautulli):

| Runtime / Configuration | Median | Min | Max |
| :--- | :---: | :---: | :---: |
| **Miru SQLite (10k-play DB)** | **`1,017.5 ms`** | `983.9 ms` | `1,124.4 ms` |
| **Miru SQLite (empty DB + Flyway migrations)** | **`1,136.1 ms`** | `1,079.6 ms` | `1,179.6 ms` |
| **Miru PostgreSQL 17 (10k-play DB, warm PG)** | **`1,092.6 ms`** | `1,031.4 ms` | `1,094.8 ms` |
| **Tautulli SQLite (10k-play DB)** | `1,265.9 ms` | `1,242.7 ms` | `1,277.9 ms` |

- **Tautulli 10,000-play migration import (`POST /api/v1/admin/imports/tautulli`)**, one run each:
  - **SQLite:** **`807.8 ms`** (`10,000` plays + `10` users, `0` malformed rows, streamed in bounded `O(batchSize)` batches)
  - **PostgreSQL 17:** **`1,963.0 ms`** (`10,000` plays + `10` users, `0` malformed rows)

---

## 3. Real-Time Responsiveness

Measured over **5 trials each** for `start`, `pause`, and `stop` triggered by Mock Plex `PlaySessionStateNotification` WebSocket frames:

| Stage & Event | Miru (WS → SSE / DOM) | Tautulli (WS → SQLite / 10 s UI Poll) | Notes |
| :--- | :---: | :---: | :--- |
| **Backend WS reaction — `start` (median)** | `519.3 ms` *(SSE event)* | **`18.1 ms`** *(SQLite row)* | Miru applies an intentional `500 ms` debounce window (`SessionReconciler`) |
| **Backend WS reaction — `pause` (median)** | `517.7 ms` *(SSE event)* | **`17.5 ms`** *(SQLite row)* | ~17–19 ms Miru reconciliation + SSE overhead on top of the 500 ms debounce |
| **Backend WS reaction — `stop` (median)** | `516.6 ms` *(SSE event)* | **`18.3 ms`** *(SQLite row deleted)* | Both subscribe directly to Plex `/:/websockets/notifications` |
| **Browser UI DOM — `start` (median)** | **`531.8 ms`** | `9,496.6 ms` | **17.9× faster** in browser |
| **Browser UI DOM — `pause` (median)** | **`526.1 ms`** | `9,497.0 ms` | **18.1× faster** in browser |
| **Browser UI DOM — `stop` (median)** | **`526.4 ms`** | `9,493.9 ms` | **18.0× faster** in browser |

- **Architectural difference:** While Tautulli processes incoming Plex WebSocket frames quickly in Python (~`18 ms`), Tautulli's web dashboard does not maintain a server push channel and instead polls `/get_activity` on a default `10 s` timer (`HOME_REFRESH_INTERVAL = 10`). Miru debounces bursts for `500 ms` (`SessionReconciler.DEFAULT_DEBOUNCE_WINDOW`) and pushes updates immediately to connected browsers over unbuffered **SSE** (`Content-Encoding` excluded cleanly), yielding **~526–532 ms** end-to-end UI updates. Since Release 1.4, each session event is built and encoded once and shared by every connected browser.

---

## 4. Query & API Latency (10,000 Plays)

Each scenario ran **20 warm-up requests** followed by **200 measured requests** (or **20 unique keys** for artwork cache misses) at concurrency `1` and `10`. Benchmark HTTP clients send default `Accept-Encoding` headers, so JSON API responses above `1024 B` are gzip-compressed by Ktor `Compression` (whereas `image/*` artwork and SSE streams skip dynamic compression). Because the requests repeat, Miru's stats rows reflect its in-memory stats cache (see the note at the top).

### Single-Client (`c = 1`)

| Workload | Miru SQLite (p50 / p95 / p99) | Miru PostgreSQL (p50 / p95 / p99) | Tautulli SQLite (p50 / p95 / p99) | Throughput (req/s: SQLite / PG / Tautulli) |
| :--- | :---: | :---: | :---: | :---: |
| **History pagination** *(50 rows, p.1)* | **`2.25`** / `3.57` / `4.98` ms | `2.42` / `3.98` / `5.34` ms | `2.81` / `5.30` / `6.78` ms | **`423.3`** / `391.1` / `308.6` |
| **30d overview stats** | **`1.22`** / `1.56` / `1.81` ms | **`1.22`** / `1.95` / `2.41` ms | `27.51` / `28.14` / `43.68` ms | **`805.6`** / `762.8` / `35.8` |
| **All-time overview stats** *(10k plays)* | `6.30` / `7.74` / `9.41` ms | **`6.21`** / `8.41` / `9.63` ms | `76.00` / `91.94` / `94.69` ms | **`154.2`** / `152.4` / `12.8` |
| **User stats** *(Miru combined vs Tautulli split)* | `3.33` / `4.53` / `5.62` ms | `2.96` / `4.23` / `5.36` ms | **`2.28`** ms *(watch)* / **`2.46`** ms *(player)* | `296.9` / `319.2` / **`351.4`** & `339.8` |
| **Artwork proxy — cache miss** *(20 items)* | `2.25` / `6.52` / `15.89` ms | — | **`1.10`** / `1.29` / `2.28` ms | `297.8` / — / **`843.1`** |
| **Artwork proxy — cache hit** | **`0.34`** / `0.49` / `0.56` ms | — | `0.58` / `2.98` / `3.15` ms | **`2,879.4`** / — / `1,117.2` |

### Concurrent Load (`c = 10`)

| Workload | Miru SQLite (p50 / p95 / p99) | Miru PostgreSQL (p50 / p95 / p99) | Tautulli SQLite (p50 / p95 / p99) | Throughput (req/s: SQLite / PG / Tautulli) |
| :--- | :---: | :---: | :---: | :---: |
| **History pagination** *(50 rows, p.1)* | `4.43` / `7.67` / `11.24` ms | **`3.74`** / `6.99` / `12.47` ms | `40.61` / `53.06` / `1,092.29` ms | `2,056.6` / **`2,353.7`** / `173.1` |
| **30d overview stats** | `2.79` / `5.33` / `6.23` ms | **`2.69`** / `4.74` / `5.67` ms | `350.23` / `570.78` / `600.98` ms | `3,252.9` / **`3,389.7`** / `26.8` |
| **All-time overview stats** *(10k plays)* | **`23.16`** / `26.91` / `28.39` ms | `24.40` / `26.35` / `27.53` ms | `1,031.99` / `1,492.19` / `1,863.74` ms | **`419.4`** / `404.9` / `9.3` |
| **User stats** *(Miru combined vs Tautulli split)* | `8.48` / `10.10` / `10.50` ms | `7.40` / `9.75` / `11.12` ms | **`6.91`** ms *(watch)* / **`6.85`** ms *(player)* | `1,162.5` / `1,264.9` / **`1,429.1`** & `1,418.7` |
| **Artwork proxy — cache hit** | **`0.81`** / `2.06` / `2.94` ms | — | `6.39` / `9.21` / `10.95` ms | **`9,632.0`** / — / `1,549.9` |

- **Notes on user stats, caching & API gzip:** `/api/v1/users/{id}/stats?range=all` returns a single comprehensive analytics payload (`playsPerDay`, `topMovies`, `topShows`, `mostRewatched`, `decisionBreakdown`, `topPlatforms`, `libraryBreakdown`, completion rate, and binge streak). Tautulli's `get_user_watch_time_stats` and `get_user_player_stats` return narrower single-aspect summaries and stay slightly faster individually (`6.91 ms` and `6.85 ms` at `c=10` vs Miru's `7.40 ms` on PostgreSQL). With Miru's stats cache serving the repeated requests, the all-time overview's larger payload makes JSON encoding and gzip, not the database, its main cost (`6.2 ms` at `c=1`); clients requesting `Accept-Encoding: identity` bypass compression entirely.

### Before and After the Release 1.4 Performance Work

The same harness, run on the same day against the commit before the performance work (`d60a10f`, `1.3.0` plus account profiles) and against `d3a940a`. Miru p50 latency in ms, before → after:

| Workload | SQLite `c = 1` | SQLite `c = 10` | PostgreSQL `c = 1` | PostgreSQL `c = 10` |
| :--- | :---: | :---: | :---: | :---: |
| **History pagination** *(50 rows, p.1)* | `3.51 → 2.25` | `8.90 → 4.43` | `8.22 → 2.42` | `10.01 → 3.74` |
| **30d overview stats** | `10.29 → 1.22` | `30.66 → 2.79` | `10.16 → 1.22` | `14.92 → 2.69` |
| **All-time overview stats** | `36.59 → 6.30` | `132.05 → 23.16` | `30.00 → 6.21` | `74.78 → 24.40` |
| **User stats** | `15.90 → 3.33` | `49.70 → 8.48` | `11.78 → 2.96` | `19.79 → 7.40` |

- **What changed:** History got faster from new indexes on stop time and on media items (not cached). The stats rows mostly show the new stats cache. Tautulli, measured in both runs, stayed within run-to-run noise (for example `37.18` vs `40.61 ms` history p50 at `c=10`). These numbers are not comparable with the earlier pre-1.0 version of this page, which came from an older version of the harness.

---

## 5. Frontend Load & Live Update Overhead

Measured in headless Playwright Chromium (`1440×900`, HTTP cache disabled, 5 cold loads per app):

| Metric | Miru (SvelteKit 2 / Svelte 5 SPA) | Tautulli (Mako + Bootstrap / jQuery) |
| :--- | :---: | :---: |
| **Static bundle (on disk, logical assets)** | `225.9 KiB` JS + `12.0 KiB` CSS gzipped (`285.3 KiB` across `63` files; `207.8 KiB` `.br` + `239.6 KiB` `.gz` precompressed siblings) | *Server-rendered HTML/static dir* |
| **Cold load requests (median)** | `55` | **`52`** |
| **Wire transfer size (median)** | **`337.0 KiB`** (`345,104 B` — precompressed Brotli `.br` served directly) | `504.2 KiB` (`516,346 B` — gzip enabled) |
| **DOMContentLoaded (median)** | `152.2 ms` | **`62.6 ms`** |
| **First Contentful Paint — FCP (median)** | `224 ms` | **`40 ms`** |
| **Largest Contentful Paint — LCP (median)** | `224 ms` | **`140 ms`** |
| **30s live overhead (3 active streams)** | **`1` SSE connection / `9.3 KiB`** (`9,542 B` SSE frames) | `3` HTTP polling requests / `64.7 KiB` (`66,270 B`) |

- **Takeaway:** Serving build-time precompressed `.br` and `.gz` siblings directly in `StaticWebRoutes` (with zero runtime compression CPU) keeps Miru's empty-cache initial wire transfer at **337.0 KiB**, **33.2% lighter over the wire than Tautulli (`504.2 KiB`)**. Tautulli's server-rendered HTML still delivers an earlier first paint (`40 ms` FCP / `140 ms` LCP vs `224 ms` SPA bootstrap), after which Miru's persistent SSE stream replaces repeated polling and cuts 30-second live monitoring traffic by **6.9×**.
