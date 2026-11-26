import { api } from '$api/client';
import type { MediaServerSummaryDto } from '$api/types';

/** How often the shell refreshes server status while the app is visible */
export const SERVER_STATUS_POLL_INTERVAL_MS = 30_000;

export class ServersStore {
	servers = $state<MediaServerSummaryDto[]>([]);
	loading = $state(false);
	loaded = $state(false);
	/** True when the last refresh failed, so the loaded statuses may be out of date */
	stale = $state(false);

	readonly configuredServers = $derived(this.servers.filter((server) => server.configured));
	readonly hasMultipleServers = $derived(this.configuredServers.length > 1);

	private inflight: Promise<void> | null = null;
	/** Bumped by reset() so a request that finishes after a reset is ignored */
	private generation = 0;
	private pollTimer: ReturnType<typeof setInterval> | null = null;
	private visibilityHandler: (() => void) | null = null;

	byId(serverId: string | null | undefined): MediaServerSummaryDto | null {
		const id = serverId?.trim();
		if (!id) return null;
		return this.servers.find((server) => server.id === id) ?? null;
	}

	isDisconnected(serverId: string | null | undefined): boolean {
		const srv = this.byId(serverId);
		return srv !== null && !srv.configured;
	}

	async refresh(force = false): Promise<void> {
		if (!force && this.loaded) return;
		if (this.inflight) return this.inflight;

		const generation = this.generation;
		this.loading = true;
		this.inflight = (async () => {
			try {
				const res = await api.GET('/api/v1/servers');
				if (generation !== this.generation) return;
				if (res.data && Array.isArray(res.data.servers)) {
					this.servers = res.data.servers;
					this.loaded = true;
					this.stale = false;
				} else if (this.loaded) {
					this.stale = true;
				}
			} catch {
				// Best-effort metadata fetch; keep existing state but flag it as possibly outdated
				if (generation === this.generation && this.loaded) this.stale = true;
			} finally {
				if (generation === this.generation) {
					this.loading = false;
					this.inflight = null;
				}
			}
		})();

		return this.inflight;
	}

	/**
	 * Keeps the server list and statuses fresh while the app is open. Polling pauses while the
	 * tab is hidden and refreshes as soon as it becomes visible again. Calling it again is a no-op.
	 */
	startPolling(intervalMs = SERVER_STATUS_POLL_INTERVAL_MS): void {
		if (this.pollTimer || typeof window === 'undefined') return;

		this.pollTimer = setInterval(() => {
			if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
			void this.refresh(true);
		}, intervalMs);

		if (typeof document !== 'undefined') {
			const handler = () => {
				if (document.visibilityState === 'visible') void this.refresh(true);
			};
			document.addEventListener('visibilitychange', handler);
			this.visibilityHandler = handler;
		}
	}

	stopPolling(): void {
		if (this.pollTimer) {
			clearInterval(this.pollTimer);
			this.pollTimer = null;
		}
		if (this.visibilityHandler && typeof document !== 'undefined') {
			document.removeEventListener('visibilitychange', this.visibilityHandler);
		}
		this.visibilityHandler = null;
	}

	get polling(): boolean {
		return this.pollTimer !== null;
	}

	reset(): void {
		this.stopPolling();
		this.generation += 1;
		this.servers = [];
		this.loading = false;
		this.loaded = false;
		this.stale = false;
		this.inflight = null;
	}
}

export const serversStore = new ServersStore();
