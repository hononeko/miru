<script lang="ts">
	import { api, MiruApiError, unwrap } from '$api/client';
	import type {
		AnalyticsMetricPreference,
		ClientStatsResponse,
		HeatmapRange,
		HeatmapStatsResponse,
		OverviewStatsResponse,
		PlexConnectionStatus,
		StatsRange
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import RangeTabs from '$components/ui/RangeTabs.svelte';
	import KpiCard from '$components/miru/KpiCard.svelte';
	import StreamCard from '$components/miru/StreamCard.svelte';
	import UnlinkedUserEmptyState from '$components/miru/UnlinkedUserEmptyState.svelte';
	import BarChart, { type BarPoint } from '$components/miru/BarChart.svelte';
	import ViewingHeatmap from '$components/miru/ViewingHeatmap.svelte';
	import ClientsCodecsPanel from '$components/miru/ClientsCodecsPanel.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { streamsStore } from '$stores/streams.svelte';
	import {
		formatBandwidthKbps,
		formatDurationSec,
		formatRelativeTime,
		formatStatPercent,
		statBarWidth
	} from '$lib/utils';
	import { Link2, Radio, Server, Tv } from 'lucide-svelte';

	let lastDefaultRange = preferencesStore.prefs.defaultStatsRange;
	let range = $state<StatsRange>(lastDefaultRange);
	let heatmapRange = $state<HeatmapRange>('90d');
	let overview = $state<OverviewStatsResponse | null>(null);
	let heatmap = $state<HeatmapStatsResponse | null>(null);
	let clientStats = $state<ClientStatsResponse | null>(null);
	let loadingStats = $state(true);
	let loadingHeatmap = $state(true);
	let statsError = $state<string | null>(null);
	let plexStatus = $state<PlexConnectionStatus | null>(null);

	$effect(() => {
		const nextDefault = preferencesStore.prefs.defaultStatsRange;
		if (nextDefault !== lastDefaultRange) {
			lastDefaultRange = nextDefault;
			range = nextDefault;
		}
	});

	$effect(() => {
		if (authStore.isAdmin) {
			void api.GET('/api/v1/admin/plex/status').then((res) => {
				if (res.data) plexStatus = res.data;
			}).catch(() => {});
		}
	});

	const analyticsMetric = $derived<AnalyticsMetricPreference>(
		preferencesStore.prefs.analyticsMetric
	);

	async function loadOverview(selectedRange: StatsRange, selectedMetric: AnalyticsMetricPreference) {
		if (authStore.isUnlinkedUser) {
			overview = null;
			clientStats = null;
			statsError = null;
			loadingStats = false;
			return;
		}
		loadingStats = true;
		statsError = null;
		try {
			const [overviewRes, clientsRes] = await Promise.all([
				api.GET('/api/v1/stats/overview', {
					params: { query: { range: selectedRange, metric: selectedMetric } }
				}),
				api.GET('/api/v1/stats/clients', {
					params: { query: { range: selectedRange, metric: selectedMetric } }
				})
			]);
			overview = unwrap(overviewRes);
			clientStats = unwrap(clientsRes);
		} catch (e) {
			statsError = e instanceof MiruApiError ? e.detail : 'Failed to load overview stats';
		} finally {
			loadingStats = false;
		}
	}

	async function loadHeatmap(
		selectedHeatmapRange: HeatmapRange,
		selectedMetric: AnalyticsMetricPreference
	) {
		if (authStore.isUnlinkedUser) {
			heatmap = null;
			loadingHeatmap = false;
			return;
		}
		loadingHeatmap = true;
		try {
			heatmap = unwrap(
				await api.GET('/api/v1/stats/heatmap', {
					params: { query: { range: selectedHeatmapRange, metric: selectedMetric } }
				})
			);
		} catch {
			// Keep previous heatmap state if overview already reports error
		} finally {
			loadingHeatmap = false;
		}
	}

	$effect(() => {
		void authStore.isUnlinkedUser;
		void loadOverview(range, analyticsMetric);
	});

	$effect(() => {
		void authStore.isUnlinkedUser;
		void loadHeatmap(heatmapRange, analyticsMetric);
	});

	const playingCount = $derived(
		streamsStore.streams.filter((s) => s.state.toLowerCase() === 'playing').length
	);
	const pausedCount = $derived(
		streamsStore.streams.filter((s) => s.state.toLowerCase() === 'paused').length
	);

	const liveBreakdown = $derived.by(() => {
		let dpCount = 0;
		let dpKbps = 0;
		let dsCount = 0;
		let dsKbps = 0;
		let hwCount = 0;
		let hwKbps = 0;
		let swCount = 0;
		let swKbps = 0;
		for (const s of streamsStore.streams) {
			const bw = s.technical.bandwidthKbps ?? 0;
			const dec = s.technical.streamDecision.toLowerCase();
			if (dec === 'direct_play') {
				dpCount++;
				dpKbps += bw;
			} else if (dec === 'direct_stream') {
				dsCount++;
				dsKbps += bw;
			} else if (s.technical.hwTranscode) {
				hwCount++;
				hwKbps += bw;
			} else {
				swCount++;
				swKbps += bw;
			}
		}
		const total = Math.max(1, streamsStore.totalBandwidthKbps);
		return [
			{ label: 'Direct Play', count: dpCount, kbps: dpKbps, pct: (dpKbps / total) * 100, barClass: 'bg-direct-play' },
			{ label: 'Direct Stream', count: dsCount, kbps: dsKbps, pct: (dsKbps / total) * 100, barClass: 'bg-direct-stream' },
			{ label: 'HW Transcode', count: hwCount, kbps: hwKbps, pct: (hwKbps / total) * 100, barClass: 'bg-hw-transcode' },
			{ label: 'SW Transcode', count: swCount, kbps: swKbps, pct: (swKbps / total) * 100, barClass: 'bg-sw-transcode' }
		];
	});

	const dailyBars = $derived.by<BarPoint[]>(() => {
		const days = overview?.playsPerDay ?? [];
		const tail = days.slice(-14);
		return tail.map((d) => ({
			label: d.date.slice(8, 10),
			value: analyticsMetric === 'duration' ? d.watchDurationSec : d.playCount,
			tooltip: `${d.date}: ${d.playCount} plays (${formatDurationSec(d.watchDurationSec)})`
		}));
	});

	const peakDay = $derived.by(() => {
		const days = overview?.playsPerDay ?? [];
		if (days.length === 0) return null;
		return days.reduce(
			(best, cur) =>
				(analyticsMetric === 'duration'
					? cur.watchDurationSec > best.watchDurationSec
					: cur.playCount > best.playCount)
					? cur
					: best,
			days[0]
		);
	});

	const topWatched = $derived.by(() => {
		if (!overview) return [];
		const combined = [
			...overview.topShows.map((s) => ({ ...s, kind: 'TV Show' })),
			...overview.topMovies.map((m) => ({ ...m, kind: 'Movie' }))
		];
		return combined
			.sort((a, b) =>
				analyticsMetric === 'duration'
					? b.watchDurationSec - a.watchDurationSec || b.playCount - a.playCount
					: b.playCount - a.playCount || b.watchDurationSec - a.watchDurationSec
			)
			.slice(0, 4);
	});
</script>

<svelte:head>
	<title>Live Activity · Miru</title>
</svelte:head>

<div class="flex flex-col gap-5">
	<!-- Page Header -->
	<div class="flex flex-wrap items-center justify-between gap-3">
		<div>
			<h1 class="font-display text-2xl font-bold tracking-wide text-fg-primary sm:text-3xl">
				Live Activity
			</h1>
			<p class="mt-0.5 text-xs text-fg-secondary sm:text-sm">
				Real-time streams via SSE · {range} telemetry overview
			</p>
		</div>
		<div class="flex items-center gap-2.5">
			<Badge
				tone={streamsStore.status === 'connected'
					? 'direct-play'
					: streamsStore.status === 'offline'
						? 'danger'
						: 'hw-transcode'}
				dot
				pulse={streamsStore.status === 'connected'}
				class="hidden sm:inline-flex"
			>
				SSE {streamsStore.status}
			</Badge>
			{#if !authStore.isUnlinkedUser}
				<RangeTabs value={range} onchange={(r) => (range = r)} />
			{/if}
		</div>
	</div>

	{#if authStore.isUnlinkedUser}
		<UnlinkedUserEmptyState context="live streams and playback telemetry" />
	{:else}
		<!-- KPI Strip -->
		{#if authStore.isAdmin && plexStatus?.credentialSource === 'NOT_CONFIGURED'}
		<div class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-cyan/40 bg-cyan-soft/50 px-4 py-3 text-xs">
			<div class="flex items-center gap-2.5">
				<Server class="h-4 w-4 shrink-0 text-cyan-hi" />
				<div>
					<p class="font-heading font-semibold text-fg-primary">Connect your Plex Media Server</p>
					<p class="text-fg-secondary">Sign in with Plex PIN OAuth in Admin to start tracking live streams and history.</p>
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

	<section class="grid grid-cols-2 gap-3 lg:grid-cols-4 lg:gap-4" aria-label="Key telemetry metrics">
		<KpiCard
			label="Active Streams"
			value={String(streamsStore.totalCount)}
			subtitle={`${playingCount} playing · ${pausedCount} paused`}
			dotClass="bg-direct-play"
		/>
		<KpiCard
			label="Total Bandwidth"
			value={formatBandwidthKbps(streamsStore.totalBandwidthKbps)}
			subtitle={`LAN ${formatBandwidthKbps(streamsStore.lanBandwidthKbps)} · WAN ${formatBandwidthKbps(streamsStore.wanBandwidthKbps)}`}
			dotClass="bg-cyan"
		/>
		<KpiCard
			label={`Direct Play (${range})`}
			value={overview ? formatStatPercent(overview.decisionBreakdown.directPlayPercent) : '—'}
			subtitle={overview
				? `${overview.decisionBreakdown.directPlay} Direct · ${overview.decisionBreakdown.directStream} Remux · ${overview.decisionBreakdown.transcode} Transcode`
				: loadingStats
					? 'Loading…'
					: 'Unavailable'}
			dotClass="bg-primary"
		>
			{#snippet extra()}
				{#if overview && overview.totalPlays > 0}
					<div class="flex h-1.5 w-full gap-0.5 overflow-hidden rounded-full bg-subtle">
						<div
							class={`h-full rounded-full bg-direct-play ${overview.decisionBreakdown.directPlay > 0 ? 'min-w-0.5' : ''}`}
							style:width={statBarWidth(overview.decisionBreakdown.directPlayPercent)}
						></div>
						<div
							class={`h-full rounded-full bg-cyan ${overview.decisionBreakdown.directStream > 0 ? 'min-w-0.5' : ''}`}
							style:width={statBarWidth(overview.decisionBreakdown.directStreamPercent)}
						></div>
						<div
							class={`h-full rounded-full bg-hw-transcode ${overview.decisionBreakdown.transcode > 0 ? 'min-w-0.5' : ''}`}
							style:width={statBarWidth(overview.decisionBreakdown.transcodePercent)}
						></div>
					</div>
				{/if}
			{/snippet}
		</KpiCard>
		<KpiCard
			label={`Watch Time (${range})`}
			value={overview ? formatDurationSec(overview.totalWatchDurationSec) : '—'}
			subtitle={overview ? `${overview.totalPlays} plays · ${overview.activeUsers} active users` : 'Loading…'}
			dotClass="bg-hw-transcode"
		/>
	</section>

	<!-- Active Streams Section -->
	<section class="flex flex-col gap-3">
		<div class="flex items-center justify-between gap-2">
			<h2 class="font-heading text-base font-semibold text-fg-primary">
				Active Streams ({streamsStore.totalCount})
			</h2>
			{#if streamsStore.lastUpdatedAtMs}
				<span class="font-mono text-[11px] text-fg-muted">
					Updated {formatRelativeTime(streamsStore.lastUpdatedAtMs, Date.now(), preferencesStore.prefs)}
				</span>
			{/if}
		</div>

		{#if streamsStore.streams.length === 0}
			<div
				class="flex flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-border-strong bg-surface/60 px-6 py-12 text-center"
			>
				<div class="flex h-11 w-11 items-center justify-center rounded-full bg-elevated text-cyan-hi">
					<Tv class="h-5 w-5" />
				</div>
				<p class="font-heading text-sm font-semibold text-fg-primary">No active streams right now</p>
				<p class="max-w-sm text-xs text-fg-secondary">
					Miru is subscribed to live server events. New playback sessions will appear here automatically without refreshing.
				</p>
			</div>
		{:else}
			<div class="grid grid-cols-1 gap-4 xl:grid-cols-2">
				{#each streamsStore.streams as stream (`${stream.serverId}:${stream.sessionKey}:${stream.media.ratingKey}`)}
					<StreamCard {stream} />
				{/each}

				{#if streamsStore.streams.length % 2 === 1}
					<!-- Live Session Breakdown Summary card balances odd stream grids on desktop -->
					<div
						class="hidden xl:flex flex-col justify-between gap-3 rounded-xl border border-border bg-surface p-4"
					>
						<div class="flex items-center justify-between">
							<h3 class="font-heading text-sm font-semibold text-fg-primary">Live Session Breakdown</h3>
							<Radio class="h-4 w-4 text-cyan-hi" />
						</div>
						<div class="flex flex-col gap-3">
							{#each liveBreakdown as row}
								<div class="flex flex-col gap-1">
									<div class="flex items-center justify-between text-xs">
										<span class="text-fg-secondary">{row.label}</span>
										<span class="font-mono text-[11px] text-fg-primary tabular-nums">
											{row.count} {row.count === 1 ? 'stream' : 'streams'} · {formatBandwidthKbps(row.kbps)}
										</span>
									</div>
									<div class="h-1.5 w-full overflow-hidden rounded-full bg-subtle">
										<div
											class={`h-full rounded-full ${row.barClass} ${row.count > 0 ? 'min-w-0.5' : ''}`}
											style:width={statBarWidth(row.pct)}
										></div>
									</div>
								</div>
							{/each}
						</div>
					</div>
				{/if}
			</div>
		{/if}
	</section>

	<!-- Overview Analytics Strip -->
	{#if statsError}
		<div class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
			{statsError}
		</div>
	{:else}
		<section class="grid grid-cols-1 gap-4 lg:grid-cols-3">
			<div class="flex flex-col justify-between gap-4 rounded-xl border border-border bg-surface p-4 lg:col-span-2">
				<div class="flex items-center justify-between gap-2">
					<h2 class="font-heading text-sm font-semibold text-fg-primary">
						{analyticsMetric === 'duration' ? 'Watch Time Per Day' : 'Plays Per Day'} ({range})
					</h2>
					{#if peakDay && (analyticsMetric === 'duration' ? peakDay.watchDurationSec > 0 : peakDay.playCount > 0)}
						<span class="font-mono text-[11px] font-medium text-primary-hover">
							Peak: {analyticsMetric === 'duration' ? formatDurationSec(peakDay.watchDurationSec) : `${peakDay.playCount} plays`} ({peakDay.date})
						</span>
					{/if}
				</div>
				<BarChart points={dailyBars} heightClass="h-36" />
			</div>

			<div class="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
				<h2 class="font-heading text-sm font-semibold text-fg-primary">Top Watched ({range})</h2>
				{#if topWatched.length === 0}
					<div class="flex flex-1 items-center justify-center rounded-lg bg-elevated/50 py-8 text-xs text-fg-muted">
						{loadingStats ? 'Loading stats…' : 'No playback history in this range'}
					</div>
				{:else}
					<div class="flex flex-col gap-2">
						{#each topWatched as item, idx}
							<button
								type="button"
								onclick={() =>
									void mediaDetailsModal.open(item.ratingKey, {
										preferSeries: item.kind === 'TV Show'
									})}
								class="group flex w-full cursor-pointer items-center justify-between gap-3 rounded-lg border border-transparent bg-elevated px-3 py-2 text-left transition-colors hover:border-cyan/50 hover:bg-subtle/60 focus-visible:border-cyan focus-visible:outline-none"
							>
								<div class="flex min-w-0 items-center gap-2.5">
									<span
										class={idx === 0
											? 'font-mono text-xs font-bold text-primary'
											: 'font-mono text-xs font-bold text-cyan-hi'}
									>
										{String(idx + 1).padStart(2, '0')}
									</span>
									<div class="min-w-0">
										<p class="truncate font-heading text-xs font-semibold text-fg-primary transition-colors group-hover:text-cyan-hi">
											{item.title}
										</p>
										<p class="text-[10px] text-fg-muted">
											{item.kind} · {item.playCount} {item.playCount === 1 ? 'play' : 'plays'}
										</p>
									</div>
								</div>
								<span class="shrink-0 font-mono text-[11px] font-semibold text-fg-secondary tabular-nums">
									{formatDurationSec(item.watchDurationSec)}
								</span>
							</button>
						{/each}
					</div>
				{/if}
			</div>
		</section>

		<!-- Watch Analytics: Viewing Heatmap + Clients, Codecs & Transcode Reasons -->
		<section class="flex flex-col gap-4" aria-label="Watch analytics">
			<ViewingHeatmap
				{heatmap}
				range={heatmapRange}
				metric={analyticsMetric}
				loading={loadingHeatmap}
				onchangeRange={(next) => (heatmapRange = next)}
			/>
			<ClientsCodecsPanel {clientStats} metric={analyticsMetric} loading={loadingStats} />
		</section>
	{/if}
	{/if}
</div>
