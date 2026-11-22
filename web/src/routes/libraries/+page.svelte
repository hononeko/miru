<script lang="ts">
	import { onMount } from 'svelte';
	import { Link2, RefreshCw, Server } from 'lucide-svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type {
		LibrarySectionResponse,
		LibraryStatsResponse,
		PlexConnectionStatus,
		RecentMediaItemResponse
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import BarChart, { type BarPoint } from '$components/miru/BarChart.svelte';
	import ProxiedImage from '$components/miru/ProxiedImage.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { cn, formatBytes, formatDurationSec, formatRelativeTime } from '$lib/utils';

	let libraries = $state<LibrarySectionResponse[]>([]);
	let selectedId = $state<string | null>(null);
	let stats = $state<LibraryStatsResponse | null>(null);
	let recentItems = $state<RecentMediaItemResponse[]>([]);
	let loadingList = $state(true);
	let loadingSection = $state(false);
	let syncing = $state(false);
	let syncNotice = $state<string | null>(null);
	let error = $state<string | null>(null);
	let plexStatus = $state<PlexConnectionStatus | null>(null);

	$effect(() => {
		if (authStore.isAdmin) {
			void api.GET('/api/v1/admin/plex/status').then((res) => {
				if (res.data) plexStatus = res.data;
			}).catch(() => {});
		}
	});

	async function loadLibraries() {
		loadingList = true;
		error = null;
		try {
			const res = unwrap(await api.GET('/api/v1/libraries'));
			libraries = res.libraries;
			if (!selectedId && res.libraries.length > 0) {
				selectedId = res.libraries[0].id;
			}
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Failed to load library sections';
		} finally {
			loadingList = false;
		}
	}

	async function loadSectionDetails(id: string) {
		loadingSection = true;
		try {
			const [sRes, rRes] = await Promise.all([
				api.GET('/api/v1/libraries/{id}/stats', { params: { path: { id } } }),
				api.GET('/api/v1/libraries/{id}/recent', { params: { path: { id }, query: { limit: 12 } } })
			]);
			stats = unwrap(sRes);
			recentItems = unwrap(rRes).items;
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Failed to load library details';
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
					params: { query: { deep: true } }
				})
			);
			syncNotice = `Synced ${summary.syncedSections} sections (${summary.totalItemsSynced.toLocaleString()} items)`;
			await loadLibraries();
			if (selectedId) await loadSectionDetails(selectedId);
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Library sync failed';
		} finally {
			syncing = false;
		}
	}

	onMount(() => {
		void loadLibraries();
	});

	$effect(() => {
		if (selectedId) {
			void loadSectionDetails(selectedId);
		}
	});

	const totalBytes = $derived(libraries.reduce((sum, l) => sum + l.totalSizeBytes, 0));
	const latestSync = $derived.by(() => {
		const times = libraries.map((l) => l.lastSyncedAtMs ?? 0).filter((t) => t > 0);
		return times.length > 0 ? Math.max(...times) : null;
	});

	const paletteColors = ['bg-cyan', 'bg-primary', 'bg-direct-play', 'bg-hw-transcode', 'bg-fg-muted'];

	function mapToDist(map: Record<string, number> | undefined) {
		const entries = Object.entries(map ?? {})
			.filter(([, count]) => count > 0)
			.sort((a, b) => b[1] - a[1])
			.slice(0, 5);
		const total = entries.reduce((sum, [, c]) => sum + c, 0);
		return entries.map(([name, count], idx) => ({
			name,
			count,
			pct: total > 0 ? (count / total) * 100 : 0,
			colorClass: paletteColors[idx % paletteColors.length]
		}));
	}

	const resDist = $derived(mapToDist(stats?.byResolution));
	const videoDist = $derived(mapToDist(stats?.byVideoCodec));
	const audioDist = $derived(mapToDist(stats?.byAudioCodec));

	const snapshotBars = $derived.by<BarPoint[]>(() => {
		const snaps = [...(stats?.snapshots ?? [])].reverse().slice(-8);
		return snaps.map((s) => ({
			label: new Date(s.capturedAtMs).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }),
			value: s.itemCount,
			tooltip: `${s.itemCount.toLocaleString()} items · ${formatBytes(s.totalSizeBytes)}`
		}));
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

	{#if authStore.isAdmin && plexStatus?.credentialSource === 'NOT_CONFIGURED'}
		<div class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-cyan/40 bg-cyan-soft/50 px-4 py-3 text-xs">
			<div class="flex items-center gap-2.5">
				<Server class="h-4 w-4 shrink-0 text-cyan-hi" />
				<div>
					<p class="font-heading font-semibold text-fg-primary">No Plex server connected</p>
					<p class="text-fg-secondary">Connect Plex via PIN OAuth in Admin before syncing library sections.</p>
				</div>
			</div>
			<a
				href="/admin"
				class="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 font-heading text-xs font-semibold text-white hover:bg-primary-hover"
			>
				<Link2 class="h-3.5 w-3.5" />
				Connect in Admin
			</a>
		</div>
	{/if}

	{#if syncNotice}
		<div class="rounded-xl border border-direct-play/50 bg-direct-play-soft px-4 py-2.5 text-xs text-direct-play">
			{syncNotice}
		</div>
	{/if}

	{#if error}
		<div class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
			{error}
		</div>
	{/if}

	{#if loadingList}
		<div class="rounded-xl border border-border bg-surface p-10 text-center font-mono text-xs text-fg-muted">
			Loading library sections…
		</div>
	{:else if libraries.length === 0}
		<div class="rounded-xl border border-border bg-surface p-10 text-center text-xs text-fg-muted">
			No library sections synced yet. Trigger a sync once Plex is connected.
		</div>
	{:else}
		<!-- Section Selector Cards -->
		<section class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 lg:gap-4">
			{#each libraries as lib (lib.id)}
				{@const selected = lib.id === selectedId}
				<button
					type="button"
					onclick={() => (selectedId = lib.id)}
					class={cn(
						'flex flex-col gap-2 rounded-xl border p-4 text-left transition-colors cursor-pointer',
						selected
							? 'border-cyan bg-cyan-soft/70 ring-1 ring-cyan'
							: 'border-border bg-surface hover:border-border-strong'
					)}
				>
					<div class="flex items-center justify-between gap-2">
						<span class="font-heading text-base font-semibold text-fg-primary">{lib.title}</span>
						<Badge tone={selected ? 'cyan' : 'neutral'} mono>{lib.sectionType.toUpperCase()}</Badge>
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

		<!-- Breakdown + Growth Row -->
		{#if stats}
			<section class="grid grid-cols-1 gap-4 lg:grid-cols-12">
				<div class="flex flex-col gap-4 rounded-xl border border-border bg-surface p-4 lg:col-span-7">
					<div class="flex items-center justify-between gap-2">
						<h2 class="font-heading text-sm font-semibold text-fg-primary">
							{stats.section.title} · Technical Breakdown
						</h2>
						<Badge tone="neutral" mono>SECTION #{stats.section.sectionKey}</Badge>
					</div>

					{#each [
						{ title: 'Video Resolution', rows: resDist },
						{ title: 'Video Codec', rows: videoDist },
						{ title: 'Audio Codec', rows: audioDist }
					] as group}
						<div class="flex flex-col gap-1.5">
							<span class="font-heading text-[11px] font-semibold text-fg-secondary">
								{group.title}
							</span>
							{#if group.rows.length === 0}
								<p class="text-[11px] text-fg-muted">Run a deep scan to populate codec breakdown.</p>
							{:else}
								<div class="flex h-2 w-full gap-1 overflow-hidden rounded-full bg-subtle">
									{#each group.rows as seg}
										<div
											class={`h-full rounded-full ${seg.colorClass}`}
											style:width={`${Math.max(2, seg.pct)}%`}
											title={`${seg.name}: ${seg.count} (${seg.pct.toFixed(1)}%)`}
										></div>
									{/each}
								</div>
								<div class="flex flex-wrap gap-x-3 gap-y-1">
									{#each group.rows as seg}
										<span class="inline-flex items-center gap-1.5 font-mono text-[10px] text-fg-secondary">
											<span class={`h-2 w-2 rounded-full ${seg.colorClass}`}></span>
											{seg.name} {seg.pct.toFixed(0)}%
										</span>
									{/each}
								</div>
							{/if}
						</div>
					{/each}
				</div>

				<div class="flex flex-col justify-between gap-4 rounded-xl border border-border bg-surface p-4 lg:col-span-5">
					<div class="flex items-start justify-between gap-2">
						<div>
							<h2 class="font-heading text-sm font-semibold text-fg-primary">
								Growth History (Snapshots)
							</h2>
							<p class="text-[11px] text-fg-muted">{stats.section.title} item count over time</p>
						</div>
						<Badge tone="direct-play" mono>{stats.snapshots.length} SNAPS</Badge>
					</div>

					<BarChart
						points={snapshotBars}
						heightClass="h-32"
						emptyMessage={loadingSection ? 'Loading snapshots…' : 'No snapshots captured yet'}
					/>

					<div class="flex items-center justify-between rounded-lg bg-elevated px-3 py-2 font-mono text-[11px]">
						<span class="text-fg-secondary">
							Runtime: {formatDurationSec(Math.floor(stats.section.totalDurationMs / 1000))}
						</span>
						<span class="text-cyan-hi">{formatBytes(stats.section.totalSizeBytes)}</span>
					</div>
				</div>
			</section>

			<!-- Recently Added Poster Grid -->
			<section class="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
				<div class="flex items-center justify-between gap-2">
					<h2 class="font-heading text-sm font-semibold text-fg-primary">
						Recently Added · {stats.section.title}
					</h2>
					<span class="hidden font-mono text-[10px] text-fg-muted sm:inline">
						Proxied via /api/v1/media/&#123;ratingKey&#125;/thumb
					</span>
				</div>

				{#if recentItems.length === 0}
					<p class="py-8 text-center text-xs text-fg-muted">
						No recently added items cached for this section yet.
					</p>
				{:else}
					<div class="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6">
						{#each recentItems as item (item.ratingKey)}
							<div class="flex flex-col gap-1.5">
								<ProxiedImage
									src={item.thumbUrl}
									alt={item.title}
									fallbackLabel={item.title}
									variant="poster"
									class="aspect-[2/3] w-full"
								/>
								<div class="flex items-center justify-between gap-1">
									<p class="truncate font-heading text-xs font-semibold text-fg-primary">
										{item.title}
									</p>
									{#if item.videoResolution}
										<Badge tone="cyan" mono class="px-1.5 py-0 text-[9px]">
											{item.videoResolution.toUpperCase()}
										</Badge>
									{/if}
								</div>
								<p class="truncate font-mono text-[10px] text-fg-muted">
									{[item.fileSizeBytes ? formatBytes(item.fileSizeBytes) : null, formatRelativeTime(item.addedAtMs)]
										.filter(Boolean)
										.join(' · ')}
								</p>
							</div>
						{/each}
					</div>
				{/if}
			</section>
		{/if}
	{/if}
</div>
