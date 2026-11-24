<script lang="ts">
	import { cn } from '$lib/utils';

	type Props = {
		size?: number;
		class?: string;
	};
	let { size = 32, class: className = '' }: Props = $props();
</script>

<svg
	width={size}
	height={size}
	viewBox="0 0 64 64"
	fill="none"
	aria-hidden="true"
	class={cn('miru-logo', className)}
>
	<rect width="64" height="64" rx="16" class="fill-primary" />
	<circle cx="32" cy="32" r="20" stroke="#FBF6EE" stroke-opacity="0.95" stroke-width="4" />
	<circle
		cx="32"
		cy="32"
		r="10"
		fill="#1F4D3A"
		stroke="#8FB09C"
		stroke-width="1.5"
		class="miru-logo-iris"
	/>
	<circle cx="38" cy="24" r="3.5" fill="#FBF6EE" class="miru-logo-glint" />
</svg>
