import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { HeatmapCellDto, HeatmapStatsResponse } from '$api/types';
import ViewingHeatmap from './ViewingHeatmap.svelte';

function buildSampleHeatmap(): HeatmapStatsResponse {
	const dayNames = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
	const cells: HeatmapCellDto[] = [];
	for (let d = 0; d < 7; d++) {
		for (let h = 0; h < 24; h++) {
			const isPeak = d === 4 && h === 21;
			cells.push({
				dayOfWeek: d,
				dayName: dayNames[d],
				hour: h,
				playCount: isPeak ? 8 : d === 0 && h === 12 ? 2 : 0,
				watchDurationSec: isPeak ? 14_400 : d === 0 && h === 12 ? 3_600 : 0
			});
		}
	}
	return {
		range: '90d',
		fromMs: 1_700_000_000_000,
		totalPlays: 10,
		totalWatchDurationSec: 18_000,
		maxCellPlays: 8,
		maxCellWatchDurationSec: 14_400,
		peakDayName: 'Fri',
		peakHour: 21,
		cells
	};
}

describe('ViewingHeatmap', () => {
	afterEach(() => {
		cleanup();
	});

	it('renders 7x24 cells, peak badge, range selector, and updates readout on cell click', async () => {
		const onchangeRange = vi.fn();
		const heatmap = buildSampleHeatmap();

		render(ViewingHeatmap, {
			props: {
				heatmap,
				range: '90d',
				loading: false,
				onchangeRange
			}
		});

		expect(screen.getByText('Viewing Heatmap (90d)')).toBeTruthy();
		expect(screen.getByText('Peak Fri 21:00 UTC')).toBeTruthy();

		const peakCellBtn = screen.getByRole('button', {
			name: /Fri 21:00–21:59 UTC: 8 plays/
		});
		await fireEvent.click(peakCellBtn);

		const readout = screen.getByTestId('heatmap-cell-readout');
		expect(readout.textContent).toContain('Fri · 21:00–21:59 UTC');
		expect(readout.textContent).toContain('8 plays');
		expect(readout.textContent).toContain('4h 0m');

		const range365Btn = screen.getByRole('button', { name: '365d' });
		await fireEvent.click(range365Btn);
		expect(onchangeRange).toHaveBeenCalledWith('365d');
	});

	it('renders duration-based intensity subtitle when metric is duration', () => {
		render(ViewingHeatmap, {
			props: {
				heatmap: buildSampleHeatmap(),
				range: '90d',
				metric: 'duration',
				loading: false,
				onchangeRange: vi.fn()
			}
		});

		expect(screen.getByText(/intensity by playback duration/i)).toBeTruthy();
	});
});
