<script lang="ts">
	import type {
		AnalyticsMetricPreference,
		HeatmapCellDto,
		HeatmapRange,
		HeatmapStatsResponse
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { cn, formatCalendarDate, formatCalendarMonthDay, formatDurationSec } from '$lib/utils';

	type Props = {
		heatmap: HeatmapStatsResponse | null;
		range: HeatmapRange;
		metric?: AnalyticsMetricPreference;
		loading?: boolean;
		onchangeRange: (range: HeatmapRange) => void;
	};

	let { heatmap, range, metric = 'plays', loading = false, onchangeRange }: Props = $props();

	const RANGE_OPTIONS: ReadonlyArray<{ value: HeatmapRange; label: string }> = [
		{ value: '7d', label: '7d' },
		{ value: '30d', label: '30d' }
	];

	const HOUR_TICKS = [0, 3, 6, 9, 12, 15, 18, 21];

	let activeCell = $state<HeatmapCellDto | null>(null);
	let gridEl: HTMLDivElement | undefined = $state();

	const dateFormat = $derived(preferencesStore.prefs.dateFormat);

	// Newest day first, so today stays next to the hour axis
	const days = $derived.by(() => {
		const byDate = new Map<string, HeatmapCellDto[]>();
		for (const cell of heatmap?.cells ?? []) {
			const row = byDate.get(cell.date);
			if (row) row.push(cell);
			else byDate.set(cell.date, [cell]);
		}
		return Array.from(byDate, ([date, cells]) => ({ date, dayName: cells[0].dayName, cells })).reverse();
	});
	const compact = $derived(days.length > 7);

	$effect(() => {
		void heatmap;
		activeCell = null;
	});

	const ARROW_STEPS: Record<string, { row: number; hour: number }> = {
		ArrowUp: { row: -1, hour: 0 },
		ArrowDown: { row: 1, hour: 0 },
		ArrowLeft: { row: 0, hour: -1 },
		ArrowRight: { row: 0, hour: 1 }
	};

	function moveFocus(e: KeyboardEvent, row: number, hour: number) {
		const step = ARROW_STEPS[e.key];
		if (!step) return;
		e.preventDefault();
		gridEl
			?.querySelector<HTMLElement>(`[data-row="${row + step.row}"][data-hour="${hour + step.hour}"]`)
			?.focus();
	}

	function cellDescription(cell: HeatmapCellDto): string {
		return `${cell.dayName} ${formatCalendarDate(cell.date, dateFormat)} ${formatHourSlot(cell.hour)}: ${cell.playCount} ${cell.playCount === 1 ? 'play' : 'plays'}, ${formatDurationSec(cell.watchDurationSec)}`;
	}

	function formatHourSlot(hour: number): string {
		const h = String(hour).padStart(2, '0');
		return `${h}:00–${h}:59 UTC`;
	}

	function cellIntensityClass(cell: HeatmapCellDto): string {
		const val = metric === 'duration' ? cell.watchDurationSec : cell.playCount;
		const maxVal =
			metric === 'duration'
				? (heatmap?.maxCellWatchDurationSec ?? 0)
				: (heatmap?.maxCellPlays ?? 0);
		if (val <= 0 || maxVal <= 0) {
			return 'bg-subtle/60 border-transparent hover:border-border-strong';
		}
		const ratio = val / maxVal;
		if (ratio > 0.75) {
			return 'bg-primary border-primary-text text-primary-fg';
		}
		if (ratio > 0.5) {
			return 'bg-cyan border-cyan-hi';
		}
		if (ratio > 0.25) {
			return 'bg-cyan/50 border-cyan/60';
		}
		return 'bg-cyan-soft border-cyan/40';
	}
</script>

<div class="flex flex-col gap-3.5 rounded-xl border border-border bg-surface p-4">
	<div class="flex flex-wrap items-center justify-between gap-2">
		<div>
			<div class="flex items-center gap-2">
				<h2 class="font-heading text-sm font-semibold text-fg-primary">
					Viewing Heatmap ({range})
				</h2>
				{#if heatmap?.peakDate && heatmap.peakHour != null}
					<Badge tone="cyan" mono>
						Peak {heatmap.peakDayName} {formatCalendarMonthDay(heatmap.peakDate, dateFormat)} {String(heatmap.peakHour).padStart(2, '0')}:00 UTC
					</Badge>
				{/if}
			</div>
			<p class="mt-0.5 text-[11px] text-fg-muted">
				Each day by UTC hour · intensity by {metric === 'duration' ? 'playback duration' : 'play count'} · Hover or tap any cell for details
			</p>
		</div>

		<div
			role="group"
			aria-label="Heatmap time range"
			class="inline-flex items-center gap-0.5 rounded-lg border border-border bg-elevated p-0.5"
		>
			{#each RANGE_OPTIONS as opt (opt.value)}
				{@const selected = range === opt.value}
				<button
					type="button"
					aria-pressed={selected}
					onclick={() => onchangeRange(opt.value)}
					class={cn(
						'rounded-md px-2 py-1 font-mono text-[11px] font-medium transition-colors cursor-pointer',
						selected
							? 'bg-cyan-soft text-cyan-hi border border-cyan/60 font-semibold'
							: 'text-fg-secondary hover:text-fg-primary border border-transparent'
					)}
				>
					{opt.label}
				</button>
			{/each}
		</div>
	</div>

	{#if days.length === 0}
		<div class="flex h-40 items-center justify-center rounded-lg bg-elevated/50 text-xs text-fg-muted">
			{loading ? 'Loading heatmap…' : 'No heatmap data available'}
		</div>
	{:else}
		<div bind:this={gridEl} class={cn('flex flex-col', compact ? 'gap-[2px]' : 'gap-1')}>
			<!-- Hour axis labels -->
			<div class="flex items-center gap-1.5">
				<span class="w-16 shrink-0 font-mono text-[9px] text-fg-muted">UTC</span>
				<div class="grid flex-1 grid-cols-24 gap-[2px] sm:gap-1">
					{#each Array.from({ length: 24 }, (_, h) => h) as hour (hour)}
						<span class="text-center font-mono text-[8px] text-fg-muted sm:text-[9px]">
							{HOUR_TICKS.includes(hour) ? String(hour).padStart(2, '0') : ''}
						</span>
					{/each}
				</div>
			</div>

			<!-- One row per calendar date -->
			{#each days as day, row (day.date)}
				<div class="flex items-center gap-1.5" data-testid="heatmap-day">
					<span class="w-16 shrink-0 whitespace-nowrap font-mono text-[10px] text-fg-muted tabular-nums">
						<span class="font-medium text-fg-secondary">{day.dayName}</span>
						{formatCalendarMonthDay(day.date, dateFormat)}
					</span>
					<div class="grid flex-1 grid-cols-24 gap-[2px] sm:gap-1">
						{#each day.cells as cell (cell.hour)}
							{@const isSelected = activeCell?.date === cell.date && activeCell?.hour === cell.hour}
							{@const isTabStop = activeCell ? isSelected : row === 0 && cell.hour === 0}
							<button
								type="button"
								data-row={row}
								data-hour={cell.hour}
								tabindex={isTabStop ? 0 : -1}
								aria-label={cellDescription(cell)}
								title={cellDescription(cell)}
								onmouseenter={() => (activeCell = cell)}
								onfocus={() => (activeCell = cell)}
								onclick={() => (activeCell = cell)}
								onkeydown={(e) => moveFocus(e, row, cell.hour)}
								class={cn(
									'w-full rounded-[3px] border transition-transform sm:rounded-xs cursor-pointer focus-visible:outline-1 focus-visible:outline-cyan',
									compact ? 'h-3 sm:h-3.5' : 'h-4 sm:h-5',
									cellIntensityClass(cell),
									isSelected && 'ring-1 ring-fg-primary scale-105'
								)}
							></button>
						{/each}
					</div>
				</div>
			{/each}
		</div>
	{/if}

	<!-- Active cell tooltip readout + Intensity legend -->
	<div class="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-elevated px-3 py-2 text-xs">
		<div class="font-mono text-[11px] text-fg-primary tabular-nums" data-testid="heatmap-cell-readout">
			{#if activeCell}
				<span class="font-semibold text-cyan-hi">
					{activeCell.dayName} {formatCalendarDate(activeCell.date, dateFormat)} · {formatHourSlot(activeCell.hour)}
				</span>
				<span class="text-fg-secondary">
					— {activeCell.playCount} {activeCell.playCount === 1 ? 'play' : 'plays'} · {formatDurationSec(activeCell.watchDurationSec)}
				</span>
			{:else if heatmap && heatmap.totalPlays > 0}
				<span class="text-fg-secondary">
					{heatmap.totalPlays.toLocaleString()} plays · {formatDurationSec(heatmap.totalWatchDurationSec)} total watch time
				</span>
			{:else if heatmap}
				<span class="text-fg-muted">No playback activity recorded in this window</span>
			{/if}
		</div>

		<div class="flex items-center gap-1.5 font-mono text-[10px] text-fg-muted">
			<span>Less</span>
			<span class="h-2.5 w-2.5 rounded-[2px] bg-subtle/60"></span>
			<span class="h-2.5 w-2.5 rounded-[2px] border border-cyan/40 bg-cyan-soft"></span>
			<span class="h-2.5 w-2.5 rounded-[2px] bg-cyan/50"></span>
			<span class="h-2.5 w-2.5 rounded-[2px] bg-cyan"></span>
			<span class="h-2.5 w-2.5 rounded-[2px] bg-primary"></span>
			<span>Peak</span>
		</div>
	</div>
</div>
