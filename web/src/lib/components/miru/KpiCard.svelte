<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '$lib/utils';

	type Props = {
		label: string;
		value: string;
		subtitle?: string;
		dotClass?: string;
		class?: string;
		extra?: Snippet;
	};

	let {
		label,
		value,
		subtitle,
		dotClass = 'bg-cyan',
		class: className = '',
		extra
	}: Props = $props();
</script>

<div
	class={cn(
		'flex flex-col justify-between gap-2 rounded-xl border border-border bg-surface p-4 shadow-xs dark:shadow-none',
		className
	)}
>
	<div class="flex items-center justify-between gap-2">
		<span class="font-heading text-[11px] font-semibold text-fg-secondary">{label}</span>
		<span class={cn('h-2 w-2 shrink-0 rounded-full', dotClass)}></span>
	</div>
	<div class="font-mono text-xl font-bold tracking-tight text-fg-primary tabular-nums sm:text-2xl">
		{value}
	</div>
	{#if extra}
		{@render extra()}
	{/if}
	{#if subtitle}
		<p class="truncate text-[11px] text-fg-muted">{subtitle}</p>
	{/if}
</div>
