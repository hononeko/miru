import { browser } from '$app/env';
import { api } from '$api/client';
import type {
	ActiveStreamDto,
	ActiveStreamsResponse,
	SessionStartedEventDto,
	SessionStoppedEventDto,
	SessionUpdatedEventDto
} from '$api/types';
import { streamKey } from '$lib/utils';

export type SseStatus = 'connecting' | 'connected' | 'reconnecting' | 'offline';

const BASE_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;

export class StreamsStore {
	streams = $state<ActiveStreamDto[]>([]);
	status = $state<SseStatus>('offline');
	serverName = $state<string | null>(null);
	lastUpdatedAtMs = $state<number | null>(null);

	private es: EventSource | null = null;
	private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
	private attempt = 0;
	private active = false;

	get totalCount(): number {
		return this.streams.length;
	}

	get totalBandwidthKbps(): number {
		return this.streams.reduce((sum, s) => sum + (s.technical.bandwidthKbps ?? 0), 0);
	}

	get lanBandwidthKbps(): number {
		return this.streams
			.filter((s) => (s.player.location ?? '').toLowerCase() === 'lan')
			.reduce((sum, s) => sum + (s.technical.bandwidthKbps ?? 0), 0);
	}

	get wanBandwidthKbps(): number {
		return this.streams
			.filter((s) => (s.player.location ?? '').toLowerCase() === 'wan')
			.reduce((sum, s) => sum + (s.technical.bandwidthKbps ?? 0), 0);
	}

	connect(): void {
		if (!browser || this.active) return;
		this.active = true;
		this.attempt = 0;
		this.openEventSource();
	}

	disconnect(): void {
		this.active = false;
		if (this.reconnectTimer !== null) {
			clearTimeout(this.reconnectTimer);
			this.reconnectTimer = null;
		}
		if (this.es) {
			this.es.close();
			this.es = null;
		}
		this.status = 'offline';
		this.serverName = null;
	}

	async refresh(): Promise<void> {
		const res = await api.GET('/api/v1/streams');
		if (res.data) {
			this.applySnapshot(res.data);
		}
	}

	applySnapshot(payload: ActiveStreamsResponse): void {
		this.streams = [...payload.streams].sort((a, b) => b.startedAtMs - a.startedAtMs);
		this.serverName = payload.serverName?.trim() || null;
		this.lastUpdatedAtMs = Date.now();
	}

	applyStarted(payload: SessionStartedEventDto): void {
		this.upsertStream(payload.stream);
		this.lastUpdatedAtMs = Date.now();
	}

	applyUpdated(payload: SessionUpdatedEventDto): void {
		this.upsertStream(payload.stream);
		this.lastUpdatedAtMs = Date.now();
	}

	applyStopped(payload: SessionStoppedEventDto): void {
		const targetKey = `${payload.serverId}:${payload.sessionKey}:${payload.ratingKey}`;
		this.streams = this.streams.filter((s) => streamKey(s) !== targetKey);
		this.lastUpdatedAtMs = Date.now();
	}

	private upsertStream(stream: ActiveStreamDto): void {
		const key = streamKey(stream);
		const idx = this.streams.findIndex((s) => streamKey(s) === key);
		if (idx >= 0) {
			const next = [...this.streams];
			next[idx] = stream;
			this.streams = next;
		} else {
			this.streams = [stream, ...this.streams];
		}
	}

	private openEventSource(): void {
		if (!this.active || !browser) return;
		this.status = this.attempt === 0 ? 'connecting' : 'reconnecting';

		const es = new EventSource('/api/v1/streams/events', { withCredentials: true });
		this.es = es;

		es.addEventListener('open', () => {
			this.attempt = 0;
			this.status = 'connected';
		});

		es.addEventListener('snapshot', (ev) => {
			try {
				const parsed = JSON.parse((ev as MessageEvent).data) as ActiveStreamsResponse;
				this.applySnapshot(parsed);
				this.status = 'connected';
				this.attempt = 0;
			} catch {
				// Ignore malformed frame
			}
		});

		es.addEventListener('session_started', (ev) => {
			try {
				const parsed = JSON.parse((ev as MessageEvent).data) as SessionStartedEventDto;
				this.applyStarted(parsed);
			} catch {
				// Ignore malformed frame
			}
		});

		es.addEventListener('session_updated', (ev) => {
			try {
				const parsed = JSON.parse((ev as MessageEvent).data) as SessionUpdatedEventDto;
				this.applyUpdated(parsed);
			} catch {
				// Ignore malformed frame
			}
		});

		es.addEventListener('session_stopped', (ev) => {
			try {
				const parsed = JSON.parse((ev as MessageEvent).data) as SessionStoppedEventDto;
				this.applyStopped(parsed);
			} catch {
				// Ignore malformed frame
			}
		});

		es.onerror = () => {
			if (this.es === es) {
				es.close();
				this.es = null;
			}
			if (!this.active) return;
			this.scheduleReconnect();
		};
	}

	private scheduleReconnect(): void {
		this.status = 'reconnecting';
		const ceiling = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * Math.pow(2, this.attempt));
		this.attempt += 1;
		// Full jitter within [0.5 * ceiling, ceiling]
		const delayMs = Math.round(ceiling * (0.5 + Math.random() * 0.5));
		this.reconnectTimer = setTimeout(() => {
			this.reconnectTimer = null;
			this.openEventSource();
		}, delayMs);
	}
}

export const streamsStore = new StreamsStore();
