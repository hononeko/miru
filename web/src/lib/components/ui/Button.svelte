<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { HTMLButtonAttributes } from 'svelte/elements';
	import { tv, type VariantProps } from 'tailwind-variants';
	import { cn } from '$lib/utils';

	const buttonVariants = tv({
		base: 'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg font-heading text-xs font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan disabled:pointer-events-none disabled:opacity-50 cursor-pointer',
		variants: {
			variant: {
				primary: 'bg-primary text-primary-fg hover:bg-primary/85',
				cyan: 'bg-cyan text-cyan-fg hover:bg-cyan/85',
				secondary: 'bg-elevated text-fg-primary border border-border hover:border-border-strong',
				outline: 'bg-transparent text-fg-primary border border-border-strong hover:bg-elevated',
				ghost: 'bg-transparent text-fg-secondary hover:bg-elevated hover:text-fg-primary',
				destructive: 'bg-danger-soft text-danger border border-danger/60 hover:bg-danger hover:text-white'
			},
			size: {
				sm: 'h-8 px-3 text-[11px]',
				md: 'h-9 px-3.5 text-xs',
				lg: 'h-11 px-4 text-sm',
				icon: 'h-9 w-9 p-0'
			}
		},
		defaultVariants: {
			variant: 'primary',
			size: 'md'
		}
	});

	type Props = HTMLButtonAttributes & {
		variant?: VariantProps<typeof buttonVariants>['variant'];
		size?: VariantProps<typeof buttonVariants>['size'];
		class?: string;
		children?: Snippet;
	};

	let {
		variant = 'primary',
		size = 'md',
		class: className = '',
		type = 'button',
		children,
		...rest
	}: Props = $props();
</script>

<button {type} class={cn(buttonVariants({ variant, size }), className)} {...rest}>
	{@render children?.()}
</button>
