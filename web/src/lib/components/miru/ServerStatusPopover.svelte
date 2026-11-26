<script lang="ts">
	import { Popover } from 'bits-ui';
	import type { MediaServerSummaryDto } from '$api/types';
	import { getServerStatus } from '$lib/serverStatus';
	import { cn } from '$lib/utils';
	import ServerStatusList from './ServerStatusList.svelte';

	type Props = {
		servers: MediaServerSummaryDto[];
		stale?: boolean;
		class?: string;
	};

	let { servers, stale = false, class: className = '' }: Props = $props();

	let open = $state(false);

	const statuses = $derived(servers.map((server) => ({ server, status: getServerStatus(server, { stale }) })));
	const summaryLabel = $derived(
		`Media server status: ${statuses.map(({ server, status }) => `${server.name} ${status.label}`).join(', ')}`
	);
</script>

<Popover.Root bind:open>
	<Popover.Trigger
		type="button"
		aria-label={summaryLabel}
		class={cn(
			'inline-flex h-8 shrink-0 cursor-pointer items-center gap-1 rounded-full border border-border bg-elevated px-2.5 transition-[border-color,transform] duration-150 hover:border-border-strong active:scale-95 motion-reduce:active:scale-100 data-[state=open]:border-border-strong',
			className
		)}
	>
		{#each statuses as { server, status } (server.id)}
			<span
				aria-hidden="true"
				class={cn(
					'h-2 w-2 rounded-full',
					status.dotClass,
					status.kind === 'reconnecting' && 'animate-pulse motion-reduce:animate-none'
				)}
			></span>
		{/each}
	</Popover.Trigger>
	<Popover.Portal>
		<Popover.Content
			side="bottom"
			align="end"
			sideOffset={8}
			collisionPadding={12}
			aria-label="Media server status"
			class="z-50 w-64 max-w-[calc(100vw-1.5rem)] rounded-xl border border-border-strong bg-surface p-3 text-fg-primary shadow-xl outline-none data-[state=open]:animate-popover-in"
		>
			<p class="mb-2 font-heading text-[10px] font-semibold tracking-wider text-fg-muted uppercase">
				Media servers
			</p>
			<ServerStatusList {servers} {stale} />
		</Popover.Content>
	</Popover.Portal>
</Popover.Root>
