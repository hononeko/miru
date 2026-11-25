import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
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

	it('offers the hygiene report to admins only', async () => {
		const getSpy = vi.spyOn(api, 'GET').mockResolvedValue({
			data: {
				sectionKey: '1',
				unwatchedDays: 180,
				sort: 'size_desc',
				totalStaleCount: 0,
				totalReclaimableBytes: 0,
				neverPlayedCount: 0,
				items: []
			},
			response: new Response(null, { status: 200 })
		} as never);

		render(LibrarySectionDetail, { props: { stats: makeStats([]), recentItems: [] } });
		await fireEvent.click(screen.getByRole('button', { name: 'Hygiene / Stale Media' }));
		expect(screen.getByText('Reclaimable Storage')).toBeTruthy();
		expect(getSpy).toHaveBeenCalledTimes(1);
		cleanup();
		getSpy.mockClear();

		signInAs('USER');
		render(LibrarySectionDetail, { props: { stats: makeStats([]), recentItems: [] } });
		expect(screen.queryByRole('group', { name: 'Library view mode' })).toBeNull();
		expect(screen.queryByText(/Hygiene/)).toBeNull();
		expect(screen.getByText('Growth History')).toBeTruthy();
		expect(getSpy).not.toHaveBeenCalled();
	});

	it("drops the previous section's stale report when another section is selected, even if it answers last", async () => {
		const answer = new Map<string, (report: unknown) => void>();
		vi.spyOn(api, 'GET').mockImplementation(((_path: string, init: { params: { path: { id: string } } }) =>
			new Promise((resolve) => {
				answer.set(init.params.path.id, (items) =>
					resolve({
						data: {
							sectionKey: init.params.path.id,
							unwatchedDays: 180,
							sort: 'size_desc',
							totalStaleCount: 1,
							totalReclaimableBytes: 1_000,
							neverPlayedCount: 1,
							items
						},
						response: new Response(null, { status: 200 })
					})
				);
			})) as never);
		const staleItem = (title: string) => [
			{ ratingKey: title, sectionKey: '1', mediaType: 'movie', title, addedAtMs: 1, playCount: 0, fileSizeBytes: 1_000 }
		];
		const section = (key: string) => {
			const stats = makeStats([]);
			return { ...stats, section: { ...stats.section, id: key, sectionKey: key, title: `Section ${key}` } };
		};

		const { rerender } = render(LibrarySectionDetail, { props: { stats: section('1'), recentItems: [] } });
		await fireEvent.click(screen.getByRole('button', { name: 'Hygiene / Stale Media' }));
		answer.get('1')!(staleItem('Old Movie'));
		expect(await screen.findByRole('button', { name: /Old Movie/ })).toBeTruthy();

		await rerender({ stats: section('2'), recentItems: [] });
		expect(screen.queryByRole('button', { name: /Old Movie/ })).toBeNull();
		expect(screen.getByText('Analyzing library hygiene…')).toBeTruthy();

		// Section 3 is selected before section 2 has answered
		await rerender({ stats: section('3'), recentItems: [] });
		answer.get('3')!(staleItem('Third Movie'));
		expect(await screen.findByRole('button', { name: /Third Movie/ })).toBeTruthy();
		answer.get('2')!(staleItem('Second Movie'));
		await new Promise((resolve) => setTimeout(resolve));
		expect(screen.getByRole('button', { name: /Third Movie/ })).toBeTruthy();
		expect(screen.queryByRole('button', { name: /Second Movie/ })).toBeNull();
	});
});
