import { cleanup, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it } from 'vitest';
import type { ClientStatsResponse } from '$api/types';
import ClientsCodecsPanel from './ClientsCodecsPanel.svelte';

describe('ClientsCodecsPanel', () => {
	afterEach(() => {
		cleanup();
	});

	it('renders player, platform, codec, and transcode reason breakdowns', () => {
		const clientStats: ClientStatsResponse = {
			range: '30d',
			fromMs: 1_700_000_000_000,
			totalPlays: 20,
			totalWatchDurationSec: 54_000,
			transcodeOrRemuxPlays: 8,
			players: [{ name: 'Plex for Apple TV', playCount: 12, watchDurationSec: 36_000, percent: 60.0 }],
			platforms: [{ name: 'tvOS', playCount: 12, watchDurationSec: 36_000, percent: 60.0 }],
			videoCodecs: [{ name: 'HEVC', playCount: 15, watchDurationSec: 45_000, percent: 75.0 }],
			audioCodecs: [{ name: 'EAC3', playCount: 14, watchDurationSec: 42_000, percent: 70.0 }],
			transcodeReasons: [
				{ name: 'Video Transcode', playCount: 5, watchDurationSec: 12_000, percent: 62.5 },
				{ name: 'Container Remux', playCount: 3, watchDurationSec: 6_000, percent: 37.5 }
			]
		};

		render(ClientsCodecsPanel, {
			props: {
				clientStats,
				loading: false
			}
		});

		expect(screen.getByText('Top Players & Apps')).toBeTruthy();
		expect(screen.getByText('Plex for Apple TV')).toBeTruthy();
		expect(screen.getByText('tvOS')).toBeTruthy();
		expect(screen.getByText('Video · HEVC')).toBeTruthy();
		expect(screen.getByText('Audio · EAC3')).toBeTruthy();
		expect(screen.getByText('Video Transcode')).toBeTruthy();
		expect(screen.getByText('Container Remux')).toBeTruthy();
	});
});
