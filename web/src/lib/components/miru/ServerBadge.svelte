<script lang="ts">
	import Badge from '$components/ui/Badge.svelte';
	import { serversStore } from '$stores/servers.svelte';

	type Props = {
		serverId?: string | null;
		serverType?: string | null;
		serverName?: string | null;
		compact?: boolean;
		class?: string;
	};

	let {
		serverId = null,
		serverType = null,
		serverName = null,
		compact = false,
		class: className = ''
	}: Props = $props();

	const resolvedServer = $derived(serversStore.byId(serverId));
	const resolvedType = $derived((serverType ?? resolvedServer?.serverType ?? '').trim().toLowerCase());
	const resolvedName = $derived((serverName ?? resolvedServer?.name ?? '').trim());
	const isDisconnected = $derived(resolvedServer !== null && !resolvedServer.configured);

	const typeLabel = $derived(resolvedType ? resolvedType.toUpperCase() : 'MEDIA');
	const badgeLabel = $derived.by(() => {
		const base = resolvedName && !compact ? `${typeLabel} · ${resolvedName}` : typeLabel;
		return isDisconnected ? `${base} · Disconnected` : base;
	});
	const tone = $derived.by(() => {
		if (isDisconnected) return 'neutral' as const;
		if (resolvedType === 'jellyfin') return 'cyan' as const;
		if (resolvedType === 'plex') return 'primary' as const;
		return 'neutral' as const;
	});
</script>

{#if resolvedType || resolvedName}
	<Badge {tone} mono class={className}>{badgeLabel}</Badge>
{/if}
