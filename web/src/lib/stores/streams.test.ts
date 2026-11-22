import { describe, expect, it } from 'vitest';
import type { ActiveStreamDto } from '$api/types';
import { StreamsStore } from './streams.svelte';

function makeStream(overrides: Partial<ActiveStreamDto> = {}): ActiveStreamDto {
	return {
		serverId: 'srv-1',
		sessionKey: 'sess-1',
		sessionId: 'abc',
		user: {
			id: 'u-1',
			username: 'sergiu',
			displayName: 'Sergiu',
			avatarUrl: '/api/v1/users/u-1/avatar'
		},
		media: {
			ratingKey: 'rk-1',
			mediaType: 'episode',
			title: "Trojan's Horse",
			grandparentTitle: 'Severance',
			parentIndex: 2,
			mediaIndex: 5,
			year: 2025,
			thumbUrl: '/api/v1/media/rk-1/thumb',
			librarySectionKey: '2',
			durationMs: 3_480_000
		},
		player: {
			name: 'Apple TV 4K',
			platform: 'tvOS',
			product: 'Plex for Apple TV',
			location: 'lan'
		},
		technical: {
			streamDecision: 'transcode',
			hwTranscode: true,
			videoCodec: 'hevc',
			videoResolution: '4k',
			bandwidthKbps: 24_800,
			throttled: true
		},
		state: 'playing',
		viewOffsetMs: 1_200_000,
		progressPercent: 34.5,
		startedAtMs: 1000,
		lastSeenAtMs: 2000,
		pausedDurationSec: 0,
		...overrides
	};
}

describe('StreamsStore SSE state transitions', () => {
	it('applies snapshot, started, updated, and stopped events without duplicates', () => {
		const store = new StreamsStore();

		store.applySnapshot({
			totalCount: 1,
			serverName: 'Kerrlab Plex',
			streams: [makeStream()]
		});
		expect(store.totalCount).toBe(1);
		expect(store.serverName).toBe('Kerrlab Plex');
		expect(store.totalBandwidthKbps).toBe(24_800);
		expect(store.lanBandwidthKbps).toBe(24_800);
		expect(store.wanBandwidthKbps).toBe(0);

		const second = makeStream({
			sessionKey: 'sess-2',
			media: { ...makeStream().media, ratingKey: 'rk-2', title: 'Dune' },
			player: { location: 'wan' },
			technical: { ...makeStream().technical, bandwidthKbps: 8_000 }
		});
		store.applyStarted({ timestampMs: 3000, stream: second });
		expect(store.totalCount).toBe(2);
		expect(store.totalBandwidthKbps).toBe(32_800);
		expect(store.wanBandwidthKbps).toBe(8_000);

		store.applyUpdated({
			timestampMs: 4000,
			stream: { ...second, state: 'paused', progressPercent: 55 },
			previousState: 'playing',
			previousStreamDecision: 'transcode',
			streamDecisionChanged: false
		});
		expect(store.totalCount).toBe(2);
		expect(store.streams.find((s) => s.sessionKey === 'sess-2')?.state).toBe('paused');

		store.applyStopped({
			timestampMs: 5000,
			serverId: 'srv-1',
			sessionKey: 'sess-1',
			ratingKey: 'rk-1',
			userId: 'u-1',
			reason: 'STOPPED',
			mergedIntoHistory: false,
			stream: makeStream()
		});
		expect(store.totalCount).toBe(1);
		expect(store.streams[0].sessionKey).toBe('sess-2');

		store.applySnapshot({ totalCount: 0, streams: [] });
		expect(store.serverName).toBeNull();

		store.applySnapshot({ totalCount: 0, serverName: 'Kerrlab Plex', streams: [] });
		expect(store.serverName).toBe('Kerrlab Plex');
		store.disconnect();
		expect(store.serverName).toBeNull();
	});
});
