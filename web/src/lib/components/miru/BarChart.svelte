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
	}

	type Props = {
		points: BarPoint[];
		heightClass?: string;
		emptyMessage?: string;
		highlightPeak?: boolean;
		class?: string;
		detail?: Snippet<[BarPoint, number, () => void]>;
	};

	let {
		points,
		heightClass = 'h-32',
		emptyMessage = 'No data in this range',
		highlightPeak = true,
		class: className = '',
		detail
	}: Props = $props();

	let openKey = $state<string | null>(null);
	let scrollEl: HTMLDivElement | undefined = $state();

	const maxValue = $derived(Math.max(...points.map((p) => p.value), 0));
	const hasSubLabels = $derived(points.some((p) => Boolean(p.subLabel)));

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

{#if points.length === 0 || maxValue === 0}
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
	<div bind:this={scrollEl} class={cn('w-full overflow-x-auto pb-1', className)}>
		<div class={cn('flex items-end gap-1.5 sm:gap-2', heightClass)}>
			{#each points as point, idx (pointKey(point, idx))}
				{@const key = pointKey(point, idx)}
				{@const isOpen = openKey === key}
				{@const ratio = maxValue > 0 ? point.value / maxValue : 0}
				{@const isPeak = highlightPeak && point.value === maxValue && point.value > 0}
				{@const barHeightPct = point.value > 0 ? Math.max(8, Math.round(ratio * 100)) : 4}
				{@const activeSegments = point.segments?.filter((s) => s.value > 0) ?? []}
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
						class="group flex h-full min-w-[22px] flex-1 cursor-pointer flex-col items-center justify-end gap-1 focus-visible:outline-none sm:min-w-[26px]"
					>
						<div class="flex w-full flex-1 items-end">
							{#if activeSegments.length > 0}
								<div
									class={cn(
										'flex w-full flex-col-reverse gap-[2px] overflow-hidden rounded-t-sm transition-all duration-300 group-focus-visible:ring-2 group-focus-visible:ring-fg-primary/80 group-focus-visible:ring-offset-1 group-focus-visible:ring-offset-surface',
										isOpen && 'ring-2 ring-fg-primary/80 ring-offset-1 ring-offset-surface'
									)}
									style:height={`${barHeightPct}%`}
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
							{:else}
								<div
									class={cn(
										'w-full rounded-t-sm transition-all duration-300 group-focus-visible:ring-2 group-focus-visible:ring-fg-primary/80 group-focus-visible:ring-offset-1 group-focus-visible:ring-offset-surface',
										point.value <= 0
											? 'bg-subtle/80 group-hover:bg-border-strong'
											: isPeak
												? 'bg-primary group-hover:bg-primary/85'
												: 'bg-cyan/85 group-hover:bg-cyan-hi',
										isOpen && 'ring-2 ring-fg-primary/80 ring-offset-1 ring-offset-surface'
									)}
									style:height={`${barHeightPct}%`}
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
							class="z-50 w-72 max-w-[calc(100vw-1.5rem)] rounded-xl border border-border-strong bg-surface p-3 text-fg-primary shadow-xl outline-none"
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
