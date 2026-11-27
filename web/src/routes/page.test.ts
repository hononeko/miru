import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import type {
	ActiveStreamDto,
	ClientStatsResponse,
	HeatmapStatsResponse,
	OverviewStatsResponse
} from '$api/types';
import { authStore } from '$stores/auth.svelte';
import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
import { streamsStore } from '$stores/streams.svelte';
import LiveActivityPage from './+page.svelte';

function makeStream(sessionKey: string, ratingKey: string, title: string): ActiveStreamDto {
	return {
		serverId: 'srv-1',
		sessionKey,
		sessionId: sessionKey,
		user: { id: 'u-1', username: 'vehkiya', displayName: 'Vehkiya', avatarUrl: null },
		media: {
			ratingKey,
			mediaType: 'episode',
			title,
			grandparentTitle: 'Archer (2009)',
			parentIndex: 6,
			mediaIndex: 11,
			year: 2015,
			thumbUrl: null,
			librarySectionKey: '2',
			durationMs: 1_291_000
		},
		player: { name: 'cachy-stardust', platform: 'Linux', product: 'Plezy', location: 'lan' },
		technical: {
			streamDecision: 'direct_play',
			hwTranscode: false,
			videoCodec: 'hevc',
			audioCodec: 'ac3',
			videoResolution: '1080p',
			bandwidthKbps: 8_300,
			throttled: false
		},
		state: 'playing',
		viewOffsetMs: 479_000,
		progressPercent: 37,
		startedAtMs: 1_700_000_000_000,
		lastSeenAtMs: 1_700_000_005_000,
		pausedDurationSec: 0
	};
}

function makeOverview(): OverviewStatsResponse {
	const days = Array.from({ length: 20 }, (_, i) => {
		const date = i === 0 ? '2026-10-30' : `2026-11-${String(i).padStart(2, '0')}`;
		const isPeak = i === 18;
		return {
			date,
			playCount: isPeak ? 5 : 1,
			watchDurationSec: isPeak ? 5_400 : 1_200,
			media: [
				{
					ratingKey: isPeak ? 'rk-archer' : `rk-${i}`,
					mediaType: isPeak ? 'show' : 'movie',
					title: isPeak ? 'Archer (2009)' : `Movie ${i}`,
					playCount: isPeak ? 4 : 1,
					watchDurationSec: isPeak ? 4_200 : 1_200,
					users: isPeak ? ['Vehkiya', 'Alice'] : ['Vehkiya']
				}
			],
			users: isPeak
				? [
						{ userId: 'u-1', username: 'vehkiya', displayName: 'Vehkiya', playCount: 3, watchDurationSec: 3_600 },
						{ userId: 'u-2', username: 'alice', displayName: 'Alice', playCount: 2, watchDurationSec: 1_800 }
					]
				: [{ userId: 'u-1', username: 'vehkiya', displayName: 'Vehkiya', playCount: 1, watchDurationSec: 1_200 }]
		};
	});
	return {
		range: '30d',
		fromMs: 1_700_000_000_000,
		totalPlays: 24,
		totalWatchDurationSec: 28_200,
		activeUsers: 2,
		playsPerDay: days,
		topMovies: [],
		topShows: [],
		decisionBreakdown: {
			directPlay: 24,
			directStream: 0,
			transcode: 0,
			hwTranscode: 0,
			total: 24,
			directPlayPercent: 100,
			directStreamPercent: 0,
			transcodePercent: 0,
			hwTranscodePercent: 0
		}
	};
}

const emptyClients: ClientStatsResponse = {
	range: '30d',
	fromMs: 1_700_000_000_000,
	totalPlays: 24,
	totalWatchDurationSec: 28_200,
	transcodeOrRemuxPlays: 0,
	players: [{ name: 'Plezy', playCount: 24, watchDurationSec: 28_200, percent: 100 }],
	platforms: [],
	videoCodecs: [],
	audioCodecs: [],
	transcodeReasons: []
};

const emptyHeatmap: HeatmapStatsResponse = {
	range: '7d',
	fromMs: 1_700_000_000_000,
	totalPlays: 24,
	totalWatchDurationSec: 28_200,
	maxCellPlays: 0,
	maxCellWatchDurationSec: 0,
	peakDayName: null,
	peakHour: null,
	cells: []
};

describe('Live Activity page fixes', () => {
	beforeEach(() => {
		authStore.principal = {
			accountId: 'acc-1',
			username: 'admin',
			email: null,
			role: 'ADMIN',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			avatarUrl: null
		};
		streamsStore.streams = [];
		streamsStore.lastUpdatedAtMs = null;

		vi.spyOn(api, 'GET').mockImplementation((async (url: string) => {
			if (url === '/api/v1/stats/overview') {
				return { data: makeOverview(), response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/stats/clients') {
				return { data: emptyClients, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/stats/heatmap') {
				return { data: emptyHeatmap, response: new Response(null, { status: 200 }) };
			}
			return {
				data: {
					configured: true,
					connected: true,
					credentialSource: 'OAUTH'
				},
				response: new Response(null, { status: 200 })
			};
		}) as never);
	});

	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
		streamsStore.streams = [];
		streamsStore.lastUpdatedAtMs = null;
		authStore.principal = null;
	});

	it('keeps Live Session Breakdown visible for even active stream counts and shows Updated only after >10s', async () => {
		streamsStore.streams = [
			makeStream('s-1', 'rk-1', 'Ep 1'),
			makeStream('s-2', 'rk-2', 'Ep 2')
		];
		streamsStore.lastUpdatedAtMs = Date.now() - 4_000;

		render(LiveActivityPage);

		const breakdownHeading = screen.getByRole('heading', { name: 'Live Session Breakdown' });
		expect(breakdownHeading).toBeTruthy();
		expect(breakdownHeading.closest('div.rounded-xl')?.className).toContain('xl:col-span-2');
		expect(screen.queryByText(/^Updated /)).toBeNull();

		streamsStore.lastUpdatedAtMs = Date.now() - 15_000;
		await waitFor(() => {
			expect(screen.getByText(/^Updated 1[5-9]s ago$/)).toBeTruthy();
		});
	});

	async function terminateFirstStream() {
		vi.spyOn(api, 'POST').mockResolvedValue({
			data: {
				sessionId: 's-1',
				sessionKey: 's-1',
				reason: 'Maintenance',
				auditEventId: 'aud-1',
				terminatedAtMs: 1_700_000_010_000
			},
			response: new Response(null, { status: 200 })
		} as never);
		await fireEvent.click(screen.getByRole('button', { name: /^Terminate$/ }));
		await fireEvent.click(await screen.findByRole('button', { name: 'Terminate Stream' }));
		await waitFor(() => {
			expect(screen.getByRole('status').textContent).toContain('Termination requested for');
		});
	}

	it('reports a termination as requested only until the stream stops', async () => {
		const stream = makeStream('s-1', 'rk-1', 'Ep 1');
		streamsStore.streams = [stream];
		render(LiveActivityPage);

		await terminateFirstStream();
		expect(screen.getByRole('status').textContent).toContain('Vehkiya — Ep 1');
		expect(screen.getByRole('button', { name: 'Terminating…' })).toHaveProperty('disabled', true);

		streamsStore.applyStopped({
			timestampMs: 1_700_000_020_000,
			serverId: 'srv-1',
			sessionKey: 's-1',
			ratingKey: 'rk-1',
			userId: 'u-1',
			reason: 'explicit_stop',
			mergedIntoHistory: false,
			stream
		});
		await waitFor(() => {
			expect(screen.queryByRole('status')).toBeNull();
		});
	});

	it('offers to terminate again when the stream is still active a minute later', async () => {
		vi.useFakeTimers({ shouldAdvanceTime: true });
		try {
			streamsStore.streams = [makeStream('s-1', 'rk-1', 'Ep 1')];
			render(LiveActivityPage);

			await terminateFirstStream();
			await vi.advanceTimersByTimeAsync(60_000);

			expect(screen.getByRole('alert').textContent).toContain('Still active:');
			expect(screen.queryByRole('status')).toBeNull();
			expect(screen.getByRole('button', { name: /^Terminate$/ })).toHaveProperty('disabled', false);
		} finally {
			vi.useRealTimers();
		}
	});

	it('renders full Plays Per Day history with click popover breakdown and places Viewing Heatmap at bottom', async () => {
		const openSpy = vi.spyOn(mediaDetailsModal, 'open').mockResolvedValue(undefined);
		render(LiveActivityPage);

		await waitFor(() => {
			expect(screen.getByRole('button', { name: /2026-10-30: 1 play/ })).toBeTruthy();
		});

		const peakBar = screen.getByRole('button', { name: /2026-11-18: 5 plays/ });
		await fireEvent.click(peakBar);

		await waitFor(() => {
			expect(screen.getByText('Viewers')).toBeTruthy();
			expect(screen.getByText('Played Media')).toBeTruthy();
		});

		const mediaBtn = screen.getByRole('button', { name: /Archer \(2009\)/ });
		await fireEvent.click(mediaBtn);
		expect(openSpy).toHaveBeenCalledWith('rk-archer', { preferSeries: true });

		// Viewing Heatmap sits below Top Players & Apps at the bottom of the page.
		const playersHeading = screen.getByRole('heading', { name: 'Top Players & Apps' });
		const heatmapHeading = screen.getByRole('heading', { name: 'Viewing Heatmap (7d)' });
		expect(
			Boolean(playersHeading.compareDocumentPosition(heatmapHeading) & Node.DOCUMENT_POSITION_FOLLOWING)
		).toBe(true);
	});

	it('allows admins to open Terminate sheet, fill preset/custom reason, submit, and shows error or confirmation', async () => {
		streamsStore.streams = [makeStream('s-1', 'rk-1', 'Ep 1')];
		const postSpy = vi.spyOn(api, 'POST').mockResolvedValueOnce({
			data: {
				sessionId: 's-1',
				sessionKey: 's-1',
				reason: 'Custom maintenance stop',
				auditEventId: 'aud-1',
				terminatedAtMs: Date.now()
			},
			response: new Response(null, { status: 200 })
		} as never);

		render(LiveActivityPage);

		const terminateBtn = screen.getByRole('button', { name: 'Terminate' });
		await fireEvent.click(terminateBtn);

		await waitFor(() => {
			expect(screen.getByRole('heading', { name: 'Terminate Active Stream' })).toBeTruthy();
		});

		await fireEvent.click(screen.getByRole('button', { name: 'Maintenance' }));
		const textarea = screen.getByPlaceholderText(/Optional message shown/i) as HTMLTextAreaElement;
		expect(textarea.value).toContain('maintenance');

		await fireEvent.input(textarea, { target: { value: 'Custom maintenance stop' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Terminate Stream' }));

		await waitFor(() => {
			expect(postSpy).toHaveBeenCalledWith('/api/v1/streams/{sessionId}/terminate', {
				params: { path: { sessionId: 's-1' } },
				body: { reason: 'Custom maintenance stop' }
			});
			expect(screen.getByRole('status').textContent).toContain('Termination requested for Vehkiya');
			expect(screen.getByRole('button', { name: 'Terminating…' })).toBeTruthy();
		});
	});

	it('displays RFC 7807 error inside Terminate sheet and hides Terminate buttons for USER role', async () => {
		streamsStore.streams = [makeStream('s-1', 'rk-1', 'Ep 1')];
		vi.spyOn(api, 'POST').mockResolvedValueOnce({
			error: {
				type: 'https://miru.hononeko.app/errors/upstream-unavailable',
				title: 'Upstream Unavailable',
				status: 502,
				detail: 'Plex server is unavailable'
			},
			response: new Response(null, { status: 502 })
		} as never);

		render(LiveActivityPage);

		await fireEvent.click(screen.getByRole('button', { name: 'Terminate' }));
		await fireEvent.click(screen.getByRole('button', { name: 'Terminate Stream' }));

		await waitFor(() => {
			expect(screen.getByRole('alert').textContent).toContain('Plex server is unavailable');
		});

		cleanup();
		authStore.principal = {
			accountId: 'acc-2',
			username: 'viewer',
			email: null,
			role: 'USER',
			linkedMediaUserIds: ['u-1'],
			authSource: 'SESSION_COOKIE',
			avatarUrl: null
		};

		render(LiveActivityPage);
		expect(screen.getByRole('heading', { name: 'Archer (2009)' })).toBeTruthy();
		expect(screen.queryByRole('button', { name: 'Terminate' })).toBeNull();
	});

	it('renders unified linked viewer in Plays Per Day legend and bar popover across servers', async () => {
		const unifiedOverview: OverviewStatsResponse = {
			range: '30d',
			fromMs: 1_700_000_000_000,
			totalPlays: 5,
			totalWatchDurationSec: 5_400,
			activeUsers: 2,
			playsPerDay: [
				{
					date: '2026-11-18',
					playCount: 5,
					watchDurationSec: 5_400,
					media: [
						{
							ratingKey: 'rk-dune',
							mediaType: 'movie',
							title: 'Dune',
							playCount: 5,
							watchDurationSec: 5_400,
							users: ['Elena']
						}
					],
					users: [
						{ userId: 'acc-elena', username: 'elena', displayName: 'Elena', playCount: 4, watchDurationSec: 4_200 },
						{ userId: 'u-bob', username: 'bob', displayName: 'Bob', playCount: 1, watchDurationSec: 1_200 }
					]
				}
			],
			topMovies: [],
			topShows: [],
			decisionBreakdown: {
				directPlay: 5,
				directStream: 0,
				transcode: 0,
				hwTranscode: 0,
				total: 5,
				directPlayPercent: 100,
				directStreamPercent: 0,
				transcodePercent: 0,
				hwTranscodePercent: 0
			}
		};
		vi.spyOn(api, 'GET').mockImplementation((async (url: string) => {
			if (url === '/api/v1/stats/overview') {
				return { data: unifiedOverview, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/stats/clients') {
				return { data: emptyClients, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/stats/heatmap') {
				return { data: emptyHeatmap, response: new Response(null, { status: 200 }) };
			}
			return { data: { configured: true, connected: true, credentialSource: 'OAUTH' }, response: new Response(null, { status: 200 }) };
		}) as never);

		render(LiveActivityPage);

		await waitFor(() => {
			expect(screen.getByText('5 plays · 2 active users')).toBeTruthy();
			expect(screen.getAllByText('Elena').length).toBeGreaterThanOrEqual(1);
			expect(screen.getAllByText('Bob').length).toBeGreaterThanOrEqual(1);
		});

		const bar = screen.getByRole('button', { name: /2026-11-18: 5 plays/ });
		await fireEvent.click(bar);

		await waitFor(() => {
			expect(screen.getByText('Viewers')).toBeTruthy();
			expect(screen.getByText('Played Media')).toBeTruthy();
			expect(screen.getAllByText('5 plays · 1h 30m').length).toBeGreaterThanOrEqual(1);
			expect(screen.getAllByText('Elena').length).toBeGreaterThanOrEqual(2);
			expect(screen.getAllByText('4').length).toBeGreaterThanOrEqual(1);
		});
	});
});
