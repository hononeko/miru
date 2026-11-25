<script lang="ts" module>
	export interface ShareSegment {
		name: string;
		percent: number;
		colorClass: string;
		title: string;
	}
</script>

<script lang="ts">
	import { formatStatPercent, statBarWidth } from '$lib/utils';

	type Props = {
		segments: ShareSegment[];
	};

	let { segments }: Props = $props();
</script>

<div class="flex h-2 w-full gap-1 overflow-hidden rounded-full bg-subtle">
	{#each segments as segment}
		<div
			class={`h-full rounded-full transition-[width] duration-500 ease-[var(--ease-out-quart)] ${segment.colorClass} ${segment.percent > 0 ? 'min-w-0.5' : ''}`}
			style:width={statBarWidth(segment.percent)}
			title={segment.title}
		></div>
	{/each}
</div>
<div class="flex flex-wrap gap-x-3 gap-y-1">
	{#each segments as segment}
		<span class="inline-flex items-center gap-1.5 font-mono text-[10px] text-fg-secondary" title={segment.title}>
			<span class={`h-2 w-2 rounded-full ${segment.colorClass}`}></span>
			{segment.name} {formatStatPercent(segment.percent, 0)}
		</span>
	{/each}
</div>
