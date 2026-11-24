<script lang="ts">
	import type {
		AnalyticsMetricPreference,
		HeatmapCellDto,
		HeatmapRange,
		HeatmapStatsResponse
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import { cn, formatDurationSec } from '$lib/utils';

	type Props = {
		heatmap: HeatmapStatsResponse | null;
		range: HeatmapRange;
		metric?: AnalyticsMetricPreference;
		loading?: boolean;
		onchangeRange: (range: HeatmapRange) => void;
	};

	let { heatmap, range, metric = 'plays', loading = false, onchangeRange }: Props = $props();

	const RANGE_OPTIONS: ReadonlyArray<{ value: HeatmapRange; label: string }> = [
		{ value: '30d', label: '30d' },
		{ value: '90d', label: '90d' },
		{ value: '365d', label: '365d' },
		{ value: 'all', label: 'All' }
	];

	const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
	const HOUR_TICKS = [0, 3, 6, 9, 12, 15, 18, 21];

	let activeCell = $state<HeatmapCellDto | null>(null);

	const rowsByDay = $derived.by(() => {
		const map = new Map<string, HeatmapCellDto>();
		for (const cell of heatmap?.cells ?? []) {
			map.set(`${cell.dayOfWeek}:${cell.hour}`, cell);
		}
		return DAY_LABELS.map((dayName, dayOfWeek) => ({
			dayOfWeek,
			dayName,
			cells: Array.from({ length: 24 }, (_, hour) => {
				return (
					map.get(`${dayOfWeek}:${hour}`) ?? {
						dayOfWeek,
						dayName,
						hour,
						playCount: 0,
						watchDurationSec: 0
					}
				);
			})
		}));
	});

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
					Viewing Heatmap ({range === 'all' ? 'All' : range})
				</h2>
				{#if heatmap && heatmap.peakDayName && heatmap.peakHour != null}
					<Badge tone="cyan" mono>
						Peak {heatmap.peakDayName} {String(heatmap.peakHour).padStart(2, '0')}:00 UTC
					</Badge>
				{/if}
			</div>
			<p class="mt-0.5 text-[11px] text-fg-muted">
				7 × 24 UTC intensity by {metric === 'duration' ? 'playback duration' : 'play count'} · Hover or tap any cell for details
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

	<!-- Hour axis labels -->
	<div class="flex flex-col gap-1">
		<div class="flex items-center gap-1.5">
			<span class="w-7 shrink-0 font-mono text-[9px] text-fg-muted">UTC</span>
			<div class="grid flex-1 grid-cols-24 gap-[2px] sm:gap-1">
				{#each Array.from({ length: 24 }, (_, h) => h) as hour (hour)}
					<span class="text-center font-mono text-[8px] text-fg-muted sm:text-[9px]">
						{HOUR_TICKS.includes(hour) ? String(hour).padStart(2, '0') : ''}
					</span>
				{/each}
			</div>
		</div>

		<!-- 7 x 24 Grid -->
		{#each rowsByDay as row (row.dayOfWeek)}
			<div class="flex items-center gap-1.5">
				<span class="w-7 shrink-0 font-mono text-[10px] font-medium text-fg-secondary">
					{row.dayName}
				</span>
				<div class="grid flex-1 grid-cols-24 gap-[2px] sm:gap-1">
					{#each row.cells as cell (cell.hour)}
						{@const isSelected =
							activeCell?.dayOfWeek === cell.dayOfWeek && activeCell?.hour === cell.hour}
						<button
							type="button"
							aria-label={`${cell.dayName} ${formatHourSlot(cell.hour)}: ${cell.playCount} plays, ${formatDurationSec(cell.watchDurationSec)}`}
							title={`${cell.dayName} ${formatHourSlot(cell.hour)} · ${cell.playCount} ${cell.playCount === 1 ? 'play' : 'plays'} (${formatDurationSec(cell.watchDurationSec)})`}
							onmouseenter={() => (activeCell = cell)}
							onfocus={() => (activeCell = cell)}
							onclick={() => (activeCell = cell)}
							class={cn(
								'h-4 w-full rounded-[3px] border transition-transform sm:h-5 sm:rounded-xs cursor-pointer focus-visible:outline-1 focus-visible:outline-cyan',
								cellIntensityClass(cell),
								isSelected && 'ring-1 ring-fg-primary scale-105'
							)}
						></button>
					{/each}
				</div>
			</div>
		{/each}
	</div>

	<!-- Active cell tooltip readout + Intensity legend -->
	<div class="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-elevated px-3 py-2 text-xs">
		<div class="font-mono text-[11px] text-fg-primary tabular-nums" data-testid="heatmap-cell-readout">
			{#if activeCell}
				<span class="font-semibold text-cyan-hi">
					{activeCell.dayName} · {formatHourSlot(activeCell.hour)}
				</span>
				<span class="text-fg-secondary">
					— {activeCell.playCount} {activeCell.playCount === 1 ? 'play' : 'plays'} · {formatDurationSec(activeCell.watchDurationSec)}
				</span>
			{:else if loading && !heatmap}
				<span class="text-fg-muted">Loading heatmap…</span>
			{:else if heatmap && heatmap.totalPlays > 0}
				<span class="text-fg-secondary">
					{heatmap.totalPlays.toLocaleString()} plays · {formatDurationSec(heatmap.totalWatchDurationSec)} total watch time
				</span>
			{:else}
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
