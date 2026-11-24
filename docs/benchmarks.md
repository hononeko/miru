# Miru vs. Tautulli Performance Benchmarks

Reproducible side-by-side comparison of **Miru** (Kotlin 2.4 / Ktor 3 / JDK 25 `jlink` on Distroless, tested with both **SQLite** and **PostgreSQL 17**) against **Tautulli** (`v2.19.1`, Python 3.14 / CherryPy, SQLite) on a shared **10,000-play** dataset under identical container CPU and memory limits (`2 vCPU`, `512 MiB` RAM).

> [!NOTE]
> All benchmarks were executed locally using a Docker + Playwright benchmark harness against Miru commit `3982d9c` with all security hardening controls (**MRU-31**–**MRU-36**) enabled by default. Results cover both areas where Miru wins decisively and candid trade-offs such as JVM baseline memory and uncached initial page transfer.

---

## Executive Summary

- **18× faster live UI updates with 7.4× less network traffic:** Miru's Plex WebSocket + Server-Sent Events (SSE) push pipeline updates the browser DOM in **526–528 ms** median (including Miru's intentional `500 ms` notification debounce window) with **0 polling requests** and **8.7 KiB** transferred over 30 seconds, compared to Tautulli's default 10-second polling interval (**9.5 s** DOM update, **3 requests / 64.7 KiB** over 30 s).
- **3.8×–15× lower p50 latency under concurrent API load:** Under 10 concurrent clients on 10,000 plays, **Miru + PostgreSQL** serves paginated history in **10.97 ms p50 / 77.02 ms p99** (`642.4 req/s`, **12.2×** Tautulli's `52.5 req/s` and **15×** lower p50 than Tautulli's `164.71 ms` / `1,276.72 ms p99`), `30d` overview stats in **76.26 ms p50** (`128.6 req/s`, **8.6×** Tautulli's `15.0 req/s`), and all-time stats in **505.12 ms p50** (`20.0 req/s`, **3.8×** Tautulli's `1,918.28 ms` / `5.3 req/s`).
- **14.8× higher cached artwork throughput:** Miru's in-memory `BoundedArtworkCache` serves repeated poster thumbnails in **0.28 ms p50 / 3,501 req/s** (`c=1`) and **1.56 ms p50 / 5,481.5 req/s** (`c=10`) vs Tautulli's disk-backed proxy at **1.95 ms / 499.4 req/s** (`c=1`) and **29.27 ms / 369.7 req/s** (`c=10`).
- **Smaller container image than Tautulli (both compressed & unpacked):** Following the switch to digest-pinned Google Distroless (`base-nossl-debian13:nonroot`) + a trimmed 14-module JDK 25 `jlink` runtime (**MRU-36**), Miru's image is **70.7 MiB** compressed / **181.0 MiB** uncompressed (`linux/amd64`) — **39% smaller unpacked** than Tautulli's **72.8 MiB / 296.1 MiB** (down from `162.5 MiB / 550.0 MiB` on full Temurin JRE).
- **17% faster cold startup & sub-second migration:** Miru reaches `/readyz` healthy in **1.23 s** (SQLite) / **1.34 s** (PostgreSQL) vs **1.48 s** for Tautulli `/status`, and imports a 10,000-play Tautulli database via API in **651 ms** (SQLite) / **1.92 s** (PostgreSQL).
- **Trade-offs:** Tautulli maintains a lower idle memory footprint (**82.1 MiB** working set vs Miru's **232–241 MiB** JVM baseline), faster narrow single-stat user endpoints (`get_user_watch_time_stats` in **8.59 ms** at `c=10` vs Miru's combined `/api/v1/users/{id}/stats` analytics bundle in **30.11 ms**), and faster uncached cold-load paint (**204 ms** LCP / **504.3 KiB** gzipped vs Miru's **280 ms** LCP / **775.8 KiB** uncompressed static wire transfer).

---

## Test Environment & Methodology

| Parameter | Value |
| :--- | :--- |
| **Host Hardware** | Intel Core i9-14900K (32 CPUs), 62.5 GiB RAM, Linux `7.3.4-1-cachyos-custom` |
| **Docker Engine** | `29.10.0` (single isolated bridge network `miru-bench-net`) |
| **Container Limits** | `--cpus=2 --memory=512m` applied identically to Miru and Tautulli app containers (`postgres:17-alpine` alongside for PG tests) |
| **Miru Version** | Commit `3982d9c` (`miru:bench` built from [`Dockerfile`](../Dockerfile): Distroless `base-nossl-debian13:nonroot` + 14-module Temurin JDK 25 `jlink`, Kotlin 2.4 / Ktor 3) |
| **Tautulli Version** | `ghcr.io/tautulli/tautulli:latest` (`v2.19.1`, Python `3.14.10`, digest `sha256:96eaa165...` amd64) |
| **Mock Plex Server** | Local Bun/Node content-negotiating JSON + Plex XML server with WebSocket `/:/websockets/notifications` and deterministic `300×450` PNG posters |
| **Dataset** | `10,000` plays across `365` days (`10` users, `300` movies/episodes, mixed Direct Play / Direct Stream / HW & SW Transcode) seeded into Tautulli SQLite and imported into Miru SQLite & PostgreSQL |
| **Auth & Security State** | Both Miru (`AUTH_MODE=local`, global CSP/security headers, rate limiting, library-section RBAC) and Tautulli (`http_password` + JWT session) ran with authentication enabled; API benchmarks used `X-Api-Key` (Miru) and `apikey=` (Tautulli); Playwright benchmarks used authenticated session cookies |

---

## 1. Resource Footprint & Container Image Size

Sampled at 1-second intervals for **30 seconds** after initial startup/sync settled (idle, then with **3 concurrent active streams**: 1 Direct Play, 1 Direct Stream, 1 HW Transcode).

| Workload / Metric | Miru (SQLite) | Miru (PostgreSQL 17) | Tautulli (SQLite) |
| :--- | :---: | :---: | :---: |
| **Image size — compressed (`linux/amd64`)** | **`70.7 MiB`** *(published `edge`)* | **`70.7 MiB`** *(+ PG 17)* | `72.8 MiB` |
| **Image size — uncompressed (`linux/amd64`)** | **`181.0 MiB`** *(local `3982d9c`)* | **`181.0 MiB`** *(+ PG 17)* | `296.1 MiB` |
| **Idle working set memory (median)** | `240.9 MiB` | `232.3 MiB` *(+ `52.9 MiB` PG)* | **`82.1 MiB`** |
| **Idle anonymous RSS (cgroup `anon`, median)** | `226.7 MiB` | `229.6 MiB` *(+ `19.2 MiB` PG)* | **`78.8 MiB`** |
| **Idle CPU % (median / mean)** | `0.05%` / **`0.09%`** | `0.04%` / `0.29%` | **`0.03%`** / `0.18%` |
| **Active 3-stream working set (median)** | `242.7 MiB` (`+1.8 MiB`) | `231.6 MiB` *(+ `53.3 MiB` PG)* | **`83.0 MiB`** (`+0.9 MiB`) |
| **Active 3-stream anon RSS (median)** | `228.8 MiB` | `229.1 MiB` *(+ `19.2 MiB` PG)* | **`79.9 MiB`** |
| **Active 3-stream CPU % (median / mean)** | `0.05%` / **`0.08%`** | `0.08%` / `0.75%` | **`0.03%`** / `0.24%` |

- **Takeaway:** Switching to shell-less Distroless + a trimmed 14-module `jlink` JRE (**MRU-36**) cut Miru's image size by **56.5% compressed** (`162.5 → 70.7 MiB`) and **67.1% uncompressed** (`550.0 → 181.0 MiB`), making Miru smaller than Tautulli on disk and over the wire. Python/CherryPy retains roughly **2.8–2.9× lower baseline RAM** at idle (`82.1 MiB` vs Miru's `232–241 MiB` JVM + Netty + HikariCP + in-memory `BoundedArtworkCache` baseline), while Miru's incremental growth under active streams remains minimal (`+1.8 MiB` SQLite, flat on PG).

---

## 2. Startup, Readiness & Tautulli Migration Speed

Measured across **5 cold `docker run` invocations** per configuration up to HTTP `200` on `/readyz` (Miru) or `/status` (Tautulli):

| Runtime / Configuration | Median | Min | Max |
| :--- | :---: | :---: | :---: |
| **Miru SQLite (10k-play DB)** | **`1,225.6 ms`** | `1,161.6 ms` | `1,252.1 ms` |
| **Miru SQLite (empty DB + Flyway migrations)** | **`1,330.3 ms`** | `1,295.6 ms` | `1,373.7 ms` |
| **Miru PostgreSQL 17 (10k-play DB, warm PG)** | **`1,336.0 ms`** | `1,277.7 ms` | `1,395.1 ms` |
| **Tautulli SQLite (10k-play DB)** | `1,480.5 ms` | `1,436.5 ms` | `1,499.1 ms` |

- **Tautulli 10,000-play migration import (`POST /api/v1/admin/imports/tautulli`)**:
  - **SQLite:** **`651.4 ms`** (`10,000` plays + `10` users, `0` malformed rows)
  - **PostgreSQL 17:** **`1,916.5 ms`** (`10,000` plays + `10` users, `0` malformed rows)

---

## 3. Real-Time Responsiveness

Measured over **5 trials each** for `start`, `pause`, and `stop` triggered by Mock Plex `PlaySessionStateNotification` WebSocket frames:

| Stage & Event | Miru (WS → SSE / DOM) | Tautulli (WS → SQLite / 10 s UI Poll) | Notes |
| :--- | :---: | :---: | :--- |
| **Backend WS reaction — `start` (median)** | `515.9 ms` *(SSE event)* | **`18.7 ms`** *(SQLite row)* | Miru applies an intentional `500 ms` debounce window (`SessionReconciler`) |
| **Backend WS reaction — `pause` (median)** | `515.7 ms` *(SSE event)* | **`18.9 ms`** *(SQLite row)* | ~16 ms Miru reconciliation + SSE overhead on top of 500 ms debounce |
| **Backend WS reaction — `stop` (median)** | `515.6 ms` *(SSE event)* | **`19.1 ms`** *(SQLite row deleted)* | Both subscribe directly to Plex `/:/websockets/notifications` |
| **Browser UI DOM — `start` (median)** | **`528.4 ms`** | `9,527.9 ms` | **18.0× faster** in browser |
| **Browser UI DOM — `pause` (median)** | **`526.3 ms`** | `9,472.3 ms` | **18.0× faster** in browser |
| **Browser UI DOM — `stop` (median)** | **`528.5 ms`** | `9,494.7 ms` | **18.0× faster** in browser |

- **Architectural difference:** While Tautulli processes incoming Plex WebSocket frames quickly in Python (~`19 ms`), Tautulli's web dashboard does not maintain a server push channel and instead polls `/get_activity` on a default `10 s` timer (`HOME_REFRESH_INTERVAL = 10`). Miru debounces bursts for `500 ms` (`SessionReconciler.DEFAULT_DEBOUNCE_WINDOW`) and pushes updates immediately to connected browsers over **SSE**, yielding **~528 ms** end-to-end UI updates.

---

## 4. Query & API Latency (10,000 Plays)

Each scenario ran **20 warm-up requests** followed by **200 measured requests** (or **20 unique keys** for artwork cache misses) at concurrency `1` and `10`.

### Single-Client (`c = 1`)

| Workload | Miru SQLite (p50 / p95 / p99) | Miru PostgreSQL (p50 / p95 / p99) | Tautulli SQLite (p50 / p95 / p99) | Throughput (req/s: SQLite / PG / Tautulli) |
| :--- | :---: | :---: | :---: | :---: |
| **History pagination** *(50 rows, p.1)* | **`3.18`** / `4.62` / `5.40` ms | `8.79` / `13.01` / `15.02` ms | `7.29` / `14.44` / `17.87` ms | **`287.8`** / `105.9` / `126.7` |
| **30d overview stats** | `19.32` / `27.33` / `37.29` ms | **`17.47`** / `44.39` / `53.71` ms | `27.92` / `39.78` / `44.62` ms | **`49.5`** / `47.8` / `34.2` |
| **All-time overview stats** *(10k plays)* | `62.72` / `79.35` / `113.00` ms | **`52.82`** / `96.69` / `110.06` ms | `76.68` / `92.66` / `98.04` ms | `15.3` / **`17.5`** / `12.6` |
| **User stats** *(Miru combined vs Tautulli split)* | `13.40` / `19.42` / `24.22` ms | `14.94` / `35.89` / `39.84` ms | **`8.96`** ms *(watch)* / **`6.45`** ms *(player)* | `69.4` / `54.0` / `125.2` & `162.7` |
| **Artwork proxy — cache miss** *(20 items)* | `1.75` / `3.21` / `15.29` ms | — | **`1.15`** / `1.69` / `2.38` ms | `397.3` / — / **`784.0`** |
| **Artwork proxy — cache hit** | **`0.28`** / `0.41` / `0.50` ms | — | `1.95` / `3.56` / `5.45` ms | **`3,501.0`** / — / `499.4` |

### Concurrent Load (`c = 10`)

| Workload | Miru SQLite (p50 / p95 / p99) | Miru PostgreSQL (p50 / p95 / p99) | Tautulli SQLite (p50 / p95 / p99) | Throughput (req/s: SQLite / PG / Tautulli) |
| :--- | :---: | :---: | :---: | :---: |
| **History pagination** *(50 rows, p.1)* | `41.83` / `107.69` / `128.85` ms | **`10.97`** / `47.82` / `77.02` ms | `164.71` / `316.01` / `1,276.72` ms | `192.1` / **`642.4`** / `52.5` |
| **30d overview stats** | `207.22` / `306.52` / `328.71` ms | **`76.26`** / `123.88` / `135.47` ms | `701.65` / `868.94` / `948.25` ms | `46.6` / **`128.6`** / `15.0` |
| **All-time overview stats** *(10k plays)* | `645.42` / `834.04` / `986.94` ms | **`505.12`** / `631.65` / `690.75` ms | `1,918.28` / `2,542.42` / `2,701.24` ms | `15.4` / **`20.0`** / `5.3` |
| **User stats** *(Miru combined vs Tautulli split)* | `165.97` / `260.96` / `296.46` ms | `30.11` / `64.29` / `85.92` ms | **`8.59`** ms *(watch)* / **`19.51`** ms *(player)* | `58.3` / `286.0` / `918.7` & `464.9` |
| **Artwork proxy — cache hit** | **`1.56`** / `3.20` / `3.69` ms | — | `29.27` / `41.52` / `50.01` ms | **`5,481.5`** / — / `369.7` |

- **Notes on user stats:** `/api/v1/users/{id}/stats?range=all` returns a single comprehensive analytics payload (`playsPerDay`, `topMovies`, `topShows`, `mostRewatched`, `decisionBreakdown`, `topPlatforms`, `libraryBreakdown`, completion rate, and binge streak). Tautulli's `get_user_watch_time_stats` and `get_user_player_stats` return narrower single-aspect summaries and therefore run faster individually (`8.59 ms` and `19.51 ms` at `c=10`).

---

## 5. Frontend Load & Live Update Overhead

Measured in headless Playwright Chromium (`1440×900`, HTTP cache disabled, 5 cold loads per app):

| Metric | Miru (SvelteKit 2 / Svelte 5 SPA) | Tautulli (Mako + Bootstrap / jQuery) |
| :--- | :---: | :---: |
| **Static bundle (on disk, gzipped)** | `191.0 KiB` JS + `11.7 KiB` CSS (`250.1 KiB` all `58` files) | *Server-rendered HTML/static dir* |
| **Cold load requests (median)** | **`49`** | `52` |
| **Wire transfer size (median)** | `775.8 KiB` (`794,370 B` — static responses served uncompressed by Ktor) | **`504.3 KiB`** (`516,356 B` — gzip enabled) |
| **DOMContentLoaded (median)** | `151.9 ms` | **`129.7 ms`** |
| **First Contentful Paint — FCP (median)** | `280 ms` | **`100 ms`** |
| **Largest Contentful Paint — LCP (median)** | `280 ms` | **`204 ms`** |
| **30s live overhead (3 active streams)** | **`0` HTTP requests / `8.7 KiB`** (`8,906 B` SSE frames) | `3` HTTP polling requests / `64.7 KiB` (`66,258 B`) |

- **Takeaway:** Tautulli's server-rendered landing page with CherryPy gzip compression paints faster on an empty-cache cold load (`204 ms` vs `280 ms` LCP, `504.3 KiB` vs `775.8 KiB`). Miru's SvelteKit JS+CSS bundle is only `202.7 KiB` gzipped on disk, so enabling Ktor static response compression (or putting a compressing reverse proxy in front of Miru) cuts uncached wire transfer well below Tautulli's. Once open, Miru's persistent SSE stream eliminates repeated polling and reduces 30-second live monitoring traffic by **7.4×**.
