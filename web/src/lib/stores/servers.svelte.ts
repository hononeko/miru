import { api } from '$api/client';
import type { MediaServerSummaryDto } from '$api/types';

export class ServersStore {
	servers = $state<MediaServerSummaryDto[]>([]);
	loading = $state(false);
	loaded = $state(false);

	readonly configuredServers = $derived(this.servers.filter((server) => server.configured));
	readonly hasMultipleServers = $derived(this.configuredServers.length > 1 || this.servers.length > 1);

	private inflight: Promise<void> | null = null;

	byId(serverId: string | null | undefined): MediaServerSummaryDto | null {
		const id = serverId?.trim();
		if (!id) return null;
		return this.servers.find((server) => server.id === id) ?? null;
	}

	async refresh(force = false): Promise<void> {
		if (!force && this.loaded) return;
		if (this.inflight) return this.inflight;

		this.loading = true;
		this.inflight = (async () => {
			try {
				const res = await api.GET('/api/v1/servers');
				if (res.data && Array.isArray(res.data.servers)) {
					this.servers = res.data.servers;
					this.loaded = true;
				}
			} catch {
				// Best-effort metadata fetch; keep existing state on transient errors
			} finally {
				this.loading = false;
				this.inflight = null;
			}
		})();

		return this.inflight;
	}

	reset(): void {
		this.servers = [];
		this.loading = false;
		this.loaded = false;
		this.inflight = null;
	}
}

export const serversStore = new ServersStore();
