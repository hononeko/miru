import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import type { HeatmapCellDto, HeatmapRange, HeatmapStatsResponse, HistoryItemDto } from '$api/types';
import { formatDurationSec } from '$lib/utils';
import ViewingHeatmap from './ViewingHeatmap.svelte';

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const LAST_DAY_MS = Date.UTC(2026, 10, 24); // Tuesday

function buildSampleHeatmap(range: HeatmapRange): HeatmapStatsResponse {
	const dayCount = range === '7d' ? 7 : 30;
	const cells: HeatmapCellDto[] = [];
	for (let d = dayCount - 1; d >= 0; d--) {
		const day = new Date(LAST_DAY_MS - d * 86_400_000);
		const date = day.toISOString().slice(0, 10);
		const dayOfWeek = (day.getUTCDay() + 6) % 7;
		for (let hour = 0; hour < 24; hour++) {
			const isPeak = date === '2026-11-20' && hour === 21;
			cells.push({
				date,
				dayOfWeek,
				dayName: DAY_NAMES[dayOfWeek],
				hour,
				playCount: isPeak ? 8 : 0,
				watchDurationSec: isPeak ? 14_400 : 0
			});
		}
	}
	return {
		range,
		fromMs: LAST_DAY_MS - (dayCount - 1) * 86_400_000,
		totalPlays: 8,
		totalWatchDurationSec: 14_400,
		maxCellPlays: 8,
		maxCellWatchDurationSec: 14_400,
		peakDate: '2026-11-20',
		peakDayName: 'Fri',
		peakHour: 21,
		cells
	};
}

function renderHeatmap(range: HeatmapRange, overrides: Record<string, unknown> = {}) {
	const onchangeRange = vi.fn();
	render(ViewingHeatmap, {
		props: { heatmap: buildSampleHeatmap(range), range, loading: false, onchangeRange, ...overrides }
	});
	return { onchangeRange };
}

function rowLabels(): string[] {
	return screen
		.getAllByTestId('heatmap-day')
		.map((row) => row.firstElementChild?.textContent?.replace(/\s+/g, ' ').trim() ?? '');
}

describe('ViewingHeatmap', () => {
	afterEach(() => {
		cleanup();
	});

	it('shows one dated row per day of the last week, newest first, and describes the clicked cell', async () => {
		const { onchangeRange } = renderHeatmap('7d');

		expect(screen.getByText('Viewing Heatmap (7d)')).toBeTruthy();
		expect(screen.getByText('Peak Fri 11-20 21:00 UTC')).toBeTruthy();
		expect(rowLabels()).toEqual([
			'Tue 11-24',
			'Mon 11-23',
			'Sun 11-22',
			'Sat 11-21',
			'Fri 11-20',
			'Thu 11-19',
			'Wed 11-18'
		]);

		await fireEvent.click(
			screen.getByRole('button', { name: 'Fri 2026-11-20 21:00–21:59 UTC: 8 plays, 4h 0m' })
		);
		const readout = screen.getByTestId('heatmap-cell-readout');
		expect(readout.textContent).toContain('Fri 2026-11-20 · 21:00–21:59 UTC');
		expect(readout.textContent).toContain('8 plays');
		expect(readout.textContent).toContain('4h 0m');

		const ranges = screen.getByRole('group', { name: 'Heatmap time range' });
		expect(Array.from(ranges.querySelectorAll('button'), (b) => b.textContent?.trim())).toEqual(['7d', '30d']);
		await fireEvent.click(screen.getByRole('button', { name: '30d' }));
		expect(onchangeRange).toHaveBeenCalledWith('30d');
	});

	it('shows thirty distinct dated rows for 30d', () => {
		renderHeatmap('30d');
		const labels = rowLabels();
		expect(labels).toHaveLength(30);
		expect(new Set(labels).size).toBe(30);
		expect(labels.at(-1)).toBe('Mon 10-26');
	});

	it('is a single tab stop that arrow keys move through', async () => {
		renderHeatmap('7d');
		const cellAt = (row: number, hour: number) =>
			document.querySelector<HTMLElement>(`[data-row="${row}"][data-hour="${hour}"]`)!;
		const tabStops = () => Array.from(document.querySelectorAll('[data-row][tabindex="0"]'));

		expect(tabStops()).toEqual([cellAt(0, 0)]);

		cellAt(0, 0).focus();
		await fireEvent.keyDown(cellAt(0, 0), { key: 'ArrowRight' });
		await fireEvent.keyDown(cellAt(0, 1), { key: 'ArrowDown' });
		expect(document.activeElement).toBe(cellAt(1, 1));
		expect(tabStops()).toEqual([cellAt(1, 1)]);

		// Nothing beyond the edge to move to
		cellAt(0, 0).focus();
		await fireEvent.keyDown(cellAt(0, 0), { key: 'ArrowUp' });
		expect(document.activeElement).toBe(cellAt(0, 0));
	});

	it('names the metric that shades the cells and a missing heatmap', () => {
		renderHeatmap('7d', { metric: 'duration' });
		expect(screen.getByText(/intensity by playback duration/i)).toBeTruthy();
		cleanup();

		const readout = () => screen.getByTestId('heatmap-cell-readout').textContent?.trim();
		renderHeatmap('7d', { heatmap: null, loading: true });
		expect(screen.getByText('Loading heatmap…')).toBeTruthy();
		expect(readout()).toBe('');
		cleanup();

		// A failed request says nothing about playback either
		renderHeatmap('7d', { heatmap: null });
		expect(screen.getByText('No heatmap data available')).toBeTruthy();
		expect(readout()).toBe('');
		cleanup();

		renderHeatmap('7d', { heatmap: { ...buildSampleHeatmap('7d'), totalPlays: 0 } });
		expect(readout()).toBe('No playback activity recorded in this window');
	});

	it('previews the hovered cell without moving the selection or the tab stop', async () => {
		renderHeatmap('7d');
		const cellAt = (row: number, hour: number) =>
			document.querySelector<HTMLElement>(`[data-row="${row}"][data-hour="${hour}"]`)!;
		const tabStops = () => Array.from(document.querySelectorAll('[data-row][tabindex="0"]'));
		const highlighted = () => Array.from(document.querySelectorAll('[data-row].ring-1'));
		const readout = () => screen.getByTestId('heatmap-cell-readout').textContent;
		const grid = screen.getByRole('group', { name: 'Plays by day and hour' });

		await fireEvent.mouseEnter(cellAt(4, 21));
		expect(readout()).toContain('Fri 2026-11-20 · 21:00–21:59 UTC');
		expect(highlighted()).toEqual([cellAt(4, 21)]);
		expect(tabStops()).toEqual([cellAt(0, 0)]);

		await fireEvent.mouseLeave(grid);
		expect(readout()).toContain('8 plays · 4h 0m total watch time');
		expect(highlighted()).toEqual([]);

		await fireEvent.click(cellAt(1, 5));
		await fireEvent.mouseEnter(cellAt(4, 21));
		expect(tabStops()).toEqual([cellAt(1, 5)]);
		await fireEvent.mouseLeave(grid);
		expect(readout()).toContain('Mon 2026-11-23 · 05:00–05:59 UTC');
		expect(highlighted()).toEqual([cellAt(1, 5)]);

		// Moving by keyboard wins over a mouse left resting on the grid
		await fireEvent.mouseEnter(cellAt(4, 21));
		await fireEvent.keyDown(cellAt(1, 5), { key: 'ArrowRight' });
		expect(readout()).toContain('Mon 2026-11-23 · 06:00–06:59 UTC');
	});
});

function historyItem(id: string, overrides: Partial<HistoryItemDto>): HistoryItemDto {
	return {
		id,
		serverId: 'srv-plex',
		userId: `u-${id}`,
		ratingKey: `rk-${id}`,
		mediaType: 'movie',
		title: `Title ${id}`,
		durationMs: 3_600_000,
		viewOffsetMs: 1_800_000,
		watchedDurationSec: 1_800,
		pausedDurationSec: 0,
		completionPercent: 50,
		isWatched: false,
		streamDecision: 'direct_play',
		hwTranscode: false,
		startedAtMs: Date.UTC(2026, 10, 20, 20, 30),
		stoppedAtMs: Date.UTC(2026, 10, 20, 21, 10),
		...overrides
	} as HistoryItemDto;
}

const historyPage = (items: HistoryItemDto[], totalCount: number) =>
	({
		data: { items, page: 1, pageSize: 5, totalCount, totalPages: Math.ceil(totalCount / 5) },
		response: new Response(null, { status: 200 })
	}) as never;

const peakCell = () => screen.getByRole('button', { name: /21:00–21:59 UTC: 8 plays/ });
const emptyCell = () => screen.getAllByRole('button', { name: /00:00–00:59 UTC: 0 plays/ })[0];
const popover = () => screen.queryByTestId('heatmap-cell-popover');

describe('ViewingHeatmap cell popover', () => {
	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
	});

	it('lists the plays that stopped in a clicked cell, linking to all of them in History', async () => {
		const getSpy = vi.spyOn(api, 'GET').mockResolvedValue(
			historyPage(
				[
					historyItem('ep', {
						mediaType: 'episode',
						title: 'Woe’s Hollow',
						grandparentTitle: 'Severance',
						parentIndex: 2,
						mediaIndex: 4,
						displayName: 'Elena',
						username: 'elena',
						watchedDurationSec: 2_880
					}),
					historyItem('film', { title: 'Dune: Part Two', year: 2024, username: 'omar', watchedDurationSec: 3_960 })
				],
				8
			)
		);
		renderHeatmap('7d', { serverId: 'srv-plex' });

		await fireEvent.click(peakCell());

		const from = '2026-11-20T21:00:00.000Z';
		const to = '2026-11-20T21:59:59.999Z';
		expect(getSpy).toHaveBeenCalledWith('/api/v1/history', {
			params: { query: { from, to, serverId: 'srv-plex', page: 1, pageSize: 5 } }
		});
		const content = await screen.findByTestId('heatmap-cell-popover');
		expect(peakCell().getAttribute('aria-expanded')).toBe('true');
		await waitFor(() => expect(within(content).getByText('Severance')).toBeTruthy());
		expect(within(content).getByText('S02 · E04 — Woe’s Hollow')).toBeTruthy();
		expect(within(content).getByText('Elena')).toBeTruthy();
		expect(within(content).getByText(formatDurationSec(2_880))).toBeTruthy();
		// Without a display name the viewer's username shows
		expect(within(content).getByText('omar')).toBeTruthy();
		expect(within(content).getByText('+6 more')).toBeTruthy();
		expect(within(content).getByText(/8 plays/)).toBeTruthy();

		const link = within(content).getByRole('link', { name: /View all in History/ });
		expect(link.getAttribute('href')).toBe(`/history?${new URLSearchParams({ from, to, serverId: 'srv-plex' })}`);
		expect(link.getAttribute('target')).toBeNull();

		// Clicking the open cell again closes it
		await fireEvent.click(peakCell());
		await waitFor(() => expect(popover()).toBeNull());
		expect(peakCell().getAttribute('aria-expanded')).toBe('false');
	});

	it('shows an empty hour without a request and reports a failed load', async () => {
		const getSpy = vi.spyOn(api, 'GET').mockResolvedValue({
			error: { title: 'Server Error', status: 500 },
			response: new Response(null, { status: 500 })
		} as never);
		renderHeatmap('7d');

		await fireEvent.click(emptyCell());
		const content = await screen.findByTestId('heatmap-cell-popover');
		expect(within(content).getByText('No plays stopped in this hour.')).toBeTruthy();
		expect(within(content).queryByRole('link')).toBeNull();
		expect(getSpy).not.toHaveBeenCalled();

		await fireEvent.click(peakCell());
		await waitFor(() =>
			expect(within(screen.getByTestId('heatmap-cell-popover')).getByRole('alert').textContent).toContain(
				'Could not load the plays in this hour.'
			)
		);
		// No server filter on the dashboard means none on the request
		expect(getSpy.mock.calls[0][1]).toEqual({
			params: { query: expect.objectContaining({ serverId: undefined }) }
		});
	});

	it('closes on Escape and gives the focus back to the cell', async () => {
		vi.spyOn(api, 'GET').mockResolvedValue(historyPage([historyItem('film', {})], 8));
		renderHeatmap('7d');

		const cell = peakCell();
		cell.focus();
		await fireEvent.click(cell);
		await screen.findByTestId('heatmap-cell-popover');

		await fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });
		await waitFor(() => expect(popover()).toBeNull());
		expect(document.activeElement).toBe(cell);
	});

	it('ignores the slower answer for a cell that is no longer open', async () => {
		let answerFirst: (value: unknown) => void = () => {};
		vi.spyOn(api, 'GET').mockImplementationOnce(
			(() => new Promise((resolve) => (answerFirst = resolve))) as never
		);
		renderHeatmap('7d');

		await fireEvent.click(peakCell());
		await fireEvent.click(emptyCell());
		answerFirst(historyPage([historyItem('late', { title: 'Late answer' })], 8));

		const content = await screen.findByTestId('heatmap-cell-popover');
		await waitFor(() => expect(within(content).getByText('No plays stopped in this hour.')).toBeTruthy());
		expect(within(content).queryByText('Late answer')).toBeNull();
	});
});
