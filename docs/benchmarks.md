# Miru vs. Tautulli Performance Benchmarks

Reproducible side-by-side comparison of **Miru** (Kotlin 2.4 / Ktor 3 / JDK 25 `jlink` on Distroless, tested with both **SQLite** and **PostgreSQL 17**) against **Tautulli** (`v2.19.1`, Python 3.14 / CherryPy, SQLite) on a shared **10,000-play** dataset under identical container CPU and memory limits (`2 vCPU`, `512 MiB` RAM).

> [!NOTE]
> All benchmarks were executed locally using a Docker + Playwright benchmark harness against Miru commit `d7e796c` (`main` after **PR #68** memory/streaming optimizations; app build `030844d`) with all security hardening controls enabled by default. Results cover both areas where Miru wins decisively and candid trade-offs such as JVM memory vs Python and uncached initial page transfer.

---

## Executive Summary

- **18× faster live UI updates with 7.4× less network traffic:** Miru's Plex WebSocket + Server-Sent Events (SSE) push pipeline updates the browser DOM in **526–532 ms** median (including Miru's intentional `500 ms` notification debounce window) with **0 polling requests** and **8.7 KiB** transferred over 30 seconds, compared to Tautulli's default 10-second polling interval (**9.5 s** DOM update, **3 requests / 64.7 KiB** over 30 s).
- **14×–46× lower p50 latency under concurrent API load:** Under 10 concurrent clients on 10,000 plays, **Miru + PostgreSQL** serves paginated history in **11.49 ms p50 / 22.78 ms p99** (`801.8 req/s`, **13.9×** Tautulli's `57.5 req/s` and **16.1×** lower p50 than Tautulli's `184.47 ms` / `288.95 ms p99`), single-pass streamed `30d` overview stats in **15.45 ms p50** (`597.6 req/s`, **43.3×** Tautulli's `13.8 req/s` and **46.1×** lower p50), and all-time stats in **80.65 ms p50** (`119.8 req/s`, **24.4×** Tautulli's `4.9 req/s` / `2,028.97 ms` p50).
- **7.0× higher cached artwork throughput:** Miru's in-memory `BoundedArtworkCache` serves repeated poster thumbnails in **0.33 ms p50 / 2,706 req/s** (`c=1`) and **1.67 ms p50 / 5,488.9 req/s** (`c=10`) vs Tautulli's disk-backed proxy at **0.61 ms / 1,528.0 req/s** (`c=1`) and **11.62 ms / 784.7 req/s** (`c=10`).
- **Smaller container image than Tautulli (both compressed & unpacked):** Using digest-pinned Google Distroless (`base-nossl-debian13:nonroot`), a trimmed 14-module JDK 25 `jlink` runtime, and a zero-dependency JSON Logback encoder (removing Jackson 3 completely from the runtime), Miru's image is **68.0 MiB** compressed / **175.5 MiB** uncompressed (`linux/amd64`) — **41% smaller unpacked** than Tautulli's **72.8 MiB / 296.1 MiB**.
- **23% faster cold startup & streaming migration:** Miru reaches `/readyz` healthy in **1.16 s** (SQLite) / **1.23 s** (PostgreSQL) vs **1.50 s** for Tautulli `/status`, and streams a 10,000-play Tautulli database via API in bounded batches in **731 ms** (SQLite) / **2.03 s** (PostgreSQL).
- **Trade-offs:** Tautulli maintains a lower idle memory footprint (**82.0 MiB** working set vs Miru's **163–183 MiB** post-load JVM working set, settling to **147.5 MiB** after idle G1 uncommit), faster narrow single-stat user endpoints (`get_user_watch_time_stats` in **8.08 ms** at `c=10` vs Miru's combined `/api/v1/users/{id}/stats` analytics bundle in **23.27 ms** on PostgreSQL), and faster uncached cold-load paint (**168 ms** LCP / **504.3 KiB** gzipped vs Miru's **260 ms** LCP / **775.7 KiB** uncompressed static wire transfer).

---

## Test Environment & Methodology

| Parameter | Value |
| :--- | :--- |
| **Host Hardware** | Intel Core i9-14900K (32 CPUs), 62.5 GiB RAM, Linux `7.3.4-1-cachyos-custom` |
| **Docker Engine** | `29.10.0` (single isolated bridge network `miru-bench-net`) |
| **Container Limits** | `--cpus=2 --memory=512m` applied identically to Miru and Tautulli app containers (`postgres:17-alpine` alongside for PG tests) |
| **Miru Version** | Commit `030844d` / `main` `d7e796c` (`miru:bench` built from [`Dockerfile`](../Dockerfile): Distroless `base-nossl-debian13:nonroot`, 14-module Temurin JDK 25 `jlink`, `-XX:+UseCompactObjectHeaders`, G1GC periodic uncommit, Kotlin 2.4 / Ktor 3) |
| **Tautulli Version** | `ghcr.io/tautulli/tautulli:latest` (`v2.19.1`, Python `3.14.10`, digest `sha256:e2f94101...` amd64) |
| **Mock Plex Server** | Local Bun/Node content-negotiating JSON + Plex XML server with WebSocket `/:/websockets/notifications` and deterministic `300×450` PNG posters |
| **Dataset** | `10,000` plays across `365` days (`10` users, `300` movies/episodes, mixed Direct Play / Direct Stream / HW & SW Transcode) seeded into Tautulli SQLite and imported into Miru SQLite & PostgreSQL |
| **Auth & Security State** | Both Miru (`AUTH_MODE=local`, global CSP/security headers, rate limiting, library-section RBAC) and Tautulli (`http_password` + JWT session) ran with authentication enabled; API benchmarks used `X-Api-Key` (Miru) and `apikey=` (Tautulli); Playwright benchmarks used authenticated session cookies |

---

## 1. Resource Footprint & Container Image Size

Sampled at 1-second intervals for **30 seconds** starting 5 seconds after startup + 10k-play import (idle window, then immediately followed by **3 concurrent active streams**: 1 Direct Play, 1 Direct Stream, 1 HW Transcode).

| Workload / Metric | Miru (SQLite) | Miru (PostgreSQL 17) | Tautulli (SQLite) |
| :--- | :---: | :---: | :---: |
| **Image size — compressed (`linux/amd64`)** | **`68.0 MiB`** *(published `edge`)* | **`68.0 MiB`** *(+ PG 17)* | `72.8 MiB` |
| **Image size — uncompressed (`linux/amd64`)** | **`175.5 MiB`** | **`175.5 MiB`** *(+ PG 17)* | `296.1 MiB` |
| **Post-import 30s idle working set (median)** | `182.7 MiB` *(min `162.9 MiB`)* | `166.5 MiB` *(+ `52.2 MiB` PG)* | **`82.0 MiB`** |
| **Post-import 30s idle anon RSS (cgroup `anon`)** | `168.4 MiB` *(min `149.3 MiB`)* | `164.0 MiB` *(+ `19.3 MiB` PG)* | **`79.0 MiB`** |
| **Idle CPU % (median / mean)** | `0.16%` / `0.28%` | `0.19%` / `0.51%` | **`0.04%`** / `0.45%` |
| **Active 3-stream working set (median)** | `163.8 MiB` | `163.2 MiB` *(+ `52.7 MiB` PG)* | **`83.0 MiB`** |
| **Active 3-stream anon RSS (median)** | `149.9 MiB` | `161.2 MiB` *(+ `19.3 MiB` PG)* | **`80.0 MiB`** |
| **Active 3-stream CPU % (median / mean)** | `0.14%` / `0.65%` | `0.15%` / **`0.32%`** | **`0.03%`** / `0.10%` |

- **Takeaway:** Combining G1 periodic heap uncommit (`-XX:G1PeriodicGCInterval=30000 -XX:G1PeriodicGCSystemLoadThreshold=0.0`), JDK 25 Compact Object Headers (`-XX:+UseCompactObjectHeaders`), `MALLOC_ARENA_MAX=2`, single-pass streaming analytics/imports, and removing Jackson 3 cut Miru's working set by **~30% right after load** (`240.9 → 163.8–182.7 MiB`) and by **52.6% after burst load settles** (`310.9 → 147.5 MiB` after a full 65 s idle settle window; `129.6 MiB` fresh idle). Miru's image is now **68.0 MiB** compressed / **175.5 MiB** unpacked (`amd64`), **41% smaller unpacked** than Tautulli. Python/CherryPy still retains roughly **2× lower active RAM** (`82–83 MiB` vs Miru's `163–183 MiB`).

---

## 2. Startup, Readiness & Tautulli Migration Speed

Measured across **5 cold `docker run` invocations** per configuration up to HTTP `200` on `/readyz` (Miru) or `/status` (Tautulli):

| Runtime / Configuration | Median | Min | Max |
| :--- | :---: | :---: | :---: |
| **Miru SQLite (10k-play DB)** | **`1,160.9 ms`** | `1,086.0 ms` | `1,257.2 ms` |
| **Miru SQLite (empty DB + Flyway migrations)** | **`1,264.8 ms`** | `1,227.1 ms` | `1,349.6 ms` |
| **Miru PostgreSQL 17 (10k-play DB, warm PG)** | **`1,232.9 ms`** | `1,169.5 ms` | `1,262.1 ms` |
| **Tautulli SQLite (10k-play DB)** | `1,498.3 ms` | `1,429.7 ms` | `1,580.0 ms` |

- **Tautulli 10,000-play migration import (`POST /api/v1/admin/imports/tautulli`)**:
  - **SQLite:** **`730.9 ms`** (`10,000` plays + `10` users, `0` malformed rows, streamed in bounded `O(batchSize)` batches)
  - **PostgreSQL 17:** **`2,032.5 ms`** (`10,000` plays + `10` users, `0` malformed rows)

---

## 3. Real-Time Responsiveness

Measured over **5 trials each** for `start`, `pause`, and `stop` triggered by Mock Plex `PlaySessionStateNotification` WebSocket frames:

| Stage & Event | Miru (WS → SSE / DOM) | Tautulli (WS → SQLite / 10 s UI Poll) | Notes |
| :--- | :---: | :---: | :--- |
| **Backend WS reaction — `start` (median)** | `518.0 ms` *(SSE event)* | **`20.1 ms`** *(SQLite row)* | Miru applies an intentional `500 ms` debounce window (`SessionReconciler`) |
| **Backend WS reaction — `pause` (median)** | `514.6 ms` *(SSE event)* | **`18.0 ms`** *(SQLite row)* | ~15–18 ms Miru reconciliation + SSE overhead on top of 500 ms debounce |
| **Backend WS reaction — `stop` (median)** | `514.2 ms` *(SSE event)* | **`17.8 ms`** *(SQLite row deleted)* | Both subscribe directly to Plex `/:/websockets/notifications` |
| **Browser UI DOM — `start` (median)** | **`531.8 ms`** | `9,527.6 ms` | **17.9× faster** in browser |
| **Browser UI DOM — `pause` (median)** | **`527.6 ms`** | `9,485.4 ms` | **18.0× faster** in browser |
| **Browser UI DOM — `stop` (median)** | **`525.9 ms`** | `9,483.6 ms` | **18.0× faster** in browser |

- **Architectural difference:** While Tautulli processes incoming Plex WebSocket frames quickly in Python (~`18–20 ms`), Tautulli's web dashboard does not maintain a server push channel and instead polls `/get_activity` on a default `10 s` timer (`HOME_REFRESH_INTERVAL = 10`). Miru debounces bursts for `500 ms` (`SessionReconciler.DEFAULT_DEBOUNCE_WINDOW`) and pushes updates immediately to connected browsers over **SSE**, yielding **~526–532 ms** end-to-end UI updates.

---

## 4. Query & API Latency (10,000 Plays)

Each scenario ran **20 warm-up requests** followed by **200 measured requests** (or **20 unique keys** for artwork cache misses) at concurrency `1` and `10`.

### Single-Client (`c = 1`)

| Workload | Miru SQLite (p50 / p95 / p99) | Miru PostgreSQL (p50 / p95 / p99) | Tautulli SQLite (p50 / p95 / p99) | Throughput (req/s: SQLite / PG / Tautulli) |
| :--- | :---: | :---: | :---: | :---: |
| **History pagination** *(50 rows, p.1)* | **`3.69`** / `5.75` / `6.64` ms | `9.40` / `18.34` / `25.34` ms | `6.96` / `13.70` / `15.56` ms | **`254.9`** / `94.2` / `132.7` |
| **30d overview stats** | `11.29` / `19.33` / `27.01` ms | **`10.00`** / `16.75` / `22.86` ms | `27.96` / `36.21` / `42.86` ms | `80.4` / **`92.3`** / `34.1` |
| **All-time overview stats** *(10k plays)* | `36.08` / `46.47` / `56.32` ms | **`28.54`** / `44.75` / `54.95` ms | `79.31` / `97.17` / `110.19` ms | `27.2` / **`33.0`** / `12.2` |
| **User stats** *(Miru combined vs Tautulli split)* | `16.10` / `24.18` / `36.08` ms | `12.26` / `22.44` / `30.24` ms | **`4.46`** ms *(watch)* / **`4.67`** ms *(player)* | `58.1` / `73.0` / `201.4` & `209.7` |
| **Artwork proxy — cache miss** *(20 items)* | `1.86` / `4.99` / `17.73` ms | — | **`1.12`** / `1.68` / `2.16` ms | `342.4` / — / **`782.3`** |
| **Artwork proxy — cache hit** | **`0.33`** / `0.56` / `0.65` ms | — | `0.61` / `0.96` / `1.04` ms | **`2,706.2`** / — / `1,528.0` |

### Concurrent Load (`c = 10`)

| Workload | Miru SQLite (p50 / p95 / p99) | Miru PostgreSQL (p50 / p95 / p99) | Tautulli SQLite (p50 / p95 / p99) | Throughput (req/s: SQLite / PG / Tautulli) |
| :--- | :---: | :---: | :---: | :---: |
| **History pagination** *(50 rows, p.1)* | `34.87` / `78.02` / `87.81` ms | **`11.49`** / `19.72` / `22.78` ms | `184.47` / `272.66` / `288.95` ms | `231.6` / **`801.8`** / `57.5` |
| **30d overview stats** | `134.65` / `189.06` / `220.01` ms | **`15.45`** / `24.86` / `29.00` ms | `712.83` / `922.84` / `970.29` ms | `71.2` / **`597.6`** / `13.8` |
| **All-time overview stats** *(10k plays)* | `328.96` / `465.40` / `543.47` ms | **`80.65`** / `111.97` / `120.39` ms | `2,028.97` / `2,692.54` / `3,375.27` ms | `29.4` / **`119.8`** / `4.9` |
| **User stats** *(Miru combined vs Tautulli split)* | `173.82` / `286.32` / `308.95` ms | `23.27` / `36.74` / `40.45` ms | **`8.08`** ms *(watch)* / **`10.88`** ms *(player)* | `53.8` / `399.8` / `986.3` & `790.7` |
| **Artwork proxy — cache hit** | **`1.67`** / `2.92` / `3.57` ms | — | `11.62` / `24.87` / `29.68` ms | **`5,488.9`** / — / `784.7` |

- **Notes on user stats:** `/api/v1/users/{id}/stats?range=all` returns a single comprehensive analytics payload (`playsPerDay`, `topMovies`, `topShows`, `mostRewatched`, `decisionBreakdown`, `topPlatforms`, `libraryBreakdown`, completion rate, and binge streak). Tautulli's `get_user_watch_time_stats` and `get_user_player_stats` return narrower single-aspect summaries and therefore run faster individually (`8.08 ms` and `10.88 ms` at `c=10`).

---

## 5. Frontend Load & Live Update Overhead

Measured in headless Playwright Chromium (`1440×900`, HTTP cache disabled, 5 cold loads per app):

| Metric | Miru (SvelteKit 2 / Svelte 5 SPA) | Tautulli (Mako + Bootstrap / jQuery) |
| :--- | :---: | :---: |
| **Static bundle (on disk, gzipped)** | `191.0 KiB` JS + `11.7 KiB` CSS (`250.1 KiB` all `58` files) | *Server-rendered HTML/static dir* |
| **Cold load requests (median)** | **`49`** | `52` |
| **Wire transfer size (median)** | `775.7 KiB` (`794,329 B` — static responses served uncompressed by Ktor) | **`504.3 KiB`** (`516,355 B` — gzip enabled) |
| **DOMContentLoaded (median)** | `146.2 ms` | **`107.4 ms`** |
| **First Contentful Paint — FCP (median)** | `260 ms` | **`56 ms`** |
| **Largest Contentful Paint — LCP (median)** | `260 ms` | **`168 ms`** |
| **30s live overhead (3 active streams)** | **`0` HTTP requests / `8.7 KiB`** (`8,906 B` SSE frames) | `3` HTTP polling requests / `64.7 KiB` (`66,264 B`) |

- **Takeaway:** Tautulli's server-rendered landing page with CherryPy gzip compression paints faster on an empty-cache cold load (`168 ms` vs `260 ms` LCP, `504.3 KiB` vs `775.7 KiB`). Miru's SvelteKit JS+CSS bundle is only `202.7 KiB` gzipped on disk, so enabling Ktor static response compression (or putting a compressing reverse proxy in front of Miru) cuts uncached wire transfer well below Tautulli's. Once open, Miru's persistent SSE stream eliminates repeated polling and reduces 30-second live monitoring traffic by **7.4×**.
