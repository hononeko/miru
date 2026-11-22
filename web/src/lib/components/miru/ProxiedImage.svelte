<script lang="ts">
	import { cn, safeInitial } from '$lib/utils';

	type Props = {
		src?: string | null;
		alt: string;
		fallbackLabel?: string;
		variant?: 'poster' | 'avatar';
		class?: string;
	};

	let {
		src = null,
		alt,
		fallbackLabel = '',
		variant = 'poster',
		class: className = ''
	}: Props = $props();

	let failedSrc = $state<string | null>(null);

	// Security guard: only allow same-origin Miru artwork/avatar proxy URLs
	const safeSrc = $derived.by(() => {
		if (!src || src === failedSrc) return null;
		return src.startsWith('/api/v1/') ? src : null;
	});
</script>

{#if safeSrc}
	<img
		src={safeSrc}
		{alt}
		loading="lazy"
		decoding="async"
		onerror={() => {
			if (src) failedSrc = src;
		}}
		class={cn(
			'object-cover shrink-0',
			variant === 'avatar' ? 'rounded-full' : 'rounded-lg border border-border-strong bg-elevated',
			className
		)}
	/>
{:else if variant === 'avatar'}
	<div
		aria-label={alt}
		class={cn(
			'flex shrink-0 items-center justify-center rounded-full border border-primary/70 bg-primary-soft font-heading font-bold text-primary-hover',
			className
		)}
	>
		{safeInitial(fallbackLabel || alt)}
	</div>
{:else}
	<div
		aria-label={alt}
		class={cn(
			'relative flex shrink-0 flex-col justify-end overflow-hidden rounded-lg border border-border-strong bg-elevated p-2',
			className
		)}
	>
		<div class="pointer-events-none absolute inset-x-0 top-0 h-[42%] bg-cyan-soft/90"></div>
		<div
			class="pointer-events-none absolute left-1/2 top-[24%] h-7 w-7 -translate-x-1/2 rounded-full bg-primary/30"
		></div>
		<span class="relative line-clamp-2 font-display text-[9px] font-bold uppercase tracking-wider text-fg-primary">
			{fallbackLabel || alt}
		</span>
	</div>
{/if}
