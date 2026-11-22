<script lang="ts">
	import { cn } from '$lib/utils';

	export interface BarPoint {
		label: string;
		value: number;
		tooltip?: string;
	}

	type Props = {
		points: BarPoint[];
		heightClass?: string;
		emptyMessage?: string;
		highlightPeak?: boolean;
		class?: string;
	};

	let {
		points,
		heightClass = 'h-32',
		emptyMessage = 'No data in this range',
		highlightPeak = true,
		class: className = ''
	}: Props = $props();

	const maxValue = $derived(Math.max(...points.map((p) => p.value), 0));
</script>

{#if points.length === 0 || maxValue === 0}
	<div class={cn('flex items-center justify-center rounded-lg bg-elevated/50 text-xs text-fg-muted', heightClass, className)}>
		{emptyMessage}
	</div>
{:else}
	<div class={cn('flex items-end gap-1.5 sm:gap-2', heightClass, className)}>
		{#each points as point}
			{@const ratio = maxValue > 0 ? point.value / maxValue : 0}
			{@const isPeak = highlightPeak && point.value === maxValue && point.value > 0}
			<div
				class="group flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1.5"
				title={point.tooltip ?? `${point.label}: ${point.value}`}
			>
				<div class="flex w-full flex-1 items-end">
					<div
						class={cn(
							'w-full rounded-t-sm transition-all duration-300',
							isPeak
								? 'bg-primary group-hover:bg-primary-hover'
								: 'bg-cyan/85 group-hover:bg-cyan-hi'
						)}
						style:height={`${Math.max(8, Math.round(ratio * 100))}%`}
					></div>
				</div>
				<span
					class={cn(
						'truncate font-mono text-[9px] tabular-nums',
						isPeak ? 'font-semibold text-primary-hover' : 'text-fg-muted'
					)}
				>
					{point.label}
				</span>
			</div>
		{/each}
	</div>
{/if}
