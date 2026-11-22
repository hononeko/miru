import { describe, expect, it } from 'vitest';
import {
	formatBandwidthKbps,
	formatBytes,
	formatClockMs,
	formatDurationSec,
	formatMediaTitle,
	formatRelativeTime,
	getDecisionMeta,
	safeInitial
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
});
