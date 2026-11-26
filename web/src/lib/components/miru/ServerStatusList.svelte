<script lang="ts">
	import type { MediaServerSummaryDto } from '$api/types';
	import { getServerStatus } from '$lib/serverStatus';
	import { cn } from '$lib/utils';
	import ServerBadge from './ServerBadge.svelte';

	type Props = {
		servers: MediaServerSummaryDto[];
		stale?: boolean;
		class?: string;
	};

	let { servers, stale = false, class: className = '' }: Props = $props();
</script>

<ul aria-label="Media server status" class={cn('flex flex-col gap-2', className)}>
	{#each servers as server (server.id)}
		{@const status = getServerStatus(server, { stale })}
		<li
			class="flex min-w-0 flex-col gap-0.5"
			title={`${server.name}: ${status.description}`}
			data-server-id={server.id}
			data-status={status.kind}
		>
			<span class="flex min-w-0 items-center justify-between gap-2">
				<span class="flex min-w-0 items-center gap-2">
					<span
						aria-hidden="true"
						class={cn(
							'h-2 w-2 shrink-0 rounded-full',
							status.dotClass,
							status.kind === 'reconnecting' && 'animate-pulse motion-reduce:animate-none'
						)}
					></span>
					<span class="truncate font-heading text-[11px] font-semibold text-fg-primary">{server.name}</span>
				</span>
				<ServerBadge serverType={server.serverType} compact class="shrink-0 px-1.5 text-[9px]" />
			</span>
			<span class={cn('pl-4 font-mono text-[10px] font-semibold uppercase', status.textClass)}>
				{status.label}
				<span class="sr-only">: {status.description}</span>
			</span>
		</li>
	{/each}
</ul>
