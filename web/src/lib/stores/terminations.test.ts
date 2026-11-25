import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TERMINATION_OVERDUE_MS, TerminationTracker } from './terminations.svelte';

describe('TerminationTracker', () => {
	beforeEach(() => {
		vi.useFakeTimers();
	});

	afterEach(() => {
		vi.useRealTimers();
	});

	it('is terminating until the stream is no longer active', () => {
		const tracker = new TerminationTracker();
		tracker.start('a', 'Alice — Akira', 'Maintenance');
		tracker.sync(new Set(['a', 'b']));

		expect(tracker.isTerminating('a')).toBe(true);
		expect(tracker.isTerminating('b')).toBe(false);
		expect(tracker.notices.map((n) => n.label)).toEqual(['Alice — Akira']);

		tracker.sync(new Set(['b']));

		expect(tracker.isTerminating('a')).toBe(false);
		expect(tracker.notices).toEqual([]);
		expect(vi.getTimerCount()).toBe(0);
	});

	it('is overdue when the stream stays active, and can be started again', () => {
		const tracker = new TerminationTracker();
		tracker.start('a', 'Alice — Akira', 'Maintenance');

		vi.advanceTimersByTime(TERMINATION_OVERDUE_MS - 1);
		expect(tracker.isTerminating('a')).toBe(true);

		vi.advanceTimersByTime(1);
		expect(tracker.isTerminating('a')).toBe(false);
		expect(tracker.notices[0].overdue).toBe(true);

		tracker.start('a', 'Alice — Akira', 'Again');
		expect(tracker.isTerminating('a')).toBe(true);
		expect(tracker.notices).toHaveLength(1);
		expect(tracker.notices[0]).toMatchObject({ reason: 'Again', overdue: false });
	});

	it('keeps terminating after its notice is dismissed, and reports again when overdue', () => {
		const tracker = new TerminationTracker();
		tracker.start('a', 'Alice — Akira', 'Maintenance');
		tracker.dismiss('a');

		expect(tracker.notices).toEqual([]);
		expect(tracker.isTerminating('a')).toBe(true);

		vi.advanceTimersByTime(TERMINATION_OVERDUE_MS);
		expect(tracker.notices.map((n) => n.overdue)).toEqual([true]);

		tracker.dismiss('a');
		expect(tracker.notices).toEqual([]);
	});

	it('follows several streams independently and stops all timers on dispose', () => {
		const tracker = new TerminationTracker();
		tracker.start('a', 'Alice — Akira', 'x');
		vi.advanceTimersByTime(TERMINATION_OVERDUE_MS / 2);
		tracker.start('b', 'Bob — Heat', 'y');
		vi.advanceTimersByTime(TERMINATION_OVERDUE_MS / 2);

		expect(tracker.notices.map((n) => [n.key, n.overdue])).toEqual([
			['a', true],
			['b', false]
		]);

		tracker.dispose();
		expect(tracker.items).toEqual([]);
		expect(vi.getTimerCount()).toBe(0);
	});
});
