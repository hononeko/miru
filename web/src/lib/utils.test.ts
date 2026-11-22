import { describe, expect, it } from 'vitest';
import {
	formatBandwidthKbps,
	formatBytes,
	formatClockMs,
	formatDateTime,
	formatDurationSec,
	formatMediaTitle,
	formatPreferencePreview,
	formatRelativeTime,
	formatShortDateTime,
	getDecisionMeta,
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
});
