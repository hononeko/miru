<script lang="ts">
	import type { Snippet } from 'svelte';
	import { tv, type VariantProps } from 'tailwind-variants';
	import { cn } from '$lib/utils';

	const badgeVariants = tv({
		base: 'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[10px] font-semibold tracking-wider uppercase leading-tight whitespace-nowrap',
		variants: {
			tone: {
				neutral: 'bg-elevated text-fg-secondary border border-border',
				primary: 'bg-primary-soft text-primary-hover border border-primary/50',
				cyan: 'bg-cyan-soft text-cyan-hi border border-cyan/50',
				'direct-play': 'bg-direct-play-soft text-direct-play',
				'direct-stream': 'bg-direct-stream-soft text-direct-stream',
				'hw-transcode': 'bg-hw-transcode-soft text-hw-transcode',
				'sw-transcode': 'bg-sw-transcode-soft text-sw-transcode',
				danger: 'bg-danger-soft text-danger'
			},
			mono: {
				true: 'font-mono normal-case tracking-normal',
				false: ''
			}
		},
		defaultVariants: {
			tone: 'neutral',
			mono: false
		}
	});

	type Props = {
		tone?: VariantProps<typeof badgeVariants>['tone'];
		mono?: boolean;
		dot?: boolean;
		pulse?: boolean;
		class?: string;
		children?: Snippet;
	};

	let {
		tone = 'neutral',
		mono = false,
		dot = false,
		pulse = false,
		class: className = '',
		children
	}: Props = $props();
</script>

<span class={cn(badgeVariants({ tone, mono }), className)}>
	{#if dot}
		<span class={cn('h-1.5 w-1.5 rounded-full bg-current shrink-0', pulse && 'animate-pulse')}></span>
	{/if}
	{@render children?.()}
</span>
