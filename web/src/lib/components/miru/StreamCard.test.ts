import { cleanup, render } from '@testing-library/svelte';
import { afterEach, describe, expect, it } from 'vitest';
import type { ActiveStreamDto } from '$api/types';
import StreamCard from './StreamCard.svelte';

function makeStream(state: string): ActiveStreamDto {
	return {
		serverId: 'srv-1',
		sessionKey: 'sess-1',
		sessionId: 'plex-1',
		state,
		progressPercent: 45,
		viewOffsetMs: 1_200_000,
		startedAtMs: 1_700_000_000_000,
		lastSeenAtMs: 1_700_000_060_000,
		pausedDurationSec: 0,
		media: {
			ratingKey: '101',
			mediaType: 'movie',
			title: 'Dune: Part Two',
			year: 2024,
			durationMs: 2_600_000
		},
		user: {
			id: 'u-1',
			username: 'alice',
			displayName: 'Alice'
		},
		player: {
			name: 'Apple TV 4K',
			product: 'Plex for Apple TV',
			platform: 'tvOS',
			location: 'lan'
		},
		technical: {
			streamDecision: 'direct_play',
			hwTranscode: false,
			throttled: false,
			bandwidthKbps: 18_400,
			videoResolution: '4k',
			videoCodec: 'hevc',
			audioCodec: 'truehd'
		}
	};
}

describe('StreamCard active stream animation classes', () => {
	afterEach(() => {
		cleanup();
	});

	it('applies continuous live animation classes for playing and buffering streams', () => {
		for (const state of ['playing', 'buffering']) {
			const { container } = render(StreamCard, { props: { stream: makeStream(state) } });
			const article = container.querySelector('article');
			expect(article?.className).toContain('stream-card-live');
			expect(container.querySelector('.stream-progress-live')).toBeTruthy();
			cleanup();
		}
	});

	it('omits continuous live animation classes when the stream is paused', () => {
		const { container } = render(StreamCard, { props: { stream: makeStream('paused') } });
		const article = container.querySelector('article');
		expect(article?.className).not.toContain('stream-card-live');
		expect(container.querySelector('.stream-progress-live')).toBeNull();
	});
});
