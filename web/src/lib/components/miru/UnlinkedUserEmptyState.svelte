<script lang="ts">
	import { Link2 } from '@lucide/svelte';

	type Props = {
		context: string;
	};

	let { context }: Props = $props();
</script>

<div
	role="status"
	class="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-border-strong bg-surface/60 px-6 py-12 text-center"
>
	<div class="flex h-12 w-12 items-center justify-center rounded-full bg-elevated text-cyan-hi">
		<Link2 class="h-5 w-5" />
	</div>
	<div class="flex max-w-md flex-col gap-1">
		<h2 class="font-heading text-base font-semibold text-fg-primary">
			No media user linked to your account
		</h2>
		<p class="text-xs text-fg-secondary">
			An administrator must link your media user in Admin &amp; Access Control before your {context} can appear here.
		</p>
	</div>
</div>
