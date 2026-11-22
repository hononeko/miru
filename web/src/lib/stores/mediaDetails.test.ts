import { describe, expect, it } from 'vitest';
import { MiruApiError } from '$api/client';
import type { MediaDetailsResponse } from '$api/types';
import { MediaDetailsModalStore } from './mediaDetails.svelte';

function makeDetails(ratingKey: string, title: string): MediaDetailsResponse {
	return {
		ratingKey,
		mediaType: 'episode',
		title,
		year: 2013,
		genres: ['Crime', 'Drama'],
		directors: ['Rian Johnson'],
		thumbUrl: `/api/v1/media/${ratingKey}/thumb`,
		artUrl: `/api/v1/media/${ratingKey}/art`,
		series: {
			ratingKey: '54000',
			title: 'Breaking Bad',
			genres: ['Crime', 'Drama'],
			seasonCount: 5,
			episodeCount: 62,
			thumbUrl: '/api/v1/media/54000/thumb'
		},
		season: {
			ratingKey: '54300',
			title: 'Season 5',
			seasonIndex: 5,
			episodeCount: 16,
			currentEpisodeIndex: 14,
			thumbUrl: '/api/v1/media/54300/thumb'
		},
		playSummary: {
			totalPlays: 2,
			uniqueUsers: 1,
			directPlayCount: 2,
			directStreamCount: 0,
			transcodeCount: 0,
			totalWatchDurationSec: 5400
		}
	};
}

function deferred<T>() {
	let resolve!: (value: T) => void;
	let reject!: (reason?: unknown) => void;
	const promise = new Promise<T>((res, rej) => {
		resolve = res;
		reject = rej;
	});
	return { promise, resolve, reject };
}

describe('MediaDetailsModalStore', () => {
	it('starts closed with empty state and ignores blank ratingKey', async () => {
		let calls = 0;
		const store = new MediaDetailsModalStore(async (key) => {
			calls++;
			return makeDetails(key, 'Movie');
		});

		expect(store.isOpen).toBe(false);
		expect(store.ratingKey).toBeNull();
		expect(store.loading).toBe(false);
		expect(store.error).toBeNull();
		expect(store.data).toBeNull();

		await store.open('   ');
		expect(calls).toBe(0);
		expect(store.isOpen).toBe(false);
	});

	it('opens, loads details, and resets on close', async () => {
		const store = new MediaDetailsModalStore(async (key) => makeDetails(key, 'Ozymandias'));

		await store.open('54321');
		expect(store.isOpen).toBe(true);
		expect(store.ratingKey).toBe('54321');
		expect(store.loading).toBe(false);
		expect(store.error).toBeNull();
		expect(store.data?.title).toBe('Ozymandias');
		expect(store.data?.series?.title).toBe('Breaking Bad');
		expect(store.data?.season?.currentEpisodeIndex).toBe(14);

		store.close();
		expect(store.isOpen).toBe(false);
		expect(store.loading).toBe(false);
		expect(store.error).toBeNull();
	});

	it('captures MiruApiError detail and generic Error messages', async () => {
		const apiErrStore = new MediaDetailsModalStore(async () => {
			throw new MiruApiError(404, {
				type: 'https://miru.hononeko.app/errors/not-found',
				title: 'Not Found',
				status: 404,
				detail: "Media '999' was not found"
			});
		});

		await apiErrStore.open('999');
		expect(apiErrStore.isOpen).toBe(true);
		expect(apiErrStore.loading).toBe(false);
		expect(apiErrStore.data).toBeNull();
		expect(apiErrStore.error).toBe("Media '999' was not found");

		const genericErrStore = new MediaDetailsModalStore(async () => {
			throw new Error('Network offline');
		});
		await genericErrStore.open('100');
		expect(genericErrStore.error).toBe('Network offline');
	});

	it('prevents stale responses or errors from overwriting newer requests or closed state', async () => {
		const first = deferred<MediaDetailsResponse>();
		const second = deferred<MediaDetailsResponse>();
		const store = new MediaDetailsModalStore(async (key) => {
			if (key === 'rk-1') return first.promise;
			return second.promise;
		});

		const p1 = store.open('rk-1');
		expect(store.loading).toBe(true);
		expect(store.ratingKey).toBe('rk-1');

		const p2 = store.open('rk-2');
		expect(store.ratingKey).toBe('rk-2');

		second.resolve(makeDetails('rk-2', 'Felina'));
		await p2;
		expect(store.loading).toBe(false);
		expect(store.data?.ratingKey).toBe('rk-2');
		expect(store.data?.title).toBe('Felina');

		// Stale rk-1 resolves or rejects late — must be ignored
		first.reject(new Error('Late timeout'));
		await p1;
		expect(store.loading).toBe(false);
		expect(store.error).toBeNull();
		expect(store.data?.ratingKey).toBe('rk-2');

		// Closing while a request is in flight invalidates that request
		const third = deferred<MediaDetailsResponse>();
		const store2 = new MediaDetailsModalStore(async () => third.promise);
		const p3 = store2.open('rk-3');
		store2.close();
		third.resolve(makeDetails('rk-3', 'Pilot'));
		await p3;
		expect(store2.isOpen).toBe(false);
		expect(store2.data).toBeNull();
	});
});
