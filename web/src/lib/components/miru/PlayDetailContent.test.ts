import { cleanup, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it } from 'vitest';
import type { HistoryDetailResponse } from '$api/types';
import PlayDetailContent from './PlayDetailContent.svelte';

function makeDetail(overrides: Partial<HistoryDetailResponse> = {}): HistoryDetailResponse {
	return {
		id: 'hist-1',
		serverId: 'srv-1',
		userId: 'u-1',
		username: 'alice',
		displayName: 'Alice',
		media: {
			ratingKey: 'rk-1',
			mediaType: 'movie',
			title: 'Spirited Away',
			durationMs: 7_500_000
		},
		viewOffsetMs: 7_200_000,
		watchedDurationSec: 7_200,
		pausedDurationSec: 60,
		completionPercent: 96,
		isWatched: true,
		streamTech: {
			streamDecision: 'transcode',
			videoDecision: 'transcode',
			audioDecision: 'transcode',
			hwTranscode: true,
			videoCodec: 'hevc',
			audioCodec: 'truehd',
			videoResolution: '4k',
			bandwidthKbps: 12_000,
			transcodeVideoCodec: 'h264',
			transcodeAudioCodec: 'aac',
			container: 'mkv',
			transcodeContainer: 'mpegts',
			subtitleDecision: 'burn'
		},
		player: {
			playerName: 'Living Room TV',
			playerPlatform: 'tvOS',
			playerProduct: 'Plex for Apple TV',
			location: 'lan'
		},
		startedAtMs: 1_700_000_000_000,
		stoppedAtMs: 1_700_007_200_000,
		...overrides
	};
}

describe('PlayDetailContent', () => {
	afterEach(() => {
		cleanup();
	});

	it('renders stream conversion details including target codecs, container, and subtitle burn-in', () => {
		render(PlayDetailContent, { props: { detail: makeDetail() } });

		expect(screen.getByText('Alice')).toBeTruthy();
		expect(screen.getByText('4K · HEVC → H264 · transcode')).toBeTruthy();
		expect(screen.getByText('TRUEHD → AAC · transcode')).toBeTruthy();
		expect(screen.getByText('MKV → MPEGTS')).toBeTruthy();
		expect(screen.getByText('Burn-in')).toBeTruthy();
	});

	it('falls back to username and then userId when displayName is absent', () => {
		render(PlayDetailContent, {
			props: { detail: makeDetail({ displayName: null, username: null, userId: 'u-fallback' }) }
		});

		expect(screen.getByText('u-fallback')).toBeTruthy();
	});
});
