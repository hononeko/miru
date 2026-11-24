<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { HTMLButtonAttributes } from 'svelte/elements';
	import { tv, type VariantProps } from 'tailwind-variants';
	import { cn } from '$lib/utils';

	const buttonVariants = tv({
		base: 'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg font-heading text-xs font-semibold transition-[background-color,border-color,color,box-shadow,transform,opacity] duration-150 ease-[var(--ease-out-quart)] active:scale-[0.98] motion-reduce:transition-none motion-reduce:active:scale-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan disabled:pointer-events-none disabled:opacity-50 cursor-pointer',
		variants: {
			variant: {
				primary:
					'btn-sheen bg-primary text-primary-fg hover:bg-primary/90 hover:-translate-y-px hover:shadow-[0_4px_14px_-3px_color-mix(in_srgb,var(--primary)_45%,transparent)] active:translate-y-0 motion-reduce:hover:translate-y-0',
				cyan:
					'btn-sheen bg-cyan text-cyan-fg hover:bg-cyan/90 hover:-translate-y-px hover:shadow-[0_4px_14px_-3px_color-mix(in_srgb,var(--accent-cyan)_40%,transparent)] active:translate-y-0 motion-reduce:hover:translate-y-0',
				secondary:
					'bg-elevated text-fg-primary border border-border hover:border-border-strong hover:bg-subtle/40',
				outline: 'bg-transparent text-fg-primary border border-border-strong hover:bg-elevated',
				ghost: 'bg-transparent text-fg-secondary hover:bg-elevated hover:text-fg-primary',
				destructive:
					'bg-danger-soft text-danger border border-danger/60 hover:bg-danger hover:text-danger-fg hover:-translate-y-px active:translate-y-0 motion-reduce:hover:translate-y-0'
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
