<script lang="ts">
	import { page } from '$app/state';
	import { ArrowLeft, Lock } from '@lucide/svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type { LibraryStatsResponse, RecentMediaItemResponse } from '$api/types';
	import LibrarySectionDetail from '$components/miru/LibrarySectionDetail.svelte';
	import ServerBadge from '$components/miru/ServerBadge.svelte';
	import UnlinkedUserEmptyState from '$components/miru/UnlinkedUserEmptyState.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { serversStore } from '$stores/servers.svelte';

	const sectionKey = $derived(page.params.key ?? '');
	const requestedServerId = $derived(page.url.searchParams.get('serverId')?.trim() || null);

	let stats = $state<LibraryStatsResponse | null>(null);
	let recentItems = $state<RecentMediaItemResponse[]>([]);
	let loading = $state(true);
	let accessDenied = $state(false);
	let error = $state<string | null>(null);

	async function loadSection(key: string, serverId: string | null) {
		if (!key) return;
		if (authStore.isUnlinkedUser) {
			stats = null;
			recentItems = [];
			loading = false;
			accessDenied = false;
			error = null;
			return;
		}
		loading = true;
		accessDenied = false;
		error = null;
		try {
			const [sRes, rRes] = await Promise.all([
				api.GET('/api/v1/libraries/{id}/stats', {
					params: {
						path: { id: key },
						...(serverId ? { query: { serverId } } : {})
					}
				}),
				api.GET('/api/v1/libraries/{id}/recent', {
					params: {
						path: { id: key },
						query: {
							limit: 12,
							...(serverId ? { serverId } : {})
						}
					}
				})
			]);
			stats = unwrap(sRes);
			recentItems = unwrap(rRes).items;
		} catch (e) {
			stats = null;
			recentItems = [];
			if (e instanceof MiruApiError && (e.status === 403 || e.status === 404)) {
				accessDenied = true;
				error = e.detail;
			} else {
				error = e instanceof MiruApiError ? e.detail : 'Failed to load library section';
			}
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void loadSection(sectionKey, requestedServerId);
	});
</script>

<svelte:head>
	<title>{stats ? `${stats.section.title} · Libraries · Miru` : 'Library · Miru'}</title>
</svelte:head>

<div class="flex flex-col gap-5">
	<div>
		<a href="/libraries" class="inline-flex items-center gap-1.5 font-mono text-xs text-cyan-hi hover:underline">
			<ArrowLeft class="h-3.5 w-3.5" /> Back to Libraries
		</a>
	</div>

	{#if authStore.isUnlinkedUser}
		<UnlinkedUserEmptyState context="shared libraries" />
	{:else if loading && !stats}
		<div class="rounded-xl border border-border bg-surface p-10 text-center font-mono text-xs text-fg-muted">
			Loading library section…
		</div>
	{:else if accessDenied}
		<div
			role="alert"
			class="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-border-strong bg-surface/60 px-6 py-12 text-center"
		>
			<div class="flex h-12 w-12 items-center justify-center rounded-full bg-elevated text-danger">
				<Lock class="h-5 w-5" />
			</div>
			<div class="flex max-w-md flex-col gap-1">
				<h1 class="font-heading text-base font-semibold text-fg-primary">
					Library access unavailable
				</h1>
				<p class="text-xs text-fg-secondary">
					This library section does not exist or is no longer shared with your account.
				</p>
			</div>
			<a
				href="/libraries"
				class="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 font-heading text-xs font-semibold text-primary-fg hover:bg-primary/85"
			>
				Return to Libraries
			</a>
		</div>
	{:else if error}
		<div role="alert" class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
			{error}
		</div>
	{:else if stats}
		<div>
			<div class="flex flex-wrap items-center gap-2">
				<h1 class="font-display text-2xl font-bold tracking-wide text-fg-primary sm:text-3xl">
					{stats.section.title}
				</h1>
				{#if serversStore.hasMultipleServers || serversStore.isDisconnected(stats.section.serverId)}
					<ServerBadge
						serverId={stats.section.serverId}
						serverType={stats.section.serverType}
						serverName={stats.section.serverName}
					/>
				{/if}
			</div>
			<p class="mt-0.5 text-xs text-fg-secondary sm:text-sm">
				Section #{stats.section.sectionKey} · {stats.section.itemCount.toLocaleString()} items
			</p>
		</div>

		<LibrarySectionDetail {stats} {recentItems} loadingSection={loading} />
	{/if}
</div>
