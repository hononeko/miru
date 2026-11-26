import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import { ServersStore } from './servers.svelte';

describe('ServersStore', () => {
	let store: ServersStore;

	beforeEach(() => {
		store = new ServersStore();
	});

	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('loads server summaries, computes hasMultipleServers, and resolves byId', async () => {
		const getSpy = vi.spyOn(api, 'GET').mockResolvedValueOnce({
			data: {
				servers: [
					{
						id: 'srv-plex',
						name: 'Home Plex',
						serverType: 'plex',
						configured: true,
						reachable: true,
						websocketEnabled: true,
						websocketState: 'CONNECTED'
					},
					{
						id: 'srv-jf',
						name: 'Studio Jellyfin',
						serverType: 'jellyfin',
						configured: true,
						reachable: true,
						websocketEnabled: true,
						websocketState: 'CONNECTED'
					}
				]
			},
			response: new Response(null, { status: 200 })
		} as never);

		await store.refresh();

		expect(getSpy).toHaveBeenCalledTimes(1);
		expect(store.loaded).toBe(true);
		expect(store.hasMultipleServers).toBe(true);
		expect(store.configuredServers).toHaveLength(2);
		expect(store.byId('srv-jf')?.name).toBe('Studio Jellyfin');
		expect(store.byId('missing')).toBeNull();

		// Subsequent refresh without force does not re-fetch
		await store.refresh();
		expect(getSpy).toHaveBeenCalledTimes(1);

		store.reset();
		expect(store.loaded).toBe(false);
		expect(store.servers).toHaveLength(0);
	});

	it('ignores malformed payloads cleanly without crashing single-server mocks', async () => {
		vi.spyOn(api, 'GET').mockResolvedValueOnce({
			data: { configured: true },
			response: new Response(null, { status: 200 })
		} as never);

		await store.refresh();

		expect(store.loaded).toBe(false);
		expect(store.hasMultipleServers).toBe(false);
		expect(store.servers).toHaveLength(0);
	});
});
