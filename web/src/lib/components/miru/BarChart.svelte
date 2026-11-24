<script lang="ts">
	import type { Snippet } from 'svelte';
	import { Popover } from 'bits-ui';
	import { cn } from '$lib/utils';

	export interface BarSegment {
		label: string;
		value: number;
		colorClass: string;
	}

	export interface BarPoint {
		id?: string;
		label: string;
		subLabel?: string;
		value: number;
		tooltip?: string;
		ariaLabel?: string;
		segments?: BarSegment[];
		/** Drawn below the baseline, on the same scale as `value`. */
		negative?: BarSegment;
	}

	type Props = {
		points: BarPoint[];
		heightClass?: string;
		barClass?: string;
		emptyMessage?: string;
		highlightPeak?: boolean;
		class?: string;
		detail?: Snippet<[BarPoint, number, () => void]>;
	};

	let {
		points,
		heightClass = 'h-32',
		barClass = '',
		emptyMessage = 'No data in this range',
		highlightPeak = true,
		class: className = '',
		detail
	}: Props = $props();

	let openKey = $state<string | null>(null);
	let scrollEl: HTMLDivElement | undefined = $state();

	const MIN_SIDE_PCT = 12;

	const maxValue = $derived(Math.max(...points.map((p) => p.value), 0));
	const maxNegative = $derived(Math.max(...points.map((p) => p.negative?.value ?? 0), 0));
	// Height of the area below the baseline, leaving room for the smallest bar on either side.
	const baselinePct = $derived.by(() => {
		if (maxNegative === 0) return 0;
		if (maxValue === 0) return 100;
		const proportional = (maxNegative / (maxValue + maxNegative)) * 100;
		return Math.min(100 - MIN_SIDE_PCT, Math.max(MIN_SIDE_PCT, proportional));
	});
	const pctPerUnit = $derived(
		Math.min(
			maxValue > 0 ? (100 - baselinePct) / maxValue : Infinity,
			maxNegative > 0 ? baselinePct / maxNegative : Infinity
		)
	);
	const hasSubLabels = $derived(points.some((p) => Boolean(p.subLabel)));

	function barHeightPct(value: number): number {
		return value > 0 ? Math.max(8, Math.round(value * pctPerUnit)) : 4;
	}

	function pointKey(point: BarPoint, idx: number): string {
		return point.id ?? `${idx}:${point.label}`;
	}

	$effect(() => {
		void points;
		openKey = null;
		if (scrollEl) {
			scrollEl.scrollLeft = scrollEl.scrollWidth;
		}
	});
</script>

{#if points.length === 0 || (maxValue === 0 && maxNegative === 0)}
	<div
		class={cn(
			'flex items-center justify-center rounded-lg bg-elevated/50 text-xs text-fg-muted',
			heightClass,
			className
		)}
	>
		{emptyMessage}
	</div>
{:else}
	<!-- p-1: the scroll container clips the ring around an open bar without it -->
	<div bind:this={scrollEl} class={cn('w-full overflow-x-auto p-1', className)}>
		<div class={cn('flex items-end gap-1.5 sm:gap-2', heightClass)}>
			{#each points as point, idx (pointKey(point, idx))}
				{@const key = pointKey(point, idx)}
				{@const isOpen = openKey === key}
				{@const isPeak = highlightPeak && point.value === maxValue && point.value > 0}
				{@const activeSegments = point.segments?.filter((s) => s.value > 0) ?? []}
				{@const ringClass = cn(
					'group-focus-visible:ring-2 group-focus-visible:ring-fg-primary/80 group-focus-visible:ring-offset-1 group-focus-visible:ring-offset-surface',
					isOpen && 'ring-2 ring-fg-primary/80 ring-offset-1 ring-offset-surface'
				)}
				<Popover.Root
					open={isOpen}
					onOpenChange={(next) => {
						if (next) {
							openKey = key;
						} else if (openKey === key) {
							openKey = null;
						}
					}}
				>
					<Popover.Trigger
						type="button"
						aria-label={point.ariaLabel ?? point.tooltip ?? `${point.label}: ${point.value}`}
						class={cn(
							'group flex h-full min-w-[22px] flex-1 cursor-pointer flex-col items-center justify-end gap-1 focus-visible:outline-none sm:min-w-[26px]',
							barClass
						)}
					>
						<div class="relative w-full flex-1">
							{#if activeSegments.length > 0}
								<div
									class={cn(
										'absolute inset-x-0 flex flex-col-reverse gap-[2px] overflow-hidden rounded-t-sm transition-[height,opacity,box-shadow] duration-300 ease-[var(--ease-out-quart)]',
										ringClass
									)}
									style:bottom={`${baselinePct}%`}
									style:height={`${barHeightPct(point.value)}%`}
								>
									{#each activeSegments as seg (seg.label)}
										{@const segPct = point.value > 0 ? (seg.value / point.value) * 100 : 0}
										<div
											class={cn(
												'min-h-[2px] w-full transition-opacity group-hover:opacity-90',
												seg.colorClass
											)}
											style:height={`${segPct}%`}
										></div>
									{/each}
								</div>
							{:else if point.value > 0 || !point.negative?.value}
								<div
									class={cn(
										'absolute inset-x-0 rounded-t-sm transition-[height,background-color,opacity,box-shadow] duration-300 ease-[var(--ease-out-quart)]',
										ringClass,
										point.value <= 0
											? 'bg-subtle/80 group-hover:bg-border-strong'
											: isPeak
												? 'bg-primary group-hover:bg-primary/85'
												: 'bg-cyan/85 group-hover:bg-cyan-hi'
									)}
									style:bottom={`${baselinePct}%`}
									style:height={`${barHeightPct(point.value)}%`}
								></div>
							{/if}
							{#if maxNegative > 0}
								<div
									class="absolute -inset-x-[3px] h-px bg-border-strong sm:-inset-x-1"
									style:bottom={`${baselinePct}%`}
								></div>
							{/if}
							{#if point.negative && point.negative.value > 0}
								<div
									class={cn(
										'absolute inset-x-0 rounded-b-sm transition-[height,opacity,box-shadow] duration-300 ease-[var(--ease-out-quart)] group-hover:opacity-90',
										point.negative.colorClass,
										ringClass
									)}
									style:top={`${100 - baselinePct}%`}
									style:height={`${barHeightPct(point.negative.value)}%`}
								></div>
							{/if}
						</div>

						<div class="flex flex-col items-center leading-tight">
							<span
								class={cn(
									'truncate font-mono text-[9px] tabular-nums',
									isOpen || isPeak ? 'font-semibold text-primary-text' : 'text-fg-muted'
								)}
							>
								{point.label}
							</span>
							{#if hasSubLabels}
								<span class="h-3 truncate font-mono text-[8px] text-fg-muted">
									{point.subLabel ?? ''}
								</span>
							{/if}
						</div>
					</Popover.Trigger>

					<Popover.Portal>
						<Popover.Content
							side="top"
							sideOffset={8}
							collisionPadding={12}
							class="z-50 w-72 max-w-[calc(100vw-1.5rem)] rounded-xl border border-border-strong bg-surface p-3 text-fg-primary shadow-xl outline-none data-[state=open]:animate-popover-in"
						>
							<Popover.Arrow class="text-border-strong" width={10} height={5} />
							{#if detail}
								{@render detail(point, idx, () => (openKey = null))}
							{:else}
								<div class="flex flex-col gap-1">
									<span class="font-mono text-[11px] font-semibold text-cyan-hi">
										{point.label}{point.subLabel ? ` · ${point.subLabel}` : ''}
									</span>
									<p class="text-xs text-fg-secondary">
										{point.tooltip ?? String(point.value)}
									</p>
								</div>
							{/if}
						</Popover.Content>
					</Popover.Portal>
				</Popover.Root>
			{/each}
		</div>
	</div>
{/if}
