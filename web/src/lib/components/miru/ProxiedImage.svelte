<script lang="ts">
	import { cn, safeInitial } from '$lib/utils';

	type Props = {
		src?: string | null;
		serverId?: string | null;
		alt: string;
		fallbackLabel?: string;
		variant?: 'poster' | 'avatar';
		class?: string;
	};

	let {
		src = null,
		serverId = null,
		alt,
		fallbackLabel = '',
		variant = 'poster',
		class: className = ''
	}: Props = $props();

	let failedSrc = $state<string | null>(null);
	let loadedSrc = $state<string | null>(null);
	let imgEl = $state<HTMLImageElement | null>(null);

	// Security guard: only allow same-origin Miru artwork/avatar proxy URLs
	const resolvedSrc = $derived.by(() => {
		if (!src || !src.startsWith('/api/v1/')) return null;
		const sid = serverId?.trim();
		if (!sid || !src.startsWith('/api/v1/media/') || src.includes('serverId=')) {
			return src;
		}
		const sep = src.includes('?') ? '&' : '?';
		return `${src}${sep}serverId=${encodeURIComponent(sid)}`;
	});

	const safeSrc = $derived.by(() => {
		if (!resolvedSrc || resolvedSrc === failedSrc) return null;
		return resolvedSrc;
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
			'relative inline-flex shrink-0 items-center justify-center overflow-hidden rounded-full border border-primary/70 bg-primary-soft font-heading font-bold text-primary-text',
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
					if (safeSrc) failedSrc = safeSrc;
					if (loadedSrc === safeSrc) loadedSrc = null;
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
			if (safeSrc) failedSrc = safeSrc;
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
