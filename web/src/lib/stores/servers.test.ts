import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import type { MediaServerSummaryDto } from '$api/types';
import { SERVER_STATUS_POLL_INTERVAL_MS, ServersStore } from './servers.svelte';

function server(overrides: Partial<MediaServerSummaryDto> & Pick<MediaServerSummaryDto, 'id'>): MediaServerSummaryDto {
	return {
		name: overrides.id,
		serverType: 'plex',
		configured: true,
		reachable: true,
		websocketEnabled: true,
		websocketState: 'CONNECTED',
		...overrides
	};
}

function serversResponse(servers: MediaServerSummaryDto[]) {
	return { data: { servers }, response: new Response(null, { status: 200 }) } as never;
}

function setVisibility(state: DocumentVisibilityState) {
	Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
	document.dispatchEvent(new Event('visibilitychange'));
}

describe('ServersStore', () => {
	let store: ServersStore;

	beforeEach(() => {
		store = new ServersStore();
	});

	afterEach(() => {
		store.reset();
		vi.restoreAllMocks();
		vi.useRealTimers();
		setVisibility('visible');
	});

	it('loads server summaries, computes hasMultipleServers, and resolves byId', async () => {
		const getSpy = vi.spyOn(api, 'GET').mockResolvedValueOnce(
			serversResponse([
				server({ id: 'srv-plex', name: 'Home Plex' }),
				server({ id: 'srv-jf', name: 'Studio Jellyfin', serverType: 'jellyfin' })
			])
		);

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

	it('counts only configured servers towards hasMultipleServers', async () => {
		vi.spyOn(api, 'GET').mockResolvedValueOnce(
			serversResponse([
				server({ id: 'srv-plex' }),
				server({ id: 'srv-old-plex', configured: false, reachable: false, websocketState: 'DISCONNECTED' })
			])
		);

		await store.refresh();

		expect(store.servers).toHaveLength(2);
		expect(store.configuredServers.map((s) => s.id)).toEqual(['srv-plex']);
		expect(store.hasMultipleServers).toBe(false);
		expect(store.isDisconnected('srv-old-plex')).toBe(true);
		expect(store.isDisconnected('srv-plex')).toBe(false);
		expect(store.isDisconnected('missing')).toBe(false);
	});

	it('keeps the last list but marks it stale when a refresh fails, and clears that on the next success', async () => {
		const getSpy = vi
			.spyOn(api, 'GET')
			.mockResolvedValueOnce(serversResponse([server({ id: 'a' }), server({ id: 'b' })]))
			.mockRejectedValueOnce(new TypeError('Failed to fetch'))
			.mockResolvedValueOnce({ error: { title: 'Bad Gateway' }, response: new Response(null, { status: 502 }) } as never)
			.mockResolvedValueOnce(serversResponse([server({ id: 'a' }), server({ id: 'b', reachable: false })]));

		await store.refresh();
		expect(store.stale).toBe(false);

		await store.refresh(true);
		expect(store.stale).toBe(true);
		expect(store.servers.map((s) => s.id)).toEqual(['a', 'b']);

		await store.refresh(true);
		expect(store.stale).toBe(true);

		await store.refresh(true);
		expect(store.stale).toBe(false);
		expect(store.byId('b')?.reachable).toBe(false);
		expect(getSpy).toHaveBeenCalledTimes(4);
	});

	it('does not mark a never-loaded store as stale', async () => {
		vi.spyOn(api, 'GET').mockRejectedValueOnce(new TypeError('Failed to fetch'));

		await store.refresh();

		expect(store.loaded).toBe(false);
		expect(store.stale).toBe(false);
	});

	it('polls on an interval, picking up a newly connected server and status changes', async () => {
		vi.useFakeTimers();
		const getSpy = vi
			.spyOn(api, 'GET')
			.mockResolvedValueOnce(serversResponse([server({ id: 'plex' })]))
			.mockResolvedValueOnce(serversResponse([server({ id: 'plex' }), server({ id: 'jf', serverType: 'jellyfin' })]))
			.mockResolvedValueOnce(
				serversResponse([server({ id: 'plex' }), server({ id: 'jf', serverType: 'jellyfin', reachable: false })])
			);

		await store.refresh();
		store.startPolling();
		expect(store.polling).toBe(true);
		expect(store.hasMultipleServers).toBe(false);

		await vi.advanceTimersByTimeAsync(SERVER_STATUS_POLL_INTERVAL_MS);
		expect(getSpy).toHaveBeenCalledTimes(2);
		expect(store.hasMultipleServers).toBe(true);

		await vi.advanceTimersByTimeAsync(SERVER_STATUS_POLL_INTERVAL_MS);
		expect(getSpy).toHaveBeenCalledTimes(3);
		expect(store.byId('jf')?.reachable).toBe(false);
	});

	it('only starts one poller however often startPolling is called', async () => {
		vi.useFakeTimers();
		const getSpy = vi.spyOn(api, 'GET').mockResolvedValue(serversResponse([server({ id: 'plex' })]));

		store.startPolling();
		store.startPolling();
		store.startPolling();
		await vi.advanceTimersByTimeAsync(SERVER_STATUS_POLL_INTERVAL_MS);

		expect(getSpy).toHaveBeenCalledTimes(1);
	});

	it('pauses polling while the tab is hidden and refreshes as soon as it is visible again', async () => {
		vi.useFakeTimers();
		const getSpy = vi.spyOn(api, 'GET').mockResolvedValue(serversResponse([server({ id: 'plex' })]));

		store.startPolling();
		setVisibility('hidden');
		await vi.advanceTimersByTimeAsync(SERVER_STATUS_POLL_INTERVAL_MS * 3);
		expect(getSpy).not.toHaveBeenCalled();

		setVisibility('visible');
		await vi.advanceTimersByTimeAsync(0);
		expect(getSpy).toHaveBeenCalledTimes(1);
	});

	it('stops polling on stopPolling and on reset', async () => {
		vi.useFakeTimers();
		const getSpy = vi.spyOn(api, 'GET').mockResolvedValue(serversResponse([server({ id: 'plex' })]));

		store.startPolling();
		store.stopPolling();
		expect(store.polling).toBe(false);
		await vi.advanceTimersByTimeAsync(SERVER_STATUS_POLL_INTERVAL_MS * 2);
		setVisibility('visible');
		await vi.advanceTimersByTimeAsync(0);
		expect(getSpy).not.toHaveBeenCalled();

		store.startPolling();
		store.reset();
		expect(store.polling).toBe(false);
		await vi.advanceTimersByTimeAsync(SERVER_STATUS_POLL_INTERVAL_MS * 2);
		expect(getSpy).not.toHaveBeenCalled();
	});

	it('drops a response that arrives after reset, so a signed-out session does not repopulate the list', async () => {
		let answer!: (value: unknown) => void;
		vi.spyOn(api, 'GET').mockReturnValueOnce(new Promise((resolve) => (answer = resolve)) as never);

		const pending = store.refresh();
		store.reset();
		answer(serversResponse([server({ id: 'a' }), server({ id: 'b' })]));
		await pending;

		expect(store.loaded).toBe(false);
		expect(store.servers).toHaveLength(0);
		expect(store.loading).toBe(false);
	});
});
