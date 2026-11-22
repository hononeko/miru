<script lang="ts">
	import type { Snippet } from 'svelte';
	import { Dialog } from 'bits-ui';
	import { X } from 'lucide-svelte';
	import { sheetDrag } from '$lib/actions/sheetDrag';

	type Props = {
		open: boolean;
		onOpenChange: (open: boolean) => void;
		title: string;
		description?: string;
		children?: Snippet;
	};

	let { open = $bindable(), onOpenChange, title, description, children }: Props = $props();
	let contentEl = $state<HTMLDivElement | null>(null);

	$effect(() => {
		if (!contentEl) return;
		const drag = sheetDrag(contentEl, { onDismiss: () => onOpenChange(false) });
		return () => drag.destroy();
	});
</script>

<Dialog.Root {open} {onOpenChange}>
	<Dialog.Portal>
		<Dialog.Overlay class="fixed inset-0 z-50 bg-black/60 backdrop-blur-[2px]" />
		<Dialog.Content
			bind:ref={contentEl}
			class="fixed inset-x-0 bottom-0 z-50 max-h-[88dvh] overflow-y-auto rounded-t-2xl border-t border-border-strong bg-surface p-5 shadow-2xl focus:outline-none md:inset-y-0 md:right-0 md:left-auto md:max-h-none md:w-full md:max-w-[420px] md:rounded-t-none md:border-t-0 md:border-l safe-pb"
		>
			<div
				data-sheet-drag-handle
				class="mx-auto -mt-2 mb-2 flex cursor-grab touch-none justify-center py-2 active:cursor-grabbing md:hidden"
			>
				<div class="h-1 w-10 rounded-full bg-border-strong"></div>
			</div>
			<div class="flex items-start justify-between gap-3 border-b border-border pb-3.5">
				<div>
					<Dialog.Title class="font-heading text-base font-semibold text-fg-primary">
						{title}
					</Dialog.Title>
					{#if description}
						<Dialog.Description class="mt-0.5 text-xs text-fg-secondary">
							{description}
						</Dialog.Description>
					{/if}
				</div>
				<Dialog.Close
					aria-label="Close panel"
					class="inline-flex h-8 w-8 items-center justify-center rounded-lg text-fg-muted hover:bg-elevated hover:text-fg-primary cursor-pointer"
				>
					<X class="h-4 w-4" />
				</Dialog.Close>
			</div>
			<div class="mt-4">
				{@render children?.()}
			</div>
		</Dialog.Content>
	</Dialog.Portal>
</Dialog.Root>
