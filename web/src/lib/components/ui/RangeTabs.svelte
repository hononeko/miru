<script lang="ts">
	import type { StatsRange } from '$api/types';
	import { cn } from '$lib/utils';

	type Props = {
		value: StatsRange;
		onchange: (range: StatsRange) => void;
		class?: string;
	};

	const RANGES: StatsRange[] = ['7d', '30d', '90d', 'all'];
	let { value, onchange, class: className = '' }: Props = $props();
</script>

<div
	role="tablist"
	aria-label="Time range"
	class={cn('inline-flex items-center gap-0.5 rounded-lg border border-border bg-surface p-0.5', className)}
>
	{#each RANGES as range}
		{@const active = value === range}
		<button
			type="button"
			role="tab"
			aria-selected={active}
			onclick={() => onchange(range)}
			class={cn(
				'rounded-md px-2.5 py-1 font-mono text-[11px] font-medium transition-[background-color,border-color,color,transform] duration-150 ease-[var(--ease-out-quart)] active:scale-[0.97] motion-reduce:active:scale-100 cursor-pointer',
				active
					? 'bg-cyan-soft text-cyan-hi border border-cyan/60 font-semibold'
					: 'text-fg-secondary hover:text-fg-primary border border-transparent'
			)}
		>
			{range}
		</button>
	{/each}
</div>
