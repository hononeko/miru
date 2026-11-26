<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { Search } from '@lucide/svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type { UserSummaryDto } from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import ProxiedImage from '$components/miru/ProxiedImage.svelte';
	import ServerBadge from '$components/miru/ServerBadge.svelte';
	import ServerFilterBar from '$components/miru/ServerFilterBar.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { serversStore } from '$stores/servers.svelte';
	import { cn, formatDurationSec, formatRelativeTime } from '$lib/utils';

	let users = $state<UserSummaryDto[]>([]);
	let selectedServerId = $state<string | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);
	let query = $state('');

	async function loadUsers(serverId: string | null = selectedServerId) {
		if (!authStore.isAdmin) {
			loading = false;
			return;
		}
		loading = true;
		error = null;
		try {
			const res = unwrap(
				await api.GET('/api/v1/users', serverId ? { params: { query: { serverId } } } : undefined)
			);
			users = res.users;
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Failed to load users';
		} finally {
			loading = false;
		}
	}

	onMount(() => {
		if (!authStore.loading && !authStore.isAdmin) {
			void goto('/');
			return;
		}
		void loadUsers(selectedServerId);
	});

	$effect(() => {
		if (selectedServerId && serversStore.loaded && !serversStore.byId(selectedServerId)) {
			selectedServerId = null;
			void loadUsers(null);
		}
	});

	const filteredUsers = $derived.by(() => {
		const q = query.trim().toLowerCase();
		if (!q) return users;
		return users.filter(
			(u) =>
				u.username.toLowerCase().includes(q) ||
				(u.displayName ?? '').toLowerCase().includes(q)
		);
	});

	const streamingCount = $derived(users.filter((u) => u.isStreaming).length);
	const totalPlays = $derived(users.reduce((sum, u) => sum + u.totalPlays, 0));
</script>

<svelte:head>
	<title>Users · Miru</title>
</svelte:head>

<div class="flex flex-col gap-5">
	{#if !authStore.isAdmin}
		<div role="alert" class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
			Administrator role is required to view media users.
		</div>
	{:else}
	<div class="flex flex-wrap items-center justify-between gap-3">
		<div>
			<h1 class="font-display text-2xl font-bold tracking-wide text-fg-primary sm:text-3xl">
				Users
			</h1>
			<p class="mt-0.5 text-xs text-fg-secondary sm:text-sm">
				{users.length} media users · {streamingCount} streaming now · {totalPlays.toLocaleString()} lifetime plays
			</p>
		</div>

		<div class="relative w-full sm:w-64">
			<Search class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-fg-muted" />
			<input
				type="search"
				bind:value={query}
				placeholder="Filter users…"
				aria-label="Filter users"
				class="w-full rounded-lg border border-input-border bg-surface py-2 pl-9 pr-3 text-xs text-fg-primary placeholder:text-fg-muted focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			/>
		</div>
	</div>

	<ServerFilterBar
		{selectedServerId}
		onchange={(serverId) => {
			selectedServerId = serverId;
			void loadUsers(serverId);
		}}
	/>

	{#if error}
		<div class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
			{error}
		</div>
	{:else if loading}
		<div class="rounded-xl border border-border bg-surface p-10 text-center font-mono text-xs text-fg-muted">
			Loading media users…
		</div>
	{:else if filteredUsers.length === 0}
		<div class="rounded-xl border border-border bg-surface p-10 text-center text-xs text-fg-muted">
			No matching users found.
		</div>
	{:else}
		<div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
			{#each filteredUsers as user (user.id)}
				<a
					href={`/users/${encodeURIComponent(user.id)}`}
					class={cn(
						'group miru-card-hover flex flex-col gap-4 rounded-xl border bg-surface p-4 hover:border-cyan hover:shadow-sm dark:hover:shadow-none',
						user.isStreaming ? 'border-border-strong' : 'border-border'
					)}
				>
					<div class="flex items-center justify-between gap-3">
						<div class="flex min-w-0 items-center gap-3">
							<ProxiedImage
								src={user.avatarUrl}
								alt={user.displayName || user.username}
								fallbackLabel={user.displayName || user.username}
								variant="avatar"
								class="h-11 w-11 text-base"
							/>
							<div class="min-w-0">
								<h2 class="truncate font-heading text-[15px] font-semibold text-fg-primary group-hover:text-cyan-hi">
									{user.displayName || user.username}
								</h2>
								<p class="truncate text-[11px] text-fg-muted">
									@{user.username} · {user.isStreaming ? 'Active now' : formatRelativeTime(user.lastSeenAtMs, Date.now(), preferencesStore.prefs)}
								</p>
							</div>
						</div>

						<div class="flex shrink-0 flex-col items-end gap-1">
							<Badge tone={user.isAdmin ? 'primary' : 'neutral'}>{user.isAdmin ? 'ADMIN' : 'USER'}</Badge>
							{#if user.isStreaming}
								<Badge tone="direct-play" dot pulse>LIVE · {user.activeStreamCount}</Badge>
							{/if}
							{#if serversStore.hasMultipleServers || serversStore.isDisconnected(user.serverId)}
								<ServerBadge
									serverId={user.serverId}
									serverType={user.serverType}
									serverName={user.serverName}
								/>
							{/if}
						</div>
					</div>

					<div
						class={cn(
							'flex items-center justify-between rounded-lg border px-3 py-2 text-[11px]',
							user.isStreaming
								? 'border-cyan/60 bg-cyan-soft text-cyan-hi font-medium'
								: 'border-border bg-elevated text-fg-secondary'
						)}
					>
						<span>
							{user.isStreaming
								? `Streaming ${user.activeStreamCount} active ${user.activeStreamCount === 1 ? 'session' : 'sessions'}`
								: `Last seen ${formatRelativeTime(user.lastSeenAtMs, Date.now(), preferencesStore.prefs)}`}
						</span>
						<span class="inline-flex items-center gap-0.5 font-mono text-[10px]">
							View
							<span
								aria-hidden="true"
								class="inline-block transition-transform duration-150 ease-[var(--ease-out-quart)] group-hover:translate-x-0.5 motion-reduce:group-hover:translate-x-0"
							>
								→
							</span>
						</span>
					</div>

					<div class="grid grid-cols-2 gap-3">
						<div>
							<p class="text-[10px] text-fg-muted">Total Plays</p>
							<p class="font-mono text-lg font-bold text-fg-primary tabular-nums">
								{user.totalPlays.toLocaleString()}
							</p>
						</div>
						<div>
							<p class="text-[10px] text-fg-muted">Watch Time</p>
							<p class="font-mono text-lg font-bold text-fg-primary tabular-nums">
								{formatDurationSec(user.totalWatchDurationSec)}
							</p>
						</div>
					</div>
				</a>
			{/each}
		</div>
	{/if}
	{/if}
</div>
