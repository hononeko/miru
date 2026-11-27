import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import type { UserDetailResponse, UserStatsResponse, WrappedStatsResponse } from '$api/types';
import { authStore } from '$stores/auth.svelte';
import { serversStore } from '$stores/servers.svelte';
import UserProfilePage from './+page.svelte';

vi.mock('$app/state', () => ({
	page: {
		params: { id: 'u-1' },
		url: new URL('http://localhost/users/u-1')
	}
}));

vi.mock('$app/navigation', () => ({
	goto: vi.fn()
}));

const sampleDetail: UserDetailResponse = {
	user: {
		id: 'u-1',
		serverId: 'srv-1',
		externalUserId: '101',
		username: 'vehkiya',
		displayName: 'Vehkiya',
		isAdmin: true,
		isStreaming: false,
		activeStreamCount: 0,
		lastSeenAtMs: 1_700_000_000_000,
		totalPlays: 10,
		totalWatchDurationSec: 36_000,
		avatarUrl: null
	},
	activeStreams: [],
	recentPlays: [],
	totalPlays: 10,
	totalWatchDurationSec: 36_000,
	topClients: [],
	topPlatforms: [],
	decisionBreakdown: {
		directPlay: 10,
		directStream: 0,
		transcode: 0,
		hwTranscode: 0,
		total: 10,
		directPlayPercent: 100,
		directStreamPercent: 0,
		transcodePercent: 0,
		hwTranscodePercent: 0
	},
	libraryBreakdown: []
};

const sampleStats: UserStatsResponse = {
	userId: 'u-1',
	username: 'vehkiya',
	displayName: 'Vehkiya',
	range: '30d',
	fromMs: 1_700_000_000_000,
	totalPlays: 10,
	totalWatchDurationSec: 36_000,
	averageCompletionPercent: 95,
	completedPlays: 9,
	longestBingeStreakDays: 3,
	maxDailyPlays: 4,
	playsPerDay: [],
	topMovies: [],
	topShows: [],
	mostRewatched: [],
	decisionBreakdown: {
		directPlay: 10,
		directStream: 0,
		transcode: 0,
		hwTranscode: 0,
		total: 10,
		directPlayPercent: 100,
		directStreamPercent: 0,
		transcodePercent: 0,
		hwTranscodePercent: 0
	},
	topPlatforms: [],
	libraryBreakdown: []
};

const sampleWrapped: WrappedStatsResponse = {
	year: new Date().getUTCFullYear(),
	userId: 'u-1',
	username: 'vehkiya',
	displayName: 'Vehkiya',
	totalPlays: 10,
	totalWatchDurationSec: 36_000,
	completedPlays: 9,
	averageCompletionPercent: 95,
	longestBingeStreakDays: 3,
	activeDaysCount: 5,
	uniqueMoviesCount: 2,
	uniqueShowsCount: 1,
	peakMonth: 'Nov',
	peakDayOfWeek: 'Tue',
	topMovies: [],
	topShows: [],
	mostRewatched: [],
	topPlatforms: [],
	topClients: [],
	decisionBreakdown: sampleStats.decisionBreakdown,
	playsByMonth: []
};

describe('User profile Most Rewatched card', () => {
	beforeEach(() => {
		authStore.loading = false;
		authStore.principal = {
			accountId: 'acc-1',
			username: 'admin',
			email: null,
			role: 'ADMIN',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			avatarUrl: null
		};
		vi.spyOn(api, 'GET').mockImplementation((async (url: string) => {
			if (url === '/api/v1/users/{id}') {
				return { data: sampleDetail, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/users/{id}/stats') {
				return { data: sampleStats, response: new Response(null, { status: 200 }) };
			}
			return { data: sampleWrapped, response: new Response(null, { status: 200 }) };
		}) as never);
	});

	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
		serversStore.reset();
		authStore.principal = null;
	});

	it('renders the Most Rewatched heading without a confusing refresh-like repeat icon', async () => {
		render(UserProfilePage);

		const heading = await screen.findByRole('heading', { name: 'Most Rewatched' });
		await waitFor(() => {
			expect(heading).toBeTruthy();
		});
		expect(heading.parentElement?.querySelector('svg')).toBeNull();
	});

	it('renders linked ServerBadges, recent play server badges, and narrows endpoints via ServerFilterBar', async () => {
		serversStore.servers = [
			{
				id: 'srv-1',
				name: 'Home Plex',
				serverType: 'plex',
				configured: true,
				reachable: true,
				websocketEnabled: true,
				websocketState: 'CONNECTED'
			},
			{
				id: 'srv-jf',
				name: 'Studio Jellyfin',
				serverType: 'jellyfin',
				configured: false,
				reachable: false,
				websocketEnabled: false,
				websocketState: 'DISABLED'
			}
		];
		serversStore.loaded = true;

		const multiDetail: UserDetailResponse = {
			...sampleDetail,
			user: {
				...sampleDetail.user,
				id: 'acc-vehkiya',
				accountId: 'acc-vehkiya',
				linkedProfiles: [
					{
						userId: 'u-1',
						serverId: 'srv-1',
						serverName: 'Home Plex',
						serverType: 'plex',
						externalUserId: '101',
						username: 'vehkiya',
						displayName: 'Vehkiya',
						isAdmin: true
					},
					{
						userId: 'u-jf',
						serverId: 'srv-jf',
						serverName: 'Studio Jellyfin',
						serverType: 'jellyfin',
						externalUserId: 'jf-101',
						username: 'vehkiya_jf',
						displayName: 'Vehkiya',
						isAdmin: false
					}
				]
			},
			recentPlays: [
				{
					id: 'play-jf-1',
					serverId: 'srv-jf',
					serverName: 'Studio Jellyfin',
					serverType: 'jellyfin',
					ratingKey: 'jf-m1',
					mediaType: 'movie',
					title: 'Arrival',
					durationMs: 6_000_000,
					viewOffsetMs: 6_000_000,
					watchedDurationSec: 6_000,
					pausedDurationSec: 0,
					completionPercent: 100,
					isWatched: true,
					streamDecision: 'direct_play',
					hwTranscode: false,
					startedAtMs: 1_700_000_000_000,
					stoppedAtMs: 1_700_000_006_000
				}
			]
		};

		const getSpy = vi.spyOn(api, 'GET').mockImplementation((async (url: string) => {
			if (url === '/api/v1/users/{id}') {
				return { data: multiDetail, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/users/{id}/stats') {
				return { data: sampleStats, response: new Response(null, { status: 200 }) };
			}
			return { data: sampleWrapped, response: new Response(null, { status: 200 }) };
		}) as never);

		render(UserProfilePage);
		await waitFor(() => {
			expect(screen.getByText(/Linked across 2 servers/)).toBeTruthy();
			expect(screen.getByText('PLEX · Home Plex')).toBeTruthy();
			expect(screen.getAllByText('JELLYFIN · Studio Jellyfin · Disconnected').length).toBeGreaterThanOrEqual(2);
		});

		await fireEvent.click(screen.getByRole('button', { name: /Studio Jellyfin/ }));
		await waitFor(() => {
			expect(getSpy).toHaveBeenCalledWith('/api/v1/users/{id}', {
				params: { path: { id: 'u-1' }, query: { serverId: 'srv-jf' } }
			});
			expect(getSpy).toHaveBeenCalledWith('/api/v1/users/{id}/stats', {
				params: { path: { id: 'u-1' }, query: { range: '30d', metric: 'plays', serverId: 'srv-jf' } }
			});
			expect(getSpy).toHaveBeenCalledWith('/api/v1/stats/wrapped/{year}', {
				params: {
					path: { year: new Date().getUTCFullYear() },
					query: { userId: 'u-1', metric: 'plays', serverId: 'srv-jf' }
				}
			});
		});
	});
});
