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
	let loadedSrc = $state<string | null>(null);
	let imgEl = $state<HTMLImageElement | null>(null);

	// Security guard: only allow same-origin Miru artwork/avatar proxy URLs
	const safeSrc = $derived.by(() => {
		if (!src || src === failedSrc) return null;
		return src.startsWith('/api/v1/') ? src : null;
	});

	$effect(() => {
		const target = safeSrc;
		if (target && imgEl && imgEl.complete && imgEl.naturalWidth > 0) {
			loadedSrc = target;
		}
	});
</script>

{#if variant === 'avatar'}
	<span
		aria-label={safeSrc ? undefined : alt}
		class={cn(
			'relative inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full border border-primary/70 bg-primary-soft font-heading font-bold text-primary-hover',
			className
		)}
	>
		{#if !safeSrc || loadedSrc !== safeSrc}
			<span aria-hidden={Boolean(safeSrc)}>{safeInitial(fallbackLabel || alt)}</span>
		{/if}
		{#if safeSrc}
			<img
				bind:this={imgEl}
				src={safeSrc}
				{alt}
				loading="lazy"
				decoding="async"
				onload={() => {
					loadedSrc = safeSrc;
				}}
				onerror={() => {
					if (src) failedSrc = src;
					if (loadedSrc === src) loadedSrc = null;
				}}
				class={cn(
					'absolute inset-0 h-full w-full object-cover text-transparent transition-opacity duration-150',
					loadedSrc === safeSrc ? 'bg-surface opacity-100' : 'opacity-0'
				)}
			/>
		{/if}
	</span>
{:else if safeSrc}
	<img
		src={safeSrc}
		{alt}
		loading="lazy"
		decoding="async"
		onerror={() => {
			if (src) failedSrc = src;
		}}
		class={cn('shrink-0 rounded-lg border border-border-strong bg-elevated object-cover', className)}
	/>
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
