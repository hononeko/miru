import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';
import type { ActiveStreamDto, DateFormatPreference, TimeFormatPreference } from '$api/types';

export interface UserDateTimePrefs {
	timeFormat?: TimeFormatPreference;
	dateFormat?: DateFormatPreference;
	relativeTimestamps?: boolean;
}

const MONTH_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const ISO_CALENDAR_DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

function pad2(n: number): string {
	return String(n).padStart(2, '0');
}

function parseIsoCalendarDate(isoDate: string): { year: string; month: number; day: number } | null {
	const match = ISO_CALENDAR_DATE_RE.exec(isoDate.trim());
	if (!match) return null;
	const month = Number(match[2]);
	const day = Number(match[3]);
	if (month < 1 || month > 12 || day < 1 || day > 31) return null;
	return { year: match[1], month, day };
}

export function formatCalendarDate(isoDate: string, dateFormat: DateFormatPreference = 'iso'): string {
	const parsed = parseIsoCalendarDate(isoDate);
	if (!parsed) return isoDate;
	if (dateFormat === 'mdy') {
		return `${MONTH_SHORT[parsed.month - 1]} ${parsed.day}, ${parsed.year}`;
	}
	if (dateFormat === 'dmy') {
		return `${pad2(parsed.day)}/${pad2(parsed.month)}/${parsed.year}`;
	}
	return `${parsed.year}-${pad2(parsed.month)}-${pad2(parsed.day)}`;
}

export function getCalendarDateAxisParts(
	isoDate: string,
	prevIsoDate?: string | null
): { dayLabel: string; monthLabel?: string } {
	const parsed = parseIsoCalendarDate(isoDate);
	if (!parsed) {
		return { dayLabel: isoDate.slice(8, 10) || isoDate };
	}
	const dayLabel = pad2(parsed.day);
	const monthShort = MONTH_SHORT[parsed.month - 1];
	const prev = prevIsoDate ? parseIsoCalendarDate(prevIsoDate) : null;
	if (!prev) {
		return { dayLabel, monthLabel: monthShort };
	}
	if (parsed.year !== prev.year) {
		return { dayLabel, monthLabel: `${monthShort} '${parsed.year.slice(2)}` };
	}
	if (parsed.month !== prev.month) {
		return { dayLabel, monthLabel: monthShort };
	}
	return { dayLabel };
}

function formatDatePart(d: Date, dateFormat: DateFormatPreference = 'iso'): string {
	const year = d.getFullYear();
	const month = d.getMonth();
	const day = d.getDate();
	if (dateFormat === 'mdy') {
		return `${MONTH_SHORT[month]} ${day}, ${year}`;
	}
	if (dateFormat === 'dmy') {
		return `${pad2(day)}/${pad2(month + 1)}/${year}`;
	}
	return `${year}-${pad2(month + 1)}-${pad2(day)}`;
}

function formatTimePart(d: Date, timeFormat: TimeFormatPreference = '24h'): string {
	const hours = d.getHours();
	const minutes = pad2(d.getMinutes());
	if (timeFormat === '12h') {
		const period = hours >= 12 ? 'PM' : 'AM';
		const hour12 = hours % 12 === 0 ? 12 : hours % 12;
		return `${hour12}:${minutes} ${period}`;
	}
	return `${pad2(hours)}:${minutes}`;
}

export function cn(...inputs: ClassValue[]): string {
	return twMerge(clsx(inputs));
}

export function streamKey(stream: Pick<ActiveStreamDto, 'serverId' | 'sessionKey'> & { media: { ratingKey: string } }): string {
	return `${stream.serverId}:${stream.sessionKey}:${stream.media.ratingKey}`;
}

export function formatDurationSec(totalSeconds: number | null | undefined): string {
	if (!totalSeconds || totalSeconds <= 0) return '0m';
	const sec = Math.floor(totalSeconds);
	const hours = Math.floor(sec / 3600);
	const minutes = Math.floor((sec % 3600) / 60);
	const seconds = sec % 60;
	if (hours > 0) return `${hours}h ${minutes}m`;
	if (minutes > 0) return seconds > 0 ? `${minutes}m ${seconds}s` : `${minutes}m`;
	return `${seconds}s`;
}

export function formatClockMs(ms: number | null | undefined): string {
	if (!ms || ms <= 0) return '0:00';
	const totalSec = Math.floor(ms / 1000);
	const h = Math.floor(totalSec / 3600);
	const m = Math.floor((totalSec % 3600) / 60);
	const s = totalSec % 60;
	const pad = (n: number) => String(n).padStart(2, '0');
	return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

export function formatBytes(bytes: number | null | undefined): string {
	if (!bytes || bytes <= 0) return '0 B';
	const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
	let idx = 0;
	let value = bytes;
	while (value >= 1024 && idx < units.length - 1) {
		value /= 1024;
		idx++;
	}
	return `${value >= 100 ? value.toFixed(0) : value.toFixed(1)} ${units[idx]}`;
}

/**
 * A statistic's percentage as a label, e.g. a share of plays or an average completion. A value above zero but
 * under 1% reads "<1%", since a figure like 0.1% only suggests precision the stat does not have.
 */
export function formatStatPercent(value: number | null | undefined, decimals = 1): string {
	if (value == null || !Number.isFinite(value) || value <= 0) return `${(0).toFixed(decimals)}%`;
	if (value < 1) return '<1%';
	return `${value.toFixed(decimals)}%`;
}

/** Width of a bar showing a percentage, proportional to it and clamped to 0–100%. */
export function statBarWidth(value: number | null | undefined): string {
	if (value == null || !Number.isFinite(value) || value <= 0) return '0%';
	return `${Math.min(value, 100)}%`;
}

export function formatBandwidthKbps(kbps: number | null | undefined): string {
	if (!kbps || kbps <= 0) return '0 Mbps';
	if (kbps >= 1000) return `${(kbps / 1000).toFixed(1)} Mbps`;
	return `${Math.round(kbps)} kbps`;
}

export function formatDateTime(
	timestampMs: number | null | undefined,
	prefs?: UserDateTimePrefs
): string {
	if (!timestampMs) return '—';
	const d = new Date(timestampMs);
	return `${formatDatePart(d, prefs?.dateFormat)} ${formatTimePart(d, prefs?.timeFormat)}`;
}

export function formatRelativeTime(
	timestampMs: number | null | undefined,
	nowMsOrPrefs: number | UserDateTimePrefs = Date.now(),
	maybePrefs?: UserDateTimePrefs
): string {
	if (!timestampMs) return 'Never';
	const nowMs = typeof nowMsOrPrefs === 'number' ? nowMsOrPrefs : Date.now();
	const prefs = typeof nowMsOrPrefs === 'object' ? nowMsOrPrefs : maybePrefs;
	if (prefs?.relativeTimestamps === false) {
		return formatDateTime(timestampMs, prefs);
	}
	const diffSec = Math.max(0, Math.floor((nowMs - timestampMs) / 1000));
	if (diffSec < 60) return `${diffSec}s ago`;
	const diffMin = Math.floor(diffSec / 60);
	if (diffMin < 60) return `${diffMin}m ago`;
	const diffHr = Math.floor(diffMin / 60);
	if (diffHr < 24) return `${diffHr}h ago`;
	const diffDays = Math.floor(diffHr / 24);
	if (diffDays < 30) return `${diffDays}d ago`;
	return formatDatePart(new Date(timestampMs), prefs?.dateFormat);
}

export function formatShortDateTime(
	timestampMs: number | null | undefined,
	prefs?: UserDateTimePrefs,
	nowMs = Date.now()
): string {
	if (!timestampMs) return '—';
	const d = new Date(timestampMs);
	const now = new Date(nowMs);
	const sameDay =
		d.getFullYear() === now.getFullYear() &&
		d.getMonth() === now.getMonth() &&
		d.getDate() === now.getDate();
	if (sameDay) {
		return `Today ${formatTimePart(d, prefs?.timeFormat)}`;
	}
	return formatDatePart(d, prefs?.dateFormat);
}

export function formatPreferencePreview(prefs: UserDateTimePrefs, sampleMs = Date.now()): string {
	const absolute = formatDateTime(sampleMs, prefs);
	if (prefs.relativeTimestamps !== false) {
		return `${absolute} · ${formatRelativeTime(sampleMs, sampleMs + 5 * 60_000, prefs)}`;
	}
	return absolute;
}

export function formatFullUtc(timestampMs: number | null | undefined): string {
	if (!timestampMs) return '—';
	return new Date(timestampMs).toISOString().replace('T', ' ').slice(0, 19) + ' UTC';
}

export interface DecisionBadgeMeta {
	label: string;
	shortLabel: string;
	tone: 'direct-play' | 'direct-stream' | 'hw-transcode' | 'sw-transcode';
}

export function getDecisionMeta(decision: string | null | undefined, hwTranscode = false): DecisionBadgeMeta {
	const norm = (decision ?? '').toLowerCase();
	if (norm === 'direct_play') {
		return { label: 'Direct Play', shortLabel: 'DIRECT PLAY', tone: 'direct-play' };
	}
	if (norm === 'direct_stream') {
		return { label: 'Direct Stream', shortLabel: 'REMUX', tone: 'direct-stream' };
	}
	if (hwTranscode) {
		return { label: 'HW Transcode', shortLabel: 'HW TRANSCODE', tone: 'hw-transcode' };
	}
	return { label: 'SW Transcode', shortLabel: 'SW TRANSCODE', tone: 'sw-transcode' };
}

export function formatMediaTitle(media: {
	mediaType: string;
	title: string;
	parentTitle?: string | null;
	grandparentTitle?: string | null;
	parentIndex?: number | null;
	mediaIndex?: number | null;
	year?: number | null;
}): { primary: string; secondary: string } {
	if (media.mediaType === 'episode' && media.grandparentTitle) {
		const s = media.parentIndex != null ? `S${String(media.parentIndex).padStart(2, '0')}` : '';
		const e = media.mediaIndex != null ? `E${String(media.mediaIndex).padStart(2, '0')}` : '';
		const code = [s, e].filter(Boolean).join(' · ');
		return {
			primary: media.grandparentTitle,
			secondary: code ? `${code} — ${media.title}` : media.title
		};
	}
	if (media.mediaType === 'track' && media.grandparentTitle) {
		return {
			primary: media.title,
			secondary: [media.grandparentTitle, media.parentTitle].filter(Boolean).join(' · ')
		};
	}
	const typeLabel = media.mediaType.replace('_', ' ');
	return {
		primary: media.title,
		secondary: [media.year, typeLabel.charAt(0).toUpperCase() + typeLabel.slice(1)].filter(Boolean).join(' · ')
	};
}

export function safeInitial(name: string | null | undefined): string {
	const trimmed = (name ?? '').trim();
	return trimmed ? trimmed[0].toUpperCase() : '?';
}

export const MIN_PASSWORD_LENGTH = 12;

export function validatePassword(password: string): string | null {
	const missing: string[] = [];
	if (password.length < MIN_PASSWORD_LENGTH) {
		missing.push(`at least ${MIN_PASSWORD_LENGTH} characters`);
	}
	if (!/[a-z]/.test(password)) {
		missing.push('at least 1 lowercase letter');
	}
	if (!/[A-Z]/.test(password)) {
		missing.push('at least 1 uppercase letter');
	}
	if (!/[0-9]/.test(password)) {
		missing.push('at least 1 digit');
	}
	return missing.length === 0 ? null : `Password must contain ${missing.join(', ')}.`;
}

export type ApiKeyExpiryPreset = 'never' | '7d' | '30d' | '90d' | '1y' | 'custom';
export type ApiKeyLifecycleStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED';

const MILLIS_PER_DAY = 86_400_000;

export const API_KEY_EXPIRY_PRESETS: ReadonlyArray<{ value: ApiKeyExpiryPreset; label: string }> = [
	{ value: 'never', label: 'Never expires' },
	{ value: '7d', label: '7 days' },
	{ value: '30d', label: '30 days' },
	{ value: '90d', label: '90 days' },
	{ value: '1y', label: '1 year (365 days)' },
	{ value: 'custom', label: 'Custom date…' }
];

export function resolveApiKeyExpiresAtMs(
	preset: ApiKeyExpiryPreset,
	customDate = '',
	nowMs = Date.now()
): number | null {
	switch (preset) {
		case 'never':
			return null;
		case '7d':
			return nowMs + 7 * MILLIS_PER_DAY;
		case '30d':
			return nowMs + 30 * MILLIS_PER_DAY;
		case '90d':
			return nowMs + 90 * MILLIS_PER_DAY;
		case '1y':
			return nowMs + 365 * MILLIS_PER_DAY;
		case 'custom': {
			const trimmed = customDate.trim();
			const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(trimmed);
			if (!match) return Number.NaN;
			const year = Number(match[1]);
			const month = Number(match[2]);
			const day = Number(match[3]);
			const target = new Date(year, month - 1, day, 23, 59, 59, 999);
			if (
				target.getFullYear() !== year ||
				target.getMonth() !== month - 1 ||
				target.getDate() !== day
			) {
				return Number.NaN;
			}
			const ts = target.getTime();
			return ts > nowMs ? ts : Number.NaN;
		}
	}
}

export function getApiKeyStatus(
	key: { revokedAtMs?: number | null; expiresAtMs?: number | null },
	nowMs = Date.now()
): ApiKeyLifecycleStatus {
	if (key.revokedAtMs != null) return 'REVOKED';
	if (key.expiresAtMs != null && key.expiresAtMs <= nowMs) return 'EXPIRED';
	return 'ACTIVE';
}

export function formatApiKeyExpiry(
	expiresAtMs: number | null | undefined,
	nowMs = Date.now(),
	prefs?: UserDateTimePrefs
): string {
	if (expiresAtMs == null) return 'Never expires';
	const dateText = formatDatePart(new Date(expiresAtMs), prefs?.dateFormat);
	if (expiresAtMs <= nowMs) {
		if (prefs?.relativeTimestamps === false) {
			return `Expired ${dateText}`;
		}
		return `Expired ${formatRelativeTime(expiresAtMs, nowMs, prefs)}`;
	}
	const diffMs = expiresAtMs - nowMs;
	let countdown: string;
	if (diffMs < 60_000) {
		countdown = 'in <1m';
	} else if (diffMs < 3_600_000) {
		countdown = `in ${Math.ceil(diffMs / 60_000)}m`;
	} else if (diffMs < MILLIS_PER_DAY) {
		countdown = `in ${Math.ceil(diffMs / 3_600_000)}h`;
	} else {
		countdown = `in ${Math.ceil(diffMs / MILLIS_PER_DAY)}d`;
	}
	return `Expires ${dateText} (${countdown})`;
}
