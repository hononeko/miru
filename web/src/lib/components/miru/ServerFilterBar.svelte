<script lang="ts">
	import { serversStore } from '$stores/servers.svelte';
	import { cn } from '$lib/utils';

	type Props = {
		selectedServerId: string | null;
		onchange: (serverId: string | null) => void;
		class?: string;
	};

	let { selectedServerId, onchange, class: className = '' }: Props = $props();
</script>

{#if serversStore.hasMultipleServers}
	<div
		role="group"
		aria-label="Filter by media server"
		class={cn('flex flex-wrap items-center gap-1.5', className)}
	>
		<button
			type="button"
			aria-pressed={selectedServerId === null}
			onclick={() => onchange(null)}
			class={cn(
				'inline-flex cursor-pointer items-center gap-1.5 rounded-lg border px-2.5 py-1.5 font-heading text-xs font-semibold transition-colors',
				selectedServerId === null
					? 'border-cyan/60 bg-cyan-soft text-cyan-hi'
					: 'border-border bg-surface text-fg-secondary hover:border-border-strong hover:text-fg-primary'
			)}
		>
			All Servers
		</button>
		{#each serversStore.servers as server (server.id)}
			{@const active = selectedServerId === server.id}
			<button
				type="button"
				aria-pressed={active}
				onclick={() => onchange(server.id)}
				class={cn(
					'inline-flex cursor-pointer items-center gap-1.5 rounded-lg border px-2.5 py-1.5 font-heading text-xs font-semibold transition-colors',
					active
						? 'border-cyan/60 bg-cyan-soft text-cyan-hi'
						: 'border-border bg-surface text-fg-secondary hover:border-border-strong hover:text-fg-primary'
				)}
			>
				<span
					class={cn(
						'h-1.5 w-1.5 rounded-full',
						server.reachable
							? 'bg-direct-play'
							: server.configured
								? 'bg-warning'
								: 'bg-fg-muted'
					)}
				></span>
				<span class="font-mono text-[10px] uppercase opacity-80">{server.serverType}</span>
				<span>{server.name}</span>
			</button>
		{/each}
	</div>
{/if}
