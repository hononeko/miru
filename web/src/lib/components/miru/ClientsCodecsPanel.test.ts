import { cleanup, render, screen, within } from '@testing-library/svelte';
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
		expect(within(screen.getByRole('group', { name: 'Video' })).getByText('HEVC 75%')).toBeTruthy();
		expect(within(screen.getByRole('group', { name: 'Audio' })).getByText('EAC3 70%')).toBeTruthy();
		expect(screen.getByText('Video Transcode')).toBeTruthy();
		expect(screen.getByText('Container Remux')).toBeTruthy();
		expect(screen.getAllByText('12 · 60.0%').length).toBeGreaterThan(0);
	});

	it('draws each codec list as one stacked bar of its top four codecs and the remaining ones', () => {
		const codec = (name: string, percent: number, playCount = 1) => ({
			name,
			playCount,
			watchDurationSec: playCount * 60,
			percent
		});
		const clientStats: ClientStatsResponse = {
			range: '30d',
			fromMs: 1_700_000_000_000,
			totalPlays: 100,
			totalWatchDurationSec: 6_000,
			transcodeOrRemuxPlays: 0,
			players: [],
			platforms: [],
			videoCodecs: [codec('HEVC', 50), codec('H264', 30), codec('AV1', 15), codec('VP9', 4.6), codec('VC1', 0.4)],
			audioCodecs: [
				codec('EAC3', 40),
				codec('AAC', 30),
				codec('TRUEHD', 12),
				codec('DTS', 10),
				codec('FLAC', 5, 5),
				codec('OPUS', 3, 3)
			],
			transcodeReasons: []
		};

		render(ClientsCodecsPanel, { props: { clientStats, loading: false } });

		const drawn = (name: string) => {
			const group = screen.getByRole('group', { name });
			const bar = Array.from(group.querySelectorAll<HTMLElement>('div[title]'));
			return {
				widths: bar.map((segment) => segment.style.width),
				titles: bar.map((segment) => segment.title),
				colors: bar.map((segment) => /bg-\S+/.exec(segment.className)?.[0]),
				legend: Array.from(group.querySelectorAll('span[title]'), (item) => item.textContent?.trim())
			};
		};

		// A single remaining codec keeps its name
		const video = drawn('Video');
		expect(video.legend).toEqual(['HEVC 50%', 'H264 30%', 'AV1 15%', 'VP9 5%', 'VC1 <1%']);
		expect(video.widths).toEqual(['50%', '30%', '15%', '4.6%', '0.4%']);
		expect(video.colors).toEqual(['bg-chart-1', 'bg-chart-2', 'bg-chart-3', 'bg-chart-4', 'bg-fg-muted']);

		const audio = drawn('Audio');
		expect(audio.legend).toEqual(['EAC3 40%', 'AAC 30%', 'TRUEHD 12%', 'DTS 10%', 'Other 8%']);
		expect(audio.widths).toEqual(['40%', '30%', '12%', '10%', '8%']);
		expect(audio.titles.at(-1)).toBe('FLAC, OPUS: 8 plays (8m)');

		// The count badge reports every codec seen, not just the ones named.
		expect(screen.getByText('11')).toBeTruthy();
	});

	it('hides an empty codec list and keeps the other', () => {
		const clientStats: ClientStatsResponse = {
			range: '30d',
			fromMs: 1_700_000_000_000,
			totalPlays: 1,
			totalWatchDurationSec: 60,
			transcodeOrRemuxPlays: 0,
			players: [],
			platforms: [],
			videoCodecs: [],
			audioCodecs: [{ name: 'AAC', playCount: 1, watchDurationSec: 60, percent: 100 }],
			transcodeReasons: []
		};

		render(ClientsCodecsPanel, { props: { clientStats, loading: false } });

		expect(screen.queryByRole('group', { name: 'Video' })).toBeNull();
		expect(within(screen.getByRole('group', { name: 'Audio' })).getByText('AAC 100%')).toBeTruthy();
		expect(screen.queryByText('No codec telemetry in this range')).toBeNull();
	});

	it('sizes bars in proportion to each share and labels shares under 1% as "<1%"', () => {
		const clientStats: ClientStatsResponse = {
			range: '30d',
			fromMs: 1_700_000_000_000,
			totalPlays: 1000,
			totalWatchDurationSec: 60_000,
			transcodeOrRemuxPlays: 0,
			players: [
				{ name: 'Plex Web', playCount: 43, watchDurationSec: 600, percent: 4.3 },
				{ name: 'Plexamp', playCount: 1, watchDurationSec: 10, percent: 0.1 }
			],
			platforms: [],
			videoCodecs: [],
			audioCodecs: [],
			transcodeReasons: []
		};

		const { container } = render(ClientsCodecsPanel, { props: { clientStats, loading: false } });

		expect(screen.getByText('43 · 4.3%')).toBeTruthy();
		expect(screen.getByText('1 · <1%')).toBeTruthy();
		const widths = [...container.querySelectorAll<HTMLElement>('div[title^="Plex"]')].map((bar) => bar.style.width);
		expect(widths).toEqual(['4.3%', '0.1%']);
	});

	it('formats item values as playback duration when metric is duration', () => {
		const clientStats: ClientStatsResponse = {
			range: '30d',
			fromMs: 1_700_000_000_000,
			totalPlays: 20,
			totalWatchDurationSec: 54_000,
			transcodeOrRemuxPlays: 8,
			players: [{ name: 'Plex for Apple TV', playCount: 12, watchDurationSec: 36_000, percent: 66.7 }],
			platforms: [],
			videoCodecs: [],
			audioCodecs: [],
			transcodeReasons: []
		};

		render(ClientsCodecsPanel, {
			props: {
				clientStats,
				metric: 'duration',
				loading: false
			}
		});

		expect(screen.getByText('10h 0m · 66.7%')).toBeTruthy();
	});
});
