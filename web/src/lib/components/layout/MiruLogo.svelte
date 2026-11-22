<script lang="ts">
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
	class={className}
>
	<rect width="64" height="64" rx="16" class="fill-primary" />
	<circle cx="32" cy="32" r="20" stroke="#FFFFFF" stroke-opacity="0.92" stroke-width="4" />
	<circle cx="32" cy="32" r="10" class="fill-cyan stroke-cyan-hi" stroke-width="1.5" />
	<circle cx="38" cy="24" r="3.5" fill="#FFFFFF" />
</svg>
