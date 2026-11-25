import { describe, expect, it } from 'vitest';
import {
	formatStatPercent,
	statBarWidth,
	formatApiKeyExpiry,
	formatBandwidthKbps,
	formatBytes,
	formatCalendarDate,
	formatCalendarMonthDay,
	formatClockMs,
	formatDateTime,
	formatDurationSec,
	formatMediaTitle,
	formatPreferencePreview,
	formatRelativeTime,
	formatShortDateTime,
	getApiKeyStatus,
	getCalendarDateAxisParts,
	getDecisionMeta,
	resolveApiKeyExpiresAtMs,
	safeInitial,
	validatePassword
} from './utils';

describe('Miru telemetry formatters', () => {
	it('formats durations across seconds, minutes, and hours', () => {
		expect(formatDurationSec(0)).toBe('0m');
		expect(formatDurationSec(45)).toBe('45s');
		expect(formatDurationSec(300)).toBe('5m');
		expect(formatDurationSec(3342)).toBe('55m 42s');
		expect(formatDurationSec(7260)).toBe('2h 1m');
	});

	it('formats playback clock timestamps', () => {
		expect(formatClockMs(0)).toBe('0:00');
		expect(formatClockMs(65_000)).toBe('1:05');
		expect(formatClockMs(4_092_000)).toBe('1:08:12');
	});

	it('formats byte sizes and bandwidth cleanly', () => {
		expect(formatBytes(0)).toBe('0 B');
		expect(formatBytes(1_073_741_824)).toBe('1.0 GB');
		expect(formatBytes(18.4 * 1024 ** 4)).toBe('18.4 TB');
		expect(formatBandwidthKbps(850)).toBe('850 kbps');
		expect(formatBandwidthKbps(24_800)).toBe('24.8 Mbps');
	});

	it('maps stream decisions and HW transcode flags cleanly', () => {
		expect(getDecisionMeta('direct_play', false).tone).toBe('direct-play');
		expect(getDecisionMeta('direct_stream', false).shortLabel).toBe('REMUX');
		expect(getDecisionMeta('transcode', true).label).toBe('HW Transcode');
		expect(getDecisionMeta('transcode', false).label).toBe('SW Transcode');
	});

	it('formats episode, movie, and track titles', () => {
		expect(
			formatMediaTitle({
				mediaType: 'episode',
				title: "Trojan's Horse",
				grandparentTitle: 'Severance',
				parentIndex: 2,
				mediaIndex: 5
			})
		).toEqual({
			primary: 'Severance',
			secondary: "S02 · E05 — Trojan's Horse"
		});

		expect(
			formatMediaTitle({
				mediaType: 'movie',
				title: 'Dune: Part Two',
				year: 2024
			})
		).toEqual({
			primary: 'Dune: Part Two',
			secondary: '2024 · Movie'
		});
	});

	it('formats relative timestamps and safe initials', () => {
		const now = 1_700_000_000_000;
		expect(formatRelativeTime(null, now)).toBe('Never');
		expect(formatRelativeTime(now - 30_000, now)).toBe('30s ago');
		expect(formatRelativeTime(now - 600_000, now)).toBe('10m ago');
		expect(formatRelativeTime(now - 7_200_000, now)).toBe('2h ago');
		expect(safeInitial('sergiu')).toBe('S');
		expect(safeInitial('   ')).toBe('?');
	});

	it('enforces password length and character complexity rules', () => {
		expect(validatePassword('Short1a')).toContain('at least 12 characters');
		expect(validatePassword('alllowercase12')).toContain('at least 1 uppercase letter');
		expect(validatePassword('ALLUPPERCASE12')).toContain('at least 1 lowercase letter');
		expect(validatePassword('NoDigitsHereAtAll')).toContain('at least 1 digit');
		expect(validatePassword('ValidPassword1')).toBeNull();
	});

	it('formats dates, times, short dates, relative toggle, and preference preview per user prefs', () => {
		// Construct local date/time deterministically so tests do not depend on machine TZ
		const sample = new Date(2026, 10, 22, 21, 5, 0).getTime(); // Nov 22, 2026 21:05 local
		const sameDayLater = new Date(2026, 10, 22, 23, 0, 0).getTime();
		const otherDay = new Date(2026, 10, 25, 10, 0, 0).getTime();

		expect(formatDateTime(null)).toBe('—');
		expect(formatDateTime(sample, { dateFormat: 'iso', timeFormat: '24h' })).toBe('2026-11-22 21:05');
		expect(formatDateTime(sample, { dateFormat: 'mdy', timeFormat: '12h' })).toBe('Nov 22, 2026 9:05 PM');
		expect(formatDateTime(sample, { dateFormat: 'dmy', timeFormat: '12h' })).toBe('22/11/2026 9:05 PM');

		expect(formatShortDateTime(sample, { dateFormat: 'iso', timeFormat: '24h' }, sameDayLater)).toBe('Today 21:05');
		expect(formatShortDateTime(sample, { dateFormat: 'iso', timeFormat: '12h' }, sameDayLater)).toBe('Today 9:05 PM');
		expect(formatShortDateTime(sample, { dateFormat: 'iso', timeFormat: '24h' }, otherDay)).toBe('2026-11-22');
		expect(formatShortDateTime(sample, { dateFormat: 'mdy', timeFormat: '24h' }, otherDay)).toBe('Nov 22, 2026');
		expect(formatShortDateTime(sample, { dateFormat: 'dmy', timeFormat: '24h' }, otherDay)).toBe('22/11/2026');

		expect(
			formatRelativeTime(sample, sample + 120_000, {
				dateFormat: 'mdy',
				timeFormat: '12h',
				relativeTimestamps: true
			})
		).toBe('2m ago');
		expect(
			formatRelativeTime(sample, sample + 120_000, {
				dateFormat: 'mdy',
				timeFormat: '12h',
				relativeTimestamps: false
			})
		).toBe('Nov 22, 2026 9:05 PM');

		expect(
			formatPreferencePreview(
				{ dateFormat: 'iso', timeFormat: '24h', relativeTimestamps: true },
				sample
			)
		).toBe('2026-11-22 21:05 · 5m ago');
		expect(
			formatPreferencePreview(
				{ dateFormat: 'dmy', timeFormat: '12h', relativeTimestamps: false },
				sample
			)
		).toBe('22/11/2026 9:05 PM');
	});

	it('computes API key expiration presets, status badges, and countdown labels', () => {
		const now = new Date(2026, 10, 22, 12, 0, 0).getTime(); // Nov 22, 2026 12:00 local
		const dayMs = 86_400_000;

		expect(resolveApiKeyExpiresAtMs('never', '', now)).toBeNull();
		expect(resolveApiKeyExpiresAtMs('7d', '', now)).toBe(now + 7 * dayMs);
		expect(resolveApiKeyExpiresAtMs('30d', '', now)).toBe(now + 30 * dayMs);
		expect(resolveApiKeyExpiresAtMs('90d', '', now)).toBe(now + 90 * dayMs);
		expect(resolveApiKeyExpiresAtMs('365d', '', now)).toBe(now + 365 * dayMs);

		const customFuture = resolveApiKeyExpiresAtMs('custom', '2026-12-31', now);
		expect(customFuture).toBe(new Date(2026, 11, 31, 23, 59, 59, 999).getTime());
		expect(Number.isNaN(resolveApiKeyExpiresAtMs('custom', '2026-11-20', now))).toBe(true);
		expect(Number.isNaN(resolveApiKeyExpiresAtMs('custom', 'not-a-date', now))).toBe(true);
		expect(Number.isNaN(resolveApiKeyExpiresAtMs('custom', '2026-02-30', now))).toBe(true);

		expect(getApiKeyStatus({ revokedAtMs: null, expiresAtMs: null }, now)).toBe('ACTIVE');
		expect(getApiKeyStatus({ revokedAtMs: null, expiresAtMs: now + 60_000 }, now)).toBe('ACTIVE');
		expect(getApiKeyStatus({ revokedAtMs: null, expiresAtMs: now }, now)).toBe('EXPIRED');
		expect(getApiKeyStatus({ revokedAtMs: null, expiresAtMs: now - 10_000 }, now)).toBe('EXPIRED');
		expect(getApiKeyStatus({ revokedAtMs: now - 5_000, expiresAtMs: now - 10_000 }, now)).toBe('REVOKED');

		expect(formatApiKeyExpiry(null, now)).toBe('Never expires');
		expect(formatApiKeyExpiry(now + 30 * dayMs - 50, now, { dateFormat: 'iso' })).toBe(
			'Expires 2026-12-22 (in 30d)'
		);
		expect(formatApiKeyExpiry(now + 3 * 3_600_000, now, { dateFormat: 'iso' })).toBe(
			'Expires 2026-11-22 (in 3h)'
		);
		expect(formatApiKeyExpiry(now + 20 * 60_000, now, { dateFormat: 'iso' })).toBe(
			'Expires 2026-11-22 (in 20m)'
		);
		expect(formatApiKeyExpiry(now + 15_000, now, { dateFormat: 'iso' })).toBe(
			'Expires 2026-11-22 (in <1m)'
		);
		expect(formatApiKeyExpiry(now - 2 * dayMs, now, { dateFormat: 'iso', relativeTimestamps: true })).toBe(
			'Expired 2d ago'
		);
		expect(formatApiKeyExpiry(now - 2 * dayMs, now, { dateFormat: 'iso', relativeTimestamps: false })).toBe(
			'Expired 2026-11-20'
		);
	});
});

describe('statistic percentages', () => {
	it('shows "<1%" for a share above zero but under 1%, and one decimal otherwise', () => {
		expect(formatStatPercent(0.1)).toBe('<1%');
		expect(formatStatPercent(0.99)).toBe('<1%');
		expect(formatStatPercent(1)).toBe('1.0%');
		expect(formatStatPercent(4.25)).toBe('4.3%');
		expect(formatStatPercent(0.6, 0)).toBe('<1%');
		expect(formatStatPercent(37.5, 0)).toBe('38%');
		expect(formatStatPercent(0)).toBe('0.0%');
		expect(formatStatPercent(null)).toBe('0.0%');
		expect(formatStatPercent(Number.NaN)).toBe('0.0%');
	});

	it('sizes bars in proportion to the percentage, clamped to 0–100%', () => {
		expect(statBarWidth(4.3)).toBe('4.3%');
		expect(statBarWidth(0.1)).toBe('0.1%');
		expect(statBarWidth(120)).toBe('100%');
		expect(statBarWidth(-5)).toBe('0%');
		expect(statBarWidth(undefined)).toBe('0%');
	});

	it('formats calendar ISO dates without timezone shifts and generates axis month markers', () => {
		expect(formatCalendarDate('2026-11-04', 'iso')).toBe('2026-11-04');
		expect(formatCalendarDate('2026-11-04', 'mdy')).toBe('Nov 4, 2026');
		expect(formatCalendarDate('2026-11-04', 'dmy')).toBe('04/11/2026');
		expect(formatCalendarDate('invalid', 'mdy')).toBe('invalid');

		expect(formatCalendarMonthDay('2026-11-04', 'iso')).toBe('11-04');
		expect(formatCalendarMonthDay('2026-11-04', 'mdy')).toBe('Nov 4');
		expect(formatCalendarMonthDay('2026-11-04', 'dmy')).toBe('04/11');
		expect(formatCalendarMonthDay('invalid', 'mdy')).toBe('invalid');

		expect(getCalendarDateAxisParts('2025-12-28')).toEqual({ dayLabel: '28', monthLabel: 'Dec' });
		expect(getCalendarDateAxisParts('2025-12-31', '2025-12-28')).toEqual({ dayLabel: '31' });
		expect(getCalendarDateAxisParts('2026-01-02', '2025-12-31')).toEqual({ dayLabel: '02', monthLabel: "Jan '26" });
		expect(getCalendarDateAxisParts('2026-02-01', '2026-01-02')).toEqual({ dayLabel: '01', monthLabel: 'Feb' });
	});
});
