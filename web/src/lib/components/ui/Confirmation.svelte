<script lang="ts">
	import Button from './Button.svelte';

	type Props = {
		titleId: string;
		title: string;
		description?: string;
		confirmLabel: string;
		busyLabel: string;
		busy: boolean;
		/** Why confirming it did not work */
		error?: string | null;
		oncancel: () => void;
		onconfirm: () => void;
	};

	let { titleId, title, description, confirmLabel, busyLabel, busy, error = null, oncancel, onconfirm }: Props = $props();
</script>

<div class="flex flex-wrap items-center justify-between gap-x-4 gap-y-2.5">
	<div class="min-w-0">
		<p id={titleId} class="font-heading text-xs font-semibold text-fg-primary [overflow-wrap:anywhere]">{title}</p>
		{#if description}
			<p id={`${titleId}-description`} class="mt-0.5 text-[11px] text-fg-secondary">{description}</p>
		{/if}
		{#if error}
			<p role="alert" class="mt-1.5 text-[11px] text-danger">{error}</p>
		{/if}
	</div>
	<div class="ml-auto flex items-center gap-2">
		<Button variant="ghost" size="sm" disabled={busy} onclick={oncancel}>Cancel</Button>
		<Button variant="destructive" size="sm" disabled={busy} onclick={onconfirm}>
			{busy ? busyLabel : confirmLabel}
		</Button>
	</div>
</div>
