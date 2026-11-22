import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, MiruApiError } from '$api/client';
import type { UserPreferencesResponse } from '$api/types';
import {
	DEFAULT_USER_PREFERENCES,
	normalizePreferences,
	PreferencesStore
} from './preferences.svelte';

function jsonResponse(status: number): Response {
	return new Response(null, { status });
}

function createStorageMock(): Storage {
	const map = new Map<string, string>();
	return {
		get length() {
			return map.size;
		},
		clear: () => map.clear(),
		getItem: (key: string) => map.get(key) ?? null,
		key: (index: number) => Array.from(map.keys())[index] ?? null,
		removeItem: (key: string) => {
			map.delete(key);
		},
		setItem: (key: string, value: string) => {
			map.set(key, String(value));
		}
	};
}

describe('PreferencesStore', () => {
	let storage: Storage;

	beforeEach(() => {
		storage = createStorageMock();
		vi.stubGlobal('localStorage', storage);
		Object.defineProperty(window, 'localStorage', { value: storage, configurable: true });
	});

	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('normalizes invalid preference payloads to defaults', () => {
		const normalized = normalizePreferences({
			theme: 'invalid' as never,
			timeFormat: '99h' as never,
			dateFormat: 'ymd' as never,
			defaultStatsRange: '1y' as never,
			relativeTimestamps: 'yes' as never
		});
		expect(normalized).toEqual(DEFAULT_USER_PREFERENCES);
	});

	it('hydrates cached preferences from localStorage immediately and syncs theme', () => {
		const cached: UserPreferencesResponse = {
			theme: 'light',
			timeFormat: '12h',
			dateFormat: 'mdy',
			defaultStatsRange: '7d',
			relativeTimestamps: false,
			updatedAtMs: 1234
		};
		localStorage.setItem('miru-user-preferences', JSON.stringify(cached));
		const themeSpy = { set: vi.fn() };

		const store = new PreferencesStore(themeSpy);
		expect(store.prefs).toEqual(cached);
		expect(themeSpy.set).toHaveBeenCalledWith('light');
	});

	it('loads server preferences, updates cache & theme, and saves updates via PUT', async () => {
		const themeSpy = { set: vi.fn() };
		const store = new PreferencesStore(themeSpy);
		expect(store.prefs).toEqual(DEFAULT_USER_PREFERENCES);

		const serverPrefs: UserPreferencesResponse = {
			theme: 'dark',
			timeFormat: '12h',
			dateFormat: 'dmy',
			defaultStatsRange: '90d',
			relativeTimestamps: false,
			updatedAtMs: 5_000
		};
		const getSpy = vi.spyOn(api, 'GET').mockResolvedValueOnce({
			data: serverPrefs,
			response: jsonResponse(200)
		} as never);

		await store.loadForAccount('acc-1');
		expect(getSpy).toHaveBeenCalledWith('/api/v1/auth/me/preferences');
		expect(store.loadedAccountId).toBe('acc-1');
		expect(store.prefs).toEqual(serverPrefs);
		expect(themeSpy.set).toHaveBeenCalledWith('dark');
		expect(JSON.parse(localStorage.getItem('miru-user-preferences') || '{}')).toEqual(serverPrefs);

		const savedPrefs: UserPreferencesResponse = {
			theme: 'light',
			timeFormat: '24h',
			dateFormat: 'iso',
			defaultStatsRange: 'all',
			relativeTimestamps: true,
			updatedAtMs: 9_000
		};
		const putSpy = vi.spyOn(api, 'PUT').mockResolvedValueOnce({
			data: savedPrefs,
			response: jsonResponse(200)
		} as never);

		const result = await store.save({
			theme: 'light',
			timeFormat: '24h',
			dateFormat: 'iso',
			defaultStatsRange: 'all',
			relativeTimestamps: true
		});
		expect(putSpy).toHaveBeenCalledWith('/api/v1/auth/me/preferences', {
			body: {
				theme: 'light',
				timeFormat: '24h',
				dateFormat: 'iso',
				defaultStatsRange: 'all',
				relativeTimestamps: true
			}
		});
		expect(result).toEqual(savedPrefs);
		expect(store.prefs).toEqual(savedPrefs);
		expect(themeSpy.set).toHaveBeenCalledWith('light');
		expect(store.saving).toBe(false);
	});

	it('manages modal open/close state and captures save errors', async () => {
		const themeSpy = { set: vi.fn() };
		const store = new PreferencesStore(themeSpy);

		store.openModal();
		expect(store.modalOpen).toBe(true);

		vi.spyOn(api, 'PUT').mockResolvedValueOnce({
			error: { title: 'Validation Error', detail: 'Invalid theme', status: 400 },
			response: jsonResponse(400)
		} as never);

		await expect(
			store.save({
				theme: 'system',
				timeFormat: '24h',
				dateFormat: 'iso',
				defaultStatsRange: '30d',
				relativeTimestamps: true
			})
		).rejects.toBeInstanceOf(MiruApiError);

		expect(store.error).toBe('Invalid theme');
		expect(store.saving).toBe(false);

		store.closeModal();
		expect(store.modalOpen).toBe(false);
		expect(store.error).toBeNull();

		store.openModal();
		store.clearLoadedAccount();
		expect(store.modalOpen).toBe(false);
		expect(store.loadedAccountId).toBeNull();
	});
});
