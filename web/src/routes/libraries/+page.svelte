<script lang="ts">
	import { onMount } from 'svelte';
	import { Link2, RefreshCw, Server } from '@lucide/svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type {
		LibrarySectionResponse,
		LibraryStatsResponse,
		PlexConnectionStatus,
		RecentMediaItemResponse
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import LibrarySectionDetail from '$components/miru/LibrarySectionDetail.svelte';
	import ServerBadge from '$components/miru/ServerBadge.svelte';
	import ServerFilterBar from '$components/miru/ServerFilterBar.svelte';
	import UnlinkedUserEmptyState from '$components/miru/UnlinkedUserEmptyState.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { serversStore } from '$stores/servers.svelte';
	import { cn, formatBytes, formatDurationSec, formatRelativeTime } from '$lib/utils';

	let libraries = $state<LibrarySectionResponse[]>([]);
	let selectedServerId = $state<string | null>(null);
	let selectedKey = $state<string | null>(null);
	let stats = $state<LibraryStatsResponse | null>(null);
	let recentItems = $state<RecentMediaItemResponse[]>([]);
	let loadingList = $state(true);
	let loadingSection = $state(false);
	let syncing = $state(false);
	let syncNotice = $state<string | null>(null);
	let error = $state<string | null>(null);
	let plexStatus = $state<PlexConnectionStatus | null>(null);

	function libraryKey(lib: Pick<LibrarySectionResponse, 'serverId' | 'sectionKey'>): string {
		return `${lib.serverId}:${lib.sectionKey}`;
	}

	const selectedLibrary = $derived(
		selectedKey ? libraries.find((lib) => libraryKey(lib) === selectedKey) ?? null : null
	);

	$effect(() => {
		if (authStore.isAdmin) {
			void api.GET('/api/v1/admin/plex/status').then((res) => {
				if (res.data) plexStatus = res.data;
			}).catch(() => {});
		}
	});

	async function loadLibraries(serverFilter: string | null = selectedServerId) {
		if (authStore.isUnlinkedUser) {
			libraries = [];
			selectedKey = null;
			stats = null;
			recentItems = [];
			error = null;
			loadingList = false;
			return;
		}
		loadingList = true;
		error = null;
		try {
			const res = unwrap(
				await api.GET('/api/v1/libraries', serverFilter ? { params: { query: { serverId: serverFilter } } } : undefined)
			);
			libraries = res.libraries;
			if (res.libraries.length === 0) {
				selectedKey = null;
				stats = null;
				recentItems = [];
			} else if (!selectedKey || !res.libraries.some((lib) => libraryKey(lib) === selectedKey)) {
				selectedKey = libraryKey(res.libraries[0]);
			}
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Failed to load library sections';
		} finally {
			loadingList = false;
		}
	}

	async function loadSectionDetails(lib: LibrarySectionResponse) {
		loadingSection = true;
		try {
			const [sRes, rRes] = await Promise.all([
				api.GET('/api/v1/libraries/{id}/stats', {
					params: {
						path: { id: lib.sectionKey },
						...(lib.serverId ? { query: { serverId: lib.serverId } } : {})
					}
				}),
				api.GET('/api/v1/libraries/{id}/recent', {
					params: {
						path: { id: lib.sectionKey },
						query: {
							limit: 12,
							...(lib.serverId ? { serverId: lib.serverId } : {})
						}
					}
				})
			]);
			stats = unwrap(sRes);
			recentItems = unwrap(rRes).items;
		} catch (e) {
			if (e instanceof MiruApiError && (e.status === 403 || e.status === 404)) {
				stats = null;
				recentItems = [];
				selectedKey = null;
				error = 'Access to this library is no longer available';
				void loadLibraries(selectedServerId);
			} else {
				error = e instanceof MiruApiError ? e.detail : 'Failed to load library details';
			}
		} finally {
			loadingSection = false;
		}
	}

	async function triggerSync() {
		syncing = true;
		syncNotice = null;
		error = null;
		try {
			const summary = unwrap(
				await api.POST('/api/v1/libraries/sync', {
					params: {
						query: {
							deep: true,
							...(selectedServerId ? { serverId: selectedServerId } : {})
						}
					}
				})
			);
			syncNotice = `Synced ${summary.syncedSections} sections (${summary.totalItemsSynced.toLocaleString()} items)`;
			await loadLibraries(selectedServerId);
			if (selectedLibrary) await loadSectionDetails(selectedLibrary);
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Library sync failed';
		} finally {
			syncing = false;
		}
	}

	onMount(() => {
		void loadLibraries(selectedServerId);
	});

	$effect(() => {
		if (selectedServerId && serversStore.loaded && !serversStore.byId(selectedServerId)) {
			selectedServerId = null;
			void loadLibraries(null);
		}
	});

	$effect(() => {
		if (selectedLibrary && !authStore.isUnlinkedUser) {
			void loadSectionDetails(selectedLibrary);
		}
	});

	const totalBytes = $derived(libraries.reduce((sum, l) => sum + l.totalSizeBytes, 0));
	const latestSync = $derived.by(() => {
		const times = libraries.map((l) => l.lastSyncedAtMs ?? 0).filter((t) => t > 0);
		return times.length > 0 ? Math.max(...times) : null;
	});
</script>

<svelte:head>
	<title>Libraries · Miru</title>
</svelte:head>

<div class="flex flex-col gap-5">
	<!-- Header -->
	<div class="flex flex-wrap items-center justify-between gap-3">
		<div>
			<h1 class="font-display text-2xl font-bold tracking-wide text-fg-primary sm:text-3xl">
				Libraries
			</h1>
			<p class="mt-0.5 text-xs text-fg-secondary sm:text-sm">
				{libraries.length} sections · {formatBytes(totalBytes)} total storage
			</p>
		</div>

		<div class="flex items-center gap-3">
			{#if latestSync}
				<span class="hidden font-mono text-[11px] text-fg-muted sm:inline">
					Last synced {formatRelativeTime(latestSync)}
				</span>
			{/if}
			{#if authStore.isAdmin}
				<Button variant="primary" size="md" disabled={syncing} onclick={triggerSync}>
					<RefreshCw class={cn('h-3.5 w-3.5', syncing && 'animate-spin')} />
					{syncing ? 'Syncing…' : 'Sync Libraries'}
				</Button>
			{/if}
		</div>
	</div>

	<ServerFilterBar
		{selectedServerId}
		onchange={(serverId) => {
			selectedServerId = serverId;
			selectedKey = null;
			void loadLibraries(serverId);
		}}
	/>

	{#if authStore.isAdmin && plexStatus?.credentialSource === 'NOT_CONFIGURED' && !serversStore.servers.some((s) => s.configured)}
		<div class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-cyan/40 bg-cyan-soft/50 px-4 py-3 text-xs">
			<div class="flex items-center gap-2.5">
				<Server class="h-4 w-4 shrink-0 text-cyan-hi" />
				<div>
					<p class="font-heading font-semibold text-fg-primary">No media server connected</p>
					<p class="text-fg-secondary">Connect Plex via PIN OAuth or Jellyfin via Quick Connect in Admin before syncing library sections.</p>
				</div>
			</div>
			<a
				href="/admin"
				class="btn-sheen inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 font-heading text-xs font-semibold text-primary-fg transition-[background-color,box-shadow,transform] duration-150 ease-[var(--ease-out-quart)] hover:bg-primary/90 hover:-translate-y-px hover:shadow-[0_4px_14px_-3px_color-mix(in_srgb,var(--primary)_45%,transparent)] active:translate-y-0 active:scale-[0.98] motion-reduce:hover:translate-y-0 motion-reduce:active:scale-100"
			>
				<Link2 class="h-3.5 w-3.5" />
				Connect in Admin
			</a>
		</div>
	{/if}

	{#if syncNotice}
		<div class="animate-fade-up rounded-xl border border-direct-play/50 bg-direct-play-soft px-4 py-2.5 text-xs text-direct-play">
			{syncNotice}
		</div>
	{/if}

	{#if error}
		<div class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
			{error}
		</div>
	{/if}

	{#if authStore.isUnlinkedUser}
		<UnlinkedUserEmptyState context="shared libraries" />
	{:else if loadingList}
		<div class="rounded-xl border border-border bg-surface p-10 text-center font-mono text-xs text-fg-muted">
			Loading library sections…
		</div>
	{:else if libraries.length === 0}
		{#if authStore.isAdmin}
			<div class="rounded-xl border border-border bg-surface p-10 text-center text-xs text-fg-muted">
				No library sections synced yet. Trigger a sync once a media server is connected.
			</div>
		{:else}
			<div class="rounded-xl border border-border bg-surface p-10 text-center">
				<h2 class="font-heading text-base font-semibold text-fg-primary">
					No libraries shared with your account
				</h2>
				<p class="mt-1.5 text-xs text-fg-secondary">
					No media libraries are currently shared with your linked media user, or library sharing permissions have not synced yet.
				</p>
			</div>
		{/if}
	{:else}
		<!-- Section Selector Cards -->
		<section class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 lg:gap-4">
			{#each libraries as lib (libraryKey(lib))}
				{@const key = libraryKey(lib)}
				{@const selected = key === selectedKey}
				<button
					type="button"
					onclick={() => (selectedKey = key)}
					class={cn(
						'miru-card-hover flex flex-col gap-2 rounded-xl border p-4 text-left active:scale-[0.99] motion-reduce:active:scale-100 cursor-pointer',
						selected
							? 'border-cyan bg-cyan-soft/70 ring-1 ring-cyan'
							: 'border-border bg-surface hover:border-border-strong'
					)}
				>
					<div class="flex items-center justify-between gap-2">
						<span class="font-heading text-base font-semibold text-fg-primary">{lib.title}</span>
						<div class="flex shrink-0 items-center gap-1.5">
							{#if serversStore.hasMultipleServers || serversStore.isDisconnected(lib.serverId)}
								<ServerBadge
									serverId={lib.serverId}
									serverType={lib.serverType}
									serverName={lib.serverName}
								/>
							{/if}
							<Badge tone={selected ? 'cyan' : 'neutral'} mono>{lib.sectionType.toUpperCase()}</Badge>
						</div>
					</div>
					<div class="flex items-baseline justify-between gap-2">
						<span class="font-mono text-2xl font-bold text-fg-primary tabular-nums">
							{lib.itemCount.toLocaleString()}
						</span>
						<span class={cn('font-mono text-sm font-semibold tabular-nums', selected ? 'text-cyan-hi' : 'text-fg-secondary')}>
							{formatBytes(lib.totalSizeBytes)}
						</span>
					</div>
					<p class="truncate text-[11px] text-fg-muted">
						{#if lib.sectionType === 'show'}
							{lib.childCount.toLocaleString()} seasons · {lib.leafCount.toLocaleString()} episodes
						{:else if lib.sectionType === 'artist'}
							{lib.childCount.toLocaleString()} albums · {lib.leafCount.toLocaleString()} tracks
						{:else}
							{formatDurationSec(Math.floor(lib.totalDurationMs / 1000))} total runtime
						{/if}
					</p>
				</button>
			{/each}
		</section>

		<!-- Breakdown + Growth Row + Recently Added -->
		{#if stats}
			<LibrarySectionDetail {stats} {recentItems} {loadingSection} />
		{/if}
	{/if}
</div>
