/// <reference types="@sveltejs/kit" />
/// <reference no-default-lib="true"/>
/// <reference lib="esnext" />
/// <reference lib="webworker" />

import { version } from '$app/env';
import { assets, immutable } from '$app/manifest';

const sw = self as unknown as ServiceWorkerGlobalScope;
const CACHE = `miru-shell-${version}`;
const ASSETS = [...immutable, ...assets].map((entry) => `/${entry.path}`);

sw.addEventListener('install', (event) => {
	event.waitUntil(
		caches
			.open(CACHE)
			.then((cache) => cache.addAll(ASSETS))
			.then(() => sw.skipWaiting())
	);
});

sw.addEventListener('activate', (event) => {
	event.waitUntil(
		caches
			.keys()
			.then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
			.then(() => sw.clients.claim())
	);
});

sw.addEventListener('fetch', (event) => {
	const req = event.request;
	if (req.method !== 'GET') return;

	const url = new URL(req.url);
	if (url.origin !== sw.location.origin) return;

	// Never intercept API calls, SSE streams, proxied artwork, or health probes
	if (url.pathname.startsWith('/api/') || url.pathname === '/healthz' || url.pathname === '/readyz') {
		return;
	}

	// Cache-first for immutable hashed app shell assets
	if (ASSETS.includes(url.pathname)) {
		event.respondWith(
			caches.match(req).then((cached) => cached ?? fetch(req))
		);
		return;
	}

	// Network-first with offline index.html shell fallback for navigations
	if (req.mode === 'navigate') {
		event.respondWith(
			fetch(req).catch(async () => {
				const fallback = await caches.match('/index.html');
				return fallback ?? new Response('Offline', { status: 503 });
			})
		);
	}
});
