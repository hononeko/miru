# Miru vs. Tautulli Performance Benchmarks

Reproducible side-by-side comparison of **Miru** (Kotlin 2.4 / Ktor 3 / JDK 25 `jlink` on Distroless, tested with both **SQLite** and **PostgreSQL 17**) against **Tautulli** (`v2.19.1`, Python 3.14 / CherryPy, SQLite) on a shared **10,000-play** dataset under identical container CPU and memory limits (`2 vCPU`, `512 MiB` RAM).

> [!NOTE]
> All benchmarks were executed locally using the [`benchmarks/run.mjs`](../benchmarks/run.mjs) Docker + Playwright harness against the `feat/pre-release-1.0.0` pre-1.0.0 build (branched from `main` `053bf6a`, local `amd64` OCI digest `sha256:c4ba00ecd`) with precompressed `.br`/`.gz` static asset negotiation, dynamic API `gzip`/`deflate` compression, and all security hardening controls enabled by default. Results cover both areas where Miru wins decisively and candid trade-offs such as JVM memory vs Python, per-response API gzip CPU overhead under concurrency, and SPA hydration timing vs server-rendered HTML.

---

## Executive Summary

- **18× faster live UI updates with 7.4× less network traffic:** Miru's Plex WebSocket + Server-Sent Events (SSE) push pipeline updates the browser DOM in **526–530 ms** median (including Miru's intentional `500 ms` notification debounce window) with **0 polling requests** and **8.7 KiB** transferred over 30 seconds, compared to Tautulli's default 10-second polling interval (**9.5 s** DOM update, **3 requests / 64.7 KiB** over 30 s).
- **17×–43× lower p50 latency under concurrent API load:** Under 10 concurrent clients on 10,000 plays with API gzip active, **Miru + PostgreSQL** serves paginated history in **11.51 ms p50 / 30.27 ms p99** (`757.3 req/s`, **18.3×** Tautulli's `41.4 req/s` and **21.5×** lower p50 than Tautulli's `247.92 ms` / `395.03 ms p99`), single-pass streamed `30d` overview stats in **19.70 ms p50** (`484.0 req/s`, **41.7×** Tautulli's `11.6 req/s` and **43.3×** lower p50), and all-time stats in **101.22 ms p50** (`98.6 req/s`, **17.0×** Tautulli's `5.8 req/s` / `1,712.86 ms` p50).
- **34% smaller cold-load browser transfer & 3.6× higher concurrent artwork throughput:** Serving build-time Brotli (`.br`) and gzip (`.gz`) static assets cuts Miru's uncached initial page transfer by **56.9%** (`775.7 → 334.5 KiB`), now **33.7% smaller than Tautulli (`504.2 KiB`)**. Miru's in-memory `BoundedArtworkCache` serves repeated poster thumbnails at **0.87 ms p50 / 1,100.9 req/s** (`c=1`) and **2.87 ms p50 / 3,196.9 req/s** (`c=10`) vs Tautulli's disk-backed proxy at **1.25 ms / 568.1 req/s** (`c=1`) and **10.82 ms / 900.1 req/s** (`c=10`).
- **Smaller container image than Tautulli (both compressed & unpacked):** Using digest-pinned Google Distroless (`base-nossl-debian13:nonroot`), a trimmed 14-module JDK 25 `jlink` runtime, a zero-dependency JSON Logback encoder, and precompressed `.br`/`.gz` web assets (`+0.5 MiB` compressed), Miru's image is **68.5 MiB** compressed / **176.8 MiB** uncompressed (`linux/amd64`) — **40% smaller unpacked** than Tautulli's **72.8 MiB / 296.1 MiB**.
- **23% faster cold startup & streaming migration:** Miru reaches `/readyz` healthy in **1.15 s** (SQLite) / **1.22 s** (PostgreSQL) vs **1.49 s** for Tautulli `/status`, and streams a 10,000-play Tautulli database via API in bounded batches in **773 ms** (SQLite) / **1.92 s** (PostgreSQL).
- **Trade-offs:** Tautulli maintains roughly **2× lower active RAM** (**82.1 MiB** working set vs Miru's **164–185 MiB** JVM working set under `512 MiB` limits), faster narrow single-stat user endpoints (`get_user_watch_time_stats` in **9.56 ms** at `c=10` vs Miru's combined `/api/v1/users/{id}/stats` analytics bundle in **31.97 ms** on PostgreSQL), and earlier uncached first paint (**56 ms** FCP / **156 ms** LCP on server-rendered HTML vs **248 ms** LCP for Miru's client SPA bootstrap). Enabling dynamic Ktor `gzip`/`deflate` on JSON API payloads above `1024 B` also trades minor compression CPU (~1–8 ms at `c=10` inside `2 vCPU`) for lower JSON response bandwidth.

---

## Test Environment & Methodology

| Parameter | Value |
| :--- | :--- |
| **Host Hardware** | Intel Core i9-14900K (32 CPUs), 62.5 GiB RAM, Linux `7.3.4-1-cachyos-custom` |
| **Docker Engine** | `29.10.0` (single isolated bridge network `miru-bench-net`) |
| **Container Limits** | `--cpus=2 --memory=512m` applied identically to Miru and Tautulli app containers (`postgres:17-alpine` alongside for PG tests) |
| **Miru Version** | `feat/pre-release-1.0.0` off `main` `053bf6a` (`miru:bench`, local `amd64` OCI `sha256:c4ba00ecd`, built from [`Dockerfile`](../Dockerfile): Distroless `base-nossl-debian13:nonroot`, 14-module Temurin JDK 25 `jlink`, `-XX:+UseCompactObjectHeaders`, G1GC periodic uncommit, `.br`/`.gz` precompressed web assets, Ktor `Compression` for JSON/text API) |
| **Tautulli Version** | `ghcr.io/tautulli/tautulli:latest` (`v2.19.1`, Python `3.14.10`, digest `sha256:e2f94101...` amd64) |
| **Mock Plex Server** | Local Bun/Node content-negotiating JSON + Plex XML server with WebSocket `/:/websockets/notifications` and deterministic `300×450` PNG posters |
| **Dataset** | `10,000` plays across `365` days (`10` users, `300` movies/episodes, mixed Direct Play / Direct Stream / HW & SW Transcode) seeded into Tautulli SQLite and imported into Miru SQLite & PostgreSQL |
| **Auth & Security State** | Both Miru (`AUTH_MODE=local`, global CSP/security headers, rate limiting, library-section RBAC) and Tautulli (`http_password` + JWT session) ran with authentication enabled; API benchmarks used `X-Api-Key` (Miru) and `apikey=` (Tautulli); Playwright benchmarks used authenticated session cookies |

---

## 1. Resource Footprint & Container Image Size

Sampled at 1-second intervals for **30 seconds** starting 5 seconds after startup + 10k-play import (idle window, then immediately followed by **3 concurrent active streams**: 1 Direct Play, 1 Direct Stream, 1 HW Transcode).

| Workload / Metric | Miru (SQLite) | Miru (PostgreSQL 17) | Tautulli (SQLite) |
| :--- | :---: | :---: | :---: |
| **Image size — compressed (`linux/amd64`)** | **`68.5 MiB`** *(local OCI export)* | **`68.5 MiB`** *(+ PG 17)* | `72.8 MiB` |
| **Image size — uncompressed (`linux/amd64`)** | **`176.8 MiB`** | **`176.8 MiB`** *(+ PG 17)* | `296.1 MiB` |
| **Post-import 30s idle working set (median)** | `185.0 MiB` *(min `168.3 MiB`)* | `168.3 MiB` *(+ `52.4 MiB` PG)* | **`82.1 MiB`** |
| **Post-import 30s idle anon RSS (cgroup `anon`)** | `170.7 MiB` *(min `154.7 MiB`)* | `165.2 MiB` *(+ `19.2 MiB` PG)* | **`79.0 MiB`** |
| **Idle CPU % (median / mean)** | `0.17%` / `0.31%` | `0.18%` / `0.81%` | **`0.03%`** / `0.15%` |
| **Active 3-stream working set (median)** | `169.3 MiB` | `163.7 MiB` *(+ `52.9 MiB` PG)* | **`83.2 MiB`** |
| **Active 3-stream anon RSS (median)** | `155.4 MiB` | `161.5 MiB` *(+ `19.3 MiB` PG)* | **`80.0 MiB`** |
| **Active 3-stream CPU % (median / mean)** | `0.17%` / `1.01%` | `0.18%` / `0.76%` | **`0.03%`** / **`0.21%`** |

- **Takeaway:** Shipping `.br`/`.gz` precompressed static siblings and `ktor-server-compression` adds just **+0.5 MiB compressed / +1.3 MiB unpacked** over the prior `68.0 / 175.5 MiB` image, leaving Miru **68.5 MiB compressed / 176.8 MiB unpacked** (`amd64`) — **40.3% smaller unpacked** than Tautulli. Combined with G1 periodic heap uncommit (`-XX:G1PeriodicGCInterval=30000 -XX:G1PeriodicGCSystemLoadThreshold=0.0`), JDK 25 Compact Object Headers (`-XX:+UseCompactObjectHeaders`), and `MALLOC_ARENA_MAX=2`, Miru stays around `164–185 MiB` under load, while Python/CherryPy retains roughly **2× lower active RAM** (`82–83 MiB`).

---

## 2. Startup, Readiness & Tautulli Migration Speed

Measured across **5 cold `docker run` invocations** per configuration up to HTTP `200` on `/readyz` (Miru) or `/status` (Tautulli):

| Runtime / Configuration | Median | Min | Max |
| :--- | :---: | :---: | :---: |
| **Miru SQLite (10k-play DB)** | **`1,150.6 ms`** | `1,069.5 ms` | `1,180.6 ms` |
| **Miru SQLite (empty DB + Flyway migrations)** | **`1,232.1 ms`** | `1,197.4 ms` | `1,278.1 ms` |
| **Miru PostgreSQL 17 (10k-play DB, warm PG)** | **`1,216.3 ms`** | `1,151.2 ms` | `1,284.7 ms` |
| **Tautulli SQLite (10k-play DB)** | `1,491.3 ms` | `1,358.3 ms` | `1,531.7 ms` |

- **Tautulli 10,000-play migration import (`POST /api/v1/admin/imports/tautulli`)**:
  - **SQLite:** **`773.0 ms`** (`10,000` plays + `10` users, `0` malformed rows, streamed in bounded `O(batchSize)` batches)
  - **PostgreSQL 17:** **`1,918.5 ms`** (`10,000` plays + `10` users, `0` malformed rows)

---

## 3. Real-Time Responsiveness

Measured over **5 trials each** for `start`, `pause`, and `stop` triggered by Mock Plex `PlaySessionStateNotification` WebSocket frames:

| Stage & Event | Miru (WS → SSE / DOM) | Tautulli (WS → SQLite / 10 s UI Poll) | Notes |
| :--- | :---: | :---: | :--- |
| **Backend WS reaction — `start` (median)** | `514.4 ms` *(SSE event)* | **`21.0 ms`** *(SQLite row)* | Miru applies an intentional `500 ms` debounce window (`SessionReconciler`) |
| **Backend WS reaction — `pause` (median)** | `507.9 ms` *(SSE event)* | **`18.7 ms`** *(SQLite row)* | ~8–14 ms Miru reconciliation + unbuffered SSE overhead on top of 500 ms debounce |
| **Backend WS reaction — `stop` (median)** | `509.8 ms` *(SSE event)* | **`18.0 ms`** *(SQLite row deleted)* | Both subscribe directly to Plex `/:/websockets/notifications` |
| **Browser UI DOM — `start` (median)** | **`529.6 ms`** | `9,521.4 ms` | **18.0× faster** in browser |
| **Browser UI DOM — `pause` (median)** | **`525.8 ms`** | `9,476.3 ms` | **18.0× faster** in browser |
| **Browser UI DOM — `stop` (median)** | **`525.9 ms`** | `9,498.2 ms` | **18.1× faster** in browser |

- **Architectural difference:** While Tautulli processes incoming Plex WebSocket frames quickly in Python (~`18–21 ms`), Tautulli's web dashboard does not maintain a server push channel and instead polls `/get_activity` on a default `10 s` timer (`HOME_REFRESH_INTERVAL = 10`). Miru debounces bursts for `500 ms` (`SessionReconciler.DEFAULT_DEBOUNCE_WINDOW`) and pushes updates immediately to connected browsers over unbuffered **SSE** (`Content-Encoding` excluded cleanly), yielding **~526–530 ms** end-to-end UI updates.

---

## 4. Query & API Latency (10,000 Plays)

Each scenario ran **20 warm-up requests** followed by **200 measured requests** (or **20 unique keys** for artwork cache misses) at concurrency `1` and `10`. Benchmark HTTP clients send default `Accept-Encoding` headers, so JSON API responses above `1024 B` are gzip-compressed by Ktor `Compression` (whereas `image/*` artwork and SSE streams skip dynamic compression).

### Single-Client (`c = 1`)

| Workload | Miru SQLite (p50 / p95 / p99) | Miru PostgreSQL (p50 / p95 / p99) | Tautulli SQLite (p50 / p95 / p99) | Throughput (req/s: SQLite / PG / Tautulli) |
| :--- | :---: | :---: | :---: | :---: |
| **History pagination** *(50 rows, p.1)* | **`3.47`** / `5.79` / `7.64` ms | `9.41` / `18.30` / `21.38` ms | `5.16` / `14.39` / `18.16` ms | **`260.7`** / `93.5` / `146.7` |
| **30d overview stats** | **`10.79`** / `13.84` / `14.93` ms | `10.87` / `18.59` / `32.81` ms | `27.96` / `38.09` / `45.96` ms | **`90.8`** / `82.7` / `33.9` |
| **All-time overview stats** *(10k plays)* | `38.12` / `50.61` / `61.40` ms | **`28.88`** / `51.92` / `65.78` ms | `80.71` / `102.91` / `115.85` ms | `25.4` / **`31.2`** / `11.9` |
| **User stats** *(Miru combined vs Tautulli split)* | `22.20` / `31.81` / `34.12` ms | `18.34` / `24.07` / `26.04` ms | **`3.78`** ms *(watch)* / **`3.24`** ms *(player)* | `43.2` / `54.5` / `290.1` & `328.6` |
| **Artwork proxy — cache miss** *(20 items)* | `3.60` / `5.86` / `17.60` ms | — | **`2.30`** / `2.73` / `2.75` ms | `227.8` / — / **`439.1`** |
| **Artwork proxy — cache hit** | **`0.87`** / `1.44` / `2.21` ms | — | `1.25` / `3.85` / `4.37` ms | **`1,100.9`** / — / `568.1` |

### Concurrent Load (`c = 10`)

| Workload | Miru SQLite (p50 / p95 / p99) | Miru PostgreSQL (p50 / p95 / p99) | Tautulli SQLite (p50 / p95 / p99) | Throughput (req/s: SQLite / PG / Tautulli) |
| :--- | :---: | :---: | :---: | :---: |
| **History pagination** *(50 rows, p.1)* | `29.88` / `59.29` / `67.49` ms | **`11.51`** / `24.28` / `30.27` ms | `247.92` / `380.13` / `395.03` ms | `301.8` / **`757.3`** / `41.4` |
| **30d overview stats** | `133.04` / `212.22` / `259.65` ms | **`19.70`** / `32.10` / `36.56` ms | `853.65` / `1,183.97` / `1,438.81` ms | `72.2` / **`484.0`** / `11.6` |
| **All-time overview stats** *(10k plays)* | `367.17` / `562.62` / `614.31` ms | **`101.22`** / `123.84` / `134.45` ms | `1,712.86` / `2,404.71` / `2,492.44` ms | `25.9` / **`98.6`** / `5.8` |
| **User stats** *(Miru combined vs Tautulli split)* | `202.52` / `303.63` / `322.58` ms | `31.97` / `76.88` / `85.91` ms | **`9.56`** ms *(watch)* / **`11.33`** ms *(player)* | `48.3` / `257.1` / `990.8` & `866.3` |
| **Artwork proxy — cache hit** | **`2.87`** / `5.04` / `6.12` ms | — | `10.82` / `16.19` / `19.07` ms | **`3,196.9`** / — / `900.1` |

- **Notes on user stats & API gzip:** `/api/v1/users/{id}/stats?range=all` returns a single comprehensive analytics payload (`playsPerDay`, `topMovies`, `topShows`, `mostRewatched`, `decisionBreakdown`, `topPlatforms`, `libraryBreakdown`, completion rate, and binge streak). Tautulli's `get_user_watch_time_stats` and `get_user_player_stats` return narrower single-aspect summaries and therefore run faster individually (`9.56 ms` and `11.33 ms` at `c=10`). Across the JSON endpoints above (`> 1024 B`), dynamic gzip compression inside the `2 vCPU` container exchanges a few milliseconds of deflate CPU under `c=10` load (compared with earlier uncompressed `c=10` PG numbers such as `15.45 → 19.70 ms` on 30d stats and `23.27 → 31.97 ms` on user stats) for substantially smaller wire payloads; clients requesting `Accept-Encoding: identity` bypass compression entirely.

---

## 5. Frontend Load & Live Update Overhead

Measured in headless Playwright Chromium (`1440×900`, HTTP cache disabled, 5 cold loads per app):

| Metric | Miru (SvelteKit 2 / Svelte 5 SPA) | Tautulli (Mako + Bootstrap / jQuery) |
| :--- | :---: | :---: |
| **Static bundle (on disk, logical assets)** | `193.5 KiB` JS + `11.8 KiB` CSS gzipped (`252.6 KiB` across `58` files; `176.3 KiB` `.br` + `206.9 KiB` `.gz` precompressed siblings) | *Server-rendered HTML/static dir* |
| **Cold load requests (median)** | **`50`** | `52` |
| **Wire transfer size (median)** | **`334.5 KiB`** (`342,536 B` — precompressed Brotli `.br` served directly) | `504.2 KiB` (`516,343 B` — gzip enabled) |
| **DOMContentLoaded (median)** | `138.2 ms` | **`83.3 ms`** |
| **First Contentful Paint — FCP (median)** | `248 ms` | **`56 ms`** |
| **Largest Contentful Paint — LCP (median)** | `248 ms` | **`156 ms`** |
| **30s live overhead (3 active streams)** | **`0` HTTP requests / `8.7 KiB`** (`8,906 B` SSE frames) | `3` HTTP polling requests / `64.7 KiB` (`66,267 B`) |

- **Takeaway:** Serving build-time precompressed `.br` and `.gz` siblings directly in `StaticWebRoutes` (with zero runtime compression CPU) cuts Miru's empty-cache initial wire transfer by **56.9%** (`775.7 → 334.5 KiB`), making Miru **33.7% lighter over the wire than Tautulli (`504.2 KiB`)** while shaving LCP from `260 ms` to `248 ms`. Tautulli's server-rendered HTML still delivers an earlier first paint (`56 ms` FCP / `156 ms` LCP vs `248 ms` SPA bootstrap), after which Miru's persistent SSE stream eliminates repeated polling and cuts 30-second live monitoring traffic by **7.4×**.
