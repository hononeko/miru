<script lang="ts">
	import { Popover } from 'bits-ui';
	import { api, unwrap } from '$api/client';
	import type {
		AnalyticsMetricPreference,
		HeatmapCellDto,
		HeatmapRange,
		HeatmapStatsResponse,
		HistoryItemDto
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import { latestRequest } from '$lib/latestRequest';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { cn, formatCalendarDate, formatCalendarMonthDay, formatDurationSec, formatMediaTitle } from '$lib/utils';

	type Props = {
		heatmap: HeatmapStatsResponse | null;
		range: HeatmapRange;
		metric?: AnalyticsMetricPreference;
		loading?: boolean;
		/** The server the heatmap is filtered to, so a cell lists the same plays it counts. */
		serverId?: string | null;
		onchangeRange: (range: HeatmapRange) => void;
	};

	let { heatmap, range, metric = 'plays', loading = false, serverId = null, onchangeRange }: Props = $props();

	/** How many of a cell's plays its popover lists; the rest are a link to History away. */
	const CELL_PLAYS_LIMIT = 5;

	type CellPlays =
		| { status: 'loading' }
		| { status: 'loaded'; items: HistoryItemDto[]; totalCount: number }
		| { status: 'error' };

	const RANGE_OPTIONS: ReadonlyArray<{ value: HeatmapRange; label: string }> = [
		{ value: '7d', label: '7d' },
		{ value: '30d', label: '30d' }
	];

	const HOUR_TICKS = [0, 3, 6, 9, 12, 15, 18, 21];

	// The focused or pressed cell keeps the selection and the tab stop; hovering only previews another one.
	let activeCell = $state<HeatmapCellDto | null>(null);
	let hoveredCell = $state<HeatmapCellDto | null>(null);
	let gridEl: HTMLDivElement | undefined = $state();

	const shownCell = $derived(hoveredCell ?? activeCell);
	const dateFormat = $derived(preferencesStore.prefs.dateFormat);

	// A clicked cell opens one shared popover with the plays it counts
	let popoverCell = $state<HeatmapCellDto | null>(null);
	let popoverAnchor = $state<HTMLElement | null>(null);
	let cellPlays = $state<CellPlays | null>(null);
	let refocusAnchor: HTMLElement | null = null;
	const nextPlaysRequest = latestRequest();

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
		hoveredCell = null;
		closeCellPopover();
	});

	function isSameCell(a: HeatmapCellDto | null, b: HeatmapCellDto): boolean {
		return a?.date === b.date && a.hour === b.hour;
	}

	function selectCell(cell: HeatmapCellDto) {
		activeCell = cell;
		hoveredCell = null;
	}

	/** The cell's UTC hour as the inclusive bounds History filters plays by their stop time with, like the heatmap. */
	function hourWindow(cell: HeatmapCellDto): { from: string; to: string } {
		const h = String(cell.hour).padStart(2, '0');
		return { from: `${cell.date}T${h}:00:00.000Z`, to: `${cell.date}T${h}:59:59.999Z` };
	}

	function historyHref(cell: HeatmapCellDto): string {
		const params = new URLSearchParams(hourWindow(cell));
		if (serverId) params.set('serverId', serverId);
		return `/history?${params}`;
	}

	async function loadCellPlays(cell: HeatmapCellDto) {
		const isLatest = nextPlaysRequest();
		cellPlays = { status: 'loading' };
		try {
			const res = unwrap(
				await api.GET('/api/v1/history', {
					params: {
						query: {
							...hourWindow(cell),
							serverId: serverId || undefined,
							page: 1,
							pageSize: CELL_PLAYS_LIMIT
						}
					}
				})
			);
			if (isLatest()) cellPlays = { status: 'loaded', items: res.items, totalCount: res.totalCount };
		} catch {
			if (isLatest()) cellPlays = { status: 'error' };
		}
	}

	function toggleCellPopover(cell: HeatmapCellDto, anchor: HTMLElement) {
		selectCell(cell);
		if (popoverCell && isSameCell(popoverCell, cell)) {
			closeCellPopover();
			return;
		}
		popoverCell = cell;
		popoverAnchor = anchor;
		if (cell.playCount > 0) {
			void loadCellPlays(cell);
		} else {
			nextPlaysRequest();
			cellPlays = { status: 'loaded', items: [], totalCount: 0 };
		}
	}

	function closeCellPopover() {
		// A request still on its way is for a popover that is no longer open
		nextPlaysRequest();
		popoverCell = null;
		popoverAnchor = null;
		cellPlays = null;
	}

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
				Each day by UTC hour · intensity by {metric === 'duration' ? 'playback duration' : 'play count'} · Hover a cell for its totals, click or tap it for its plays
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
		<div
			bind:this={gridEl}
			onmouseleave={() => (hoveredCell = null)}
			role="group"
			aria-label="Plays by day and hour"
			class={cn('flex flex-col', compact ? 'gap-[2px]' : 'gap-1')}
		>
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
							{@const isShown = isSameCell(shownCell, cell)}
							{@const isTabStop = activeCell ? isSameCell(activeCell, cell) : row === 0 && cell.hour === 0}
							{@const isPopoverCell = isSameCell(popoverCell, cell)}
							<button
								type="button"
								data-row={row}
								data-hour={cell.hour}
								tabindex={isTabStop ? 0 : -1}
								aria-label={cellDescription(cell)}
								aria-haspopup="dialog"
								aria-expanded={isPopoverCell}
								title={cellDescription(cell)}
								onmouseenter={() => (hoveredCell = cell)}
								onfocus={() => selectCell(cell)}
								onclick={(e) => toggleCellPopover(cell, e.currentTarget)}
								onkeydown={(e) => moveFocus(e, row, cell.hour)}
								class={cn(
									'w-full rounded-[3px] border transition-transform sm:rounded-xs cursor-pointer focus-visible:outline-1 focus-visible:outline-cyan',
									compact ? 'h-3 sm:h-3.5' : 'h-4 sm:h-5',
									cellIntensityClass(cell),
									isShown && 'ring-1 ring-fg-primary scale-105'
								)}
							></button>
						{/each}
					</div>
				</div>
			{/each}
		</div>
	{/if}

	<Popover.Root
		open={popoverCell !== null}
		onOpenChange={(open) => {
			if (!open) closeCellPopover();
		}}
	>
		<Popover.Portal>
			<Popover.Content
				customAnchor={popoverAnchor}
				side="top"
				sideOffset={8}
				collisionPadding={12}
				data-testid="heatmap-cell-popover"
				aria-label={popoverCell ? `Plays ${cellDescription(popoverCell)}` : undefined}
				onInteractOutside={(e) => {
					// The open cell's own click toggles the popover, so it must not count as a click outside
					if (popoverAnchor && e.target instanceof Node && popoverAnchor.contains(e.target)) e.preventDefault();
				}}
				onEscapeKeydown={() => (refocusAnchor = popoverAnchor)}
				onCloseAutoFocus={(e) => {
					// Back to the cell after Escape; a click elsewhere keeps the focus it moved
					e.preventDefault();
					refocusAnchor?.focus();
					refocusAnchor = null;
				}}
				class="z-50 w-80 max-w-[calc(100vw-1.5rem)] rounded-xl border border-border-strong bg-surface p-3 text-fg-primary shadow-xl outline-none data-[state=open]:animate-popover-in"
			>
				<Popover.Arrow class="text-border-strong" width={10} height={5} />
				{#if popoverCell}
					{@const cell = popoverCell}
					<div class="flex flex-col gap-2">
						<div>
							<p class="font-mono text-[11px] font-semibold text-cyan-hi">
								{cell.dayName} {formatCalendarDate(cell.date, dateFormat)} · {formatHourSlot(cell.hour)}
							</p>
							<p class="text-xs text-fg-secondary">
								{cell.playCount} {cell.playCount === 1 ? 'play' : 'plays'} · {formatDurationSec(cell.watchDurationSec)}
							</p>
						</div>

						{#if cell.playCount === 0}
							<p class="text-xs text-fg-muted">No plays stopped in this hour.</p>
						{:else if cellPlays?.status === 'error'}
							<p role="alert" class="text-xs text-danger">Could not load the plays in this hour.</p>
						{:else if cellPlays?.status === 'loaded'}
							<ul class="flex flex-col divide-y divide-border border-y border-border">
								{#each cellPlays.items as item (item.id)}
									{@const titles = formatMediaTitle(item)}
									<li class="flex items-baseline gap-2 py-1.5 text-xs">
										<div class="min-w-0 flex-1">
											<p class="truncate font-medium text-fg-primary">{titles.primary}</p>
											<p class="truncate text-[11px] text-fg-muted">{titles.secondary}</p>
										</div>
										<span class="max-w-24 shrink-0 truncate text-fg-secondary">
											{item.displayName || item.username || item.userId}
										</span>
										<span class="shrink-0 font-mono text-[11px] text-fg-muted tabular-nums">
											{formatDurationSec(item.watchedDurationSec)}
										</span>
									</li>
								{/each}
							</ul>
							{#if cellPlays.totalCount > cellPlays.items.length}
								<p class="text-[11px] text-fg-muted">
									+{cellPlays.totalCount - cellPlays.items.length} more
								</p>
							{/if}
						{:else}
							<p class="text-xs text-fg-muted">Loading plays…</p>
						{/if}

						{#if cell.playCount > 0}
							<a href={historyHref(cell)} class="text-xs font-medium text-cyan-hi hover:underline">
								View all in History →
							</a>
						{/if}
					</div>
				{/if}
			</Popover.Content>
		</Popover.Portal>
	</Popover.Root>

	<!-- Active cell tooltip readout + Intensity legend -->
	<div class="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-elevated px-3 py-2 text-xs">
		<div class="font-mono text-[11px] text-fg-primary tabular-nums" data-testid="heatmap-cell-readout">
			{#if shownCell}
				<span class="font-semibold text-cyan-hi">
					{shownCell.dayName} {formatCalendarDate(shownCell.date, dateFormat)} · {formatHourSlot(shownCell.hour)}
				</span>
				<span class="text-fg-secondary">
					— {shownCell.playCount} {shownCell.playCount === 1 ? 'play' : 'plays'} · {formatDurationSec(shownCell.watchDurationSec)}
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
