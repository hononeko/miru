import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { LibraryGrowthDayResponse, LibraryStatsResponse } from '$api/types';
import { authStore } from '$stores/auth.svelte';
import LibrarySectionDetail from './LibrarySectionDetail.svelte';

function makeStats(growth: LibraryGrowthDayResponse[], sectionType = 'movie'): LibraryStatsResponse {
	return {
		section: {
			id: '1',
			serverId: 'srv-1',
			sectionKey: '1',
			title: 'Movies',
			sectionType,
			itemCount: 10,
			childCount: 0,
			leafCount: 10,
			totalSizeBytes: 1_000,
			totalDurationMs: 1_000,
			updatedAtMs: 1_000
		},
		byResolution: {},
		byVideoCodec: {},
		byAudioCodec: {},
		// Several scans of the same day, as a real server produces
		snapshots: Array.from({ length: 10 }, (_, i) => ({
			id: `snap-${i}`,
			capturedAtMs: Date.UTC(2026, 10, 24, i),
			itemCount: 10,
			childCount: 0,
			leafCount: 10,
			totalSizeBytes: 1_000,
			totalDurationMs: 1_000
		})),
		growth
	};
}

function signInAs(role: 'ADMIN' | 'USER') {
	authStore.principal = {
		accountId: 'acc-1',
		username: 'someone',
		email: null,
		role,
		linkedMediaUserIds: ['u-1'],
		authSource: 'SESSION_COOKIE',
		authProvider: 'LOCAL',
		avatarUrl: null
	};
}

describe('LibrarySectionDetail', () => {
	beforeEach(() => {
		signInAs('ADMIN');
	});

	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
		authStore.principal = null;
	});

	it('draws one growth column per day with changes and spells out additions, removals and size on click', async () => {
		render(LibrarySectionDetail, {
			props: {
				stats: makeStats([
					{ date: '2026-11-20', added: 3, removed: 1, sizeDeltaBytes: 2_147_483_648 },
					{ date: '2026-11-24', added: 0, removed: 1, sizeDeltaBytes: -1_048_576 }
				]),
				recentItems: []
			}
		});

		expect(screen.getByText('movies added and removed per day (UTC)')).toBeTruthy();
		const columns = screen.getAllByRole('button', { name: /^2026-11-/ });
		expect(columns.map((c) => c.getAttribute('aria-label'))).toEqual([
			'2026-11-20: 3 movies added, 1 movie removed',
			'2026-11-24: 1 movie removed'
		]);
		expect(columns[0].querySelectorAll('.bg-chart-1, .bg-chart-2')).toHaveLength(2);
		expect(columns[1].querySelectorAll('.bg-chart-1')).toHaveLength(0);
		expect(columns[1].querySelectorAll('.bg-chart-2')).toHaveLength(1);

		await fireEvent.click(columns[0]);
		expect(screen.getByText('+3 movies added')).toBeTruthy();
		expect(screen.getByText('−1 movie removed')).toBeTruthy();
		expect(screen.getByText('+2.0 GB')).toBeTruthy();

		await fireEvent.click(columns[1]);
		expect(screen.getByText('−1.0 MB')).toBeTruthy();
	});

	it('counts episodes for shows and says when nothing has changed yet', () => {
		render(LibrarySectionDetail, {
			props: {
				stats: makeStats([{ date: '2026-11-24', added: 1, removed: 0, sizeDeltaBytes: 0 }], 'show'),
				recentItems: []
			}
		});
		expect(screen.getByRole('button', { name: '2026-11-24: 1 episode added' })).toBeTruthy();
		cleanup();

		render(LibrarySectionDetail, { props: { stats: makeStats([]), recentItems: [] } });
		expect(screen.getByText('No additions or removals recorded yet')).toBeTruthy();
	});
});
