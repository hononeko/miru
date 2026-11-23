import { api, MiruApiError, unwrap } from '$api/client';
import type {
	AnalyticsMetricPreference,
	DateFormatPreference,
	StatsRange,
	ThemePreference,
	TimeFormatPreference,
	UpdateUserPreferencesRequest,
	UserPreferencesResponse
} from '$api/types';
import { themeStore } from './theme.svelte';

const STORAGE_KEY = 'miru-user-preferences';

const ALLOWED_THEMES = new Set<ThemePreference>(['system', 'dark', 'light']);
const ALLOWED_TIME_FORMATS = new Set<TimeFormatPreference>(['24h', '12h']);
const ALLOWED_DATE_FORMATS = new Set<DateFormatPreference>(['iso', 'mdy', 'dmy']);
const ALLOWED_STATS_RANGES = new Set<StatsRange>(['7d', '30d', '90d', 'all']);
const ALLOWED_ANALYTICS_METRICS = new Set<AnalyticsMetricPreference>(['plays', 'duration']);

export const DEFAULT_USER_PREFERENCES: UserPreferencesResponse = {
	theme: 'system',
	timeFormat: '24h',
	dateFormat: 'iso',
	defaultStatsRange: '30d',
	relativeTimestamps: true,
	analyticsMetric: 'plays',
	updatedAtMs: null
};

export function normalizePreferences(raw: Partial<UserPreferencesResponse> | null | undefined): UserPreferencesResponse {
	return {
		theme: raw?.theme && ALLOWED_THEMES.has(raw.theme) ? raw.theme : DEFAULT_USER_PREFERENCES.theme,
		timeFormat:
			raw?.timeFormat && ALLOWED_TIME_FORMATS.has(raw.timeFormat)
				? raw.timeFormat
				: DEFAULT_USER_PREFERENCES.timeFormat,
		dateFormat:
			raw?.dateFormat && ALLOWED_DATE_FORMATS.has(raw.dateFormat)
				? raw.dateFormat
				: DEFAULT_USER_PREFERENCES.dateFormat,
		defaultStatsRange:
			raw?.defaultStatsRange && ALLOWED_STATS_RANGES.has(raw.defaultStatsRange)
				? raw.defaultStatsRange
				: DEFAULT_USER_PREFERENCES.defaultStatsRange,
		relativeTimestamps:
			typeof raw?.relativeTimestamps === 'boolean'
				? raw.relativeTimestamps
				: DEFAULT_USER_PREFERENCES.relativeTimestamps,
		analyticsMetric:
			raw?.analyticsMetric && ALLOWED_ANALYTICS_METRICS.has(raw.analyticsMetric)
				? raw.analyticsMetric
				: DEFAULT_USER_PREFERENCES.analyticsMetric,
		updatedAtMs: typeof raw?.updatedAtMs === 'number' ? raw.updatedAtMs : null
	};
}

function getStorage(): Storage | null {
	if (typeof window === 'undefined') return null;
	const ls = window.localStorage;
	if (!ls || typeof ls.getItem !== 'function' || typeof ls.setItem !== 'function') return null;
	return ls;
}

function readCachedPreferences(): UserPreferencesResponse | null {
	const storage = getStorage();
	if (!storage) return null;
	try {
		const raw = storage.getItem(STORAGE_KEY);
		if (!raw) return null;
		const parsed = JSON.parse(raw) as Partial<UserPreferencesResponse>;
		return normalizePreferences(parsed);
	} catch {
		return null;
	}
}

function writeCachedPreferences(prefs: UserPreferencesResponse): void {
	const storage = getStorage();
	if (!storage) return;
	try {
		storage.setItem(STORAGE_KEY, JSON.stringify(prefs));
	} catch {
		// Ignore storage access errors in restricted webviews
	}
}

export class PreferencesStore {
	prefs = $state<UserPreferencesResponse>(DEFAULT_USER_PREFERENCES);
	modalOpen = $state(false);
	loading = $state(false);
	saving = $state(false);
	error = $state<string | null>(null);
	loadedAccountId = $state<string | null>(null);

	constructor(private readonly theme: { set: (mode: ThemePreference) => void } = themeStore) {
		const cached = readCachedPreferences();
		if (cached) {
			this.prefs = cached;
			this.theme.set(cached.theme);
		}
	}

	openModal(): void {
		this.error = null;
		this.modalOpen = true;
	}

	closeModal(): void {
		this.modalOpen = false;
		this.error = null;
	}

	clearLoadedAccount(): void {
		this.loadedAccountId = null;
		this.modalOpen = false;
		this.error = null;
	}

	async loadForAccount(accountId: string): Promise<UserPreferencesResponse> {
		this.loading = true;
		this.error = null;
		try {
			const res = unwrap(await api.GET('/api/v1/auth/me/preferences'));
			const normalized = normalizePreferences(res);
			this.prefs = normalized;
			this.loadedAccountId = accountId;
			writeCachedPreferences(normalized);
			this.theme.set(normalized.theme);
			return normalized;
		} catch (e) {
			this.error = e instanceof MiruApiError ? e.detail : 'Failed to load user preferences';
			return this.prefs;
		} finally {
			this.loading = false;
		}
	}

	async save(input: UpdateUserPreferencesRequest): Promise<UserPreferencesResponse> {
		this.saving = true;
		this.error = null;
		try {
			const body: UpdateUserPreferencesRequest = {
				theme: input.theme,
				timeFormat: input.timeFormat,
				dateFormat: input.dateFormat,
				defaultStatsRange: input.defaultStatsRange,
				relativeTimestamps: input.relativeTimestamps,
				analyticsMetric: input.analyticsMetric
			};
			const res = unwrap(
				await api.PUT('/api/v1/auth/me/preferences', {
					body
				})
			);
			const normalized = normalizePreferences(res);
			this.prefs = normalized;
			writeCachedPreferences(normalized);
			this.theme.set(normalized.theme);
			return normalized;
		} catch (e) {
			this.error = e instanceof MiruApiError ? e.detail : 'Failed to save user preferences';
			throw e;
		} finally {
			this.saving = false;
		}
	}
}

export const preferencesStore = new PreferencesStore();
