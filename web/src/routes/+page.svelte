<script lang="ts">
	import { api, MiruApiError, unwrap } from '$api/client';
	import type {
		ActiveStreamDto,
		AnalyticsMetricPreference,
		ClientStatsResponse,
		HeatmapRange,
		HeatmapStatsResponse,
		OverviewStatsResponse,
		PlexConnectionStatus,
		StatsRange,
		TerminateStreamResponse
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import RangeTabs from '$components/ui/RangeTabs.svelte';
	import KpiCard from '$components/miru/KpiCard.svelte';
	import StreamCard from '$components/miru/StreamCard.svelte';
	import TerminateStreamSheet from '$components/miru/TerminateStreamSheet.svelte';
	import UnlinkedUserEmptyState from '$components/miru/UnlinkedUserEmptyState.svelte';
	import BarChart, { type BarPoint, type BarSegment } from '$components/miru/BarChart.svelte';
	import ViewingHeatmap from '$components/miru/ViewingHeatmap.svelte';
	import ClientsCodecsPanel from '$components/miru/ClientsCodecsPanel.svelte';
	import { latestRequest } from '$lib/latestRequest';
	import { authStore } from '$stores/auth.svelte';
	import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { streamsStore } from '$stores/streams.svelte';
	import {
		cn,
		formatBandwidthKbps,
		formatCalendarDate,
		formatDurationSec,
		formatRelativeTime,
		formatStatPercent,
		getCalendarDateAxisParts,
		statBarWidth,
		streamKey
	} from '$lib/utils';
	import { CheckCircle2, Link2, Radio, Server, Tv, X } from 'lucide-svelte';

	let lastDefaultRange = preferencesStore.prefs.defaultStatsRange;
	let range = $state<StatsRange>(lastDefaultRange);
	let heatmapRange = $state<HeatmapRange>('7d');
	let overview = $state<OverviewStatsResponse | null>(null);
	let heatmap = $state<HeatmapStatsResponse | null>(null);
	let clientStats = $state<ClientStatsResponse | null>(null);
	let loadingStats = $state(true);
	let loadingHeatmap = $state(true);
	let statsError = $state<string | null>(null);
	let plexStatus = $state<PlexConnectionStatus | null>(null);
	let terminateTarget = $state<ActiveStreamDto | null>(null);
	let terminateSheetOpen = $state(false);
	let terminatingKeys = $state<string[]>([]);
	let terminationNotice = $state<{ message: string; reason: string } | null>(null);

	$effect(() => {
		const activeKeys = new Set(streamsStore.streams.map((s) => streamKey(s)));
		if (terminatingKeys.some((key) => !activeKeys.has(key))) {
			terminatingKeys = terminatingKeys.filter((key) => activeKeys.has(key));
		}
	});

	function openTerminateSheet(stream: ActiveStreamDto) {
		terminateTarget = stream;
		terminateSheetOpen = true;
	}

	function handleStreamTerminated(result: TerminateStreamResponse, stream: ActiveStreamDto) {
		const key = streamKey(stream);
		if (!terminatingKeys.includes(key)) {
			terminatingKeys = [...terminatingKeys, key];
		}
		const userLabel = stream.user.displayName || stream.user.username || 'viewer';
		terminationNotice = {
			message: `Termination requested for ${userLabel} — ${stream.media.title}`,
			reason: result.reason
		};
	}

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

	const startOverviewRequest = latestRequest();
	const startHeatmapRequest = latestRequest();

	async function loadOverview(selectedRange: StatsRange, selectedMetric: AnalyticsMetricPreference) {
		const isLatest = startOverviewRequest();
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
			if (!isLatest()) return;
			overview = unwrap(overviewRes);
			clientStats = unwrap(clientsRes);
		} catch (e) {
			if (!isLatest()) return;
			statsError = e instanceof MiruApiError ? e.detail : 'Failed to load overview stats';
		} finally {
			if (isLatest()) loadingStats = false;
		}
	}

	async function loadHeatmap(
		selectedHeatmapRange: HeatmapRange,
		selectedMetric: AnalyticsMetricPreference
	) {
		const isLatest = startHeatmapRequest();
		if (authStore.isUnlinkedUser) {
			heatmap = null;
			loadingHeatmap = false;
			return;
		}
		loadingHeatmap = true;
		try {
			const res = unwrap(
				await api.GET('/api/v1/stats/heatmap', {
					params: { query: { range: selectedHeatmapRange, metric: selectedMetric } }
				})
			);
			if (isLatest()) heatmap = res;
		} catch {
			// Keep previous heatmap state if overview already reports error
		} finally {
			if (isLatest()) loadingHeatmap = false;
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

	let nowMs = $state(Date.now());

	$effect(() => {
		if (streamsStore.lastUpdatedAtMs === null) return;
		nowMs = Date.now();
		const timer = setInterval(() => {
			nowMs = Date.now();
		}, 1_000);
		return () => clearInterval(timer);
	});

	const showLastUpdated = $derived(
		streamsStore.lastUpdatedAtMs !== null && nowMs - streamsStore.lastUpdatedAtMs > 10_000
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

	const DAILY_USER_COLORS = ['bg-chart-1', 'bg-chart-2', 'bg-chart-3', 'bg-chart-4'] as const;
	const DAILY_OTHER_COLOR = 'bg-fg-muted';

	function userDisplayLabel(user: { displayName?: string | null; username: string }): string {
		return user.displayName?.trim() || user.username;
	}

	function dailyMediaKindLabel(mediaType: string): string {
		const normalized = mediaType.toLowerCase();
		if (normalized === 'show' || normalized === 'episode') return 'TV Show';
		if (normalized === 'movie') return 'Movie';
		if (normalized === 'track') return 'Music';
		if (normalized === 'live_tv') return 'Live TV';
		return normalized ? normalized.charAt(0).toUpperCase() + normalized.slice(1) : 'Media';
	}

	const dailyDays = $derived(overview?.playsPerDay ?? []);

	const dailyUserLegend = $derived.by(() => {
		const totals = new Map<string, { id: string; label: string; total: number }>();
		for (const day of dailyDays) {
			for (const u of day.users ?? []) {
				const val = analyticsMetric === 'duration' ? u.watchDurationSec : u.playCount;
				const label = userDisplayLabel(u);
				const prev = totals.get(u.userId);
				if (prev) {
					prev.total += val;
				} else {
					totals.set(u.userId, { id: u.userId, label, total: val });
				}
			}
		}
		const sorted = Array.from(totals.values()).sort(
			(a, b) => b.total - a.total || a.label.localeCompare(b.label)
		);
		const top = sorted.slice(0, DAILY_USER_COLORS.length).map((u, idx) => ({
			id: u.id,
			label: u.label,
			colorClass: DAILY_USER_COLORS[idx] as string
		}));
		if (sorted.length > DAILY_USER_COLORS.length) {
			top.push({ id: '__other__', label: 'Other', colorClass: DAILY_OTHER_COLOR });
		}
		return top;
	});

	const dailyUserColorMap = $derived.by(() => {
		const map = new Map<string, string>();
		for (const item of dailyUserLegend) {
			if (item.id !== '__other__') {
				map.set(item.id, item.colorClass);
			}
		}
		return map;
	});

	const dailyBars = $derived.by<BarPoint[]>(() => {
		const days = dailyDays;
		const showStackedUsers = dailyUserLegend.length > 1;
		const colorOrder = new Map(dailyUserLegend.map((item, i) => [item.id, i]));
		return days.map((d, idx) => {
			const axis = getCalendarDateAxisParts(d.date, idx > 0 ? days[idx - 1].date : null);
			const value = analyticsMetric === 'duration' ? d.watchDurationSec : d.playCount;
			let segments: BarSegment[] | undefined;
			if (showStackedUsers && (d.users?.length ?? 0) > 0) {
				const bucketTotals = new Map<string, { label: string; value: number; colorClass: string; order: number }>();
				for (const u of d.users) {
					const uVal = analyticsMetric === 'duration' ? u.watchDurationSec : u.playCount;
					if (uVal <= 0) continue;
					const mappedColor = dailyUserColorMap.get(u.userId);
					const key = mappedColor ? u.userId : '__other__';
					const label = mappedColor ? userDisplayLabel(u) : 'Other';
					const colorClass = mappedColor ?? DAILY_OTHER_COLOR;
					const order = colorOrder.get(key) ?? 99;
					const prev = bucketTotals.get(key);
					if (prev) {
						prev.value += uVal;
					} else {
						bucketTotals.set(key, { label, value: uVal, colorClass, order });
					}
				}
				segments = Array.from(bucketTotals.values())
					.sort((a, b) => a.order - b.order)
					.map(({ label, value: segValue, colorClass }) => ({
						label,
						value: segValue,
						colorClass
					}));
			}
			const formattedDate = formatCalendarDate(d.date, preferencesStore.prefs.dateFormat);
			const playsText = `${d.playCount} ${d.playCount === 1 ? 'play' : 'plays'}`;
			return {
				id: d.date,
				label: axis.dayLabel,
				subLabel: axis.monthLabel,
				value,
				tooltip: `${formattedDate}: ${playsText} (${formatDurationSec(d.watchDurationSec)})`,
				ariaLabel: `${formattedDate}: ${playsText}, ${formatDurationSec(d.watchDurationSec)}`,
				segments
			};
		});
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
						: 'warning'}
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
				class="btn-sheen inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 font-heading text-xs font-semibold text-primary-fg transition-[background-color,box-shadow,transform] duration-150 ease-[var(--ease-out-quart)] hover:bg-primary/90 hover:-translate-y-px hover:shadow-[0_4px_14px_-3px_color-mix(in_srgb,var(--primary)_45%,transparent)] active:translate-y-0 active:scale-[0.98] motion-reduce:hover:translate-y-0 motion-reduce:active:scale-100"
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
							class={`h-full rounded-full bg-direct-play transition-[width] duration-500 ease-[var(--ease-out-quart)] ${overview.decisionBreakdown.directPlay > 0 ? 'min-w-0.5' : ''}`}
							style:width={statBarWidth(overview.decisionBreakdown.directPlayPercent)}
						></div>
						<div
							class={`h-full rounded-full bg-direct-stream transition-[width] duration-500 ease-[var(--ease-out-quart)] ${overview.decisionBreakdown.directStream > 0 ? 'min-w-0.5' : ''}`}
							style:width={statBarWidth(overview.decisionBreakdown.directStreamPercent)}
						></div>
						<div
							class={`h-full rounded-full bg-hw-transcode transition-[width] duration-500 ease-[var(--ease-out-quart)] ${overview.decisionBreakdown.transcode > 0 ? 'min-w-0.5' : ''}`}
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
			dotClass="bg-chart-3"
		/>
	</section>

	<!-- Active Streams Section -->
	<section class="flex flex-col gap-3">
		<div class="flex items-center justify-between gap-2">
			<h2 class="font-heading text-base font-semibold text-fg-primary">
				Active Streams ({streamsStore.totalCount})
			</h2>
			{#if showLastUpdated && streamsStore.lastUpdatedAtMs}
				<span class="font-mono text-[11px] text-fg-muted">
					Updated {formatRelativeTime(streamsStore.lastUpdatedAtMs, nowMs, preferencesStore.prefs)}
				</span>
			{/if}
		</div>

		{#if terminationNotice}
			<div
				role="status"
				class="animate-fade-up flex items-start justify-between gap-3 rounded-xl border border-direct-play/40 bg-direct-play-soft px-3.5 py-2.5 text-xs"
			>
				<div class="flex items-start gap-2.5">
					<CheckCircle2 class="mt-0.5 h-4 w-4 shrink-0 text-direct-play" />
					<div>
						<p class="font-heading font-semibold text-fg-primary">{terminationNotice.message}</p>
						<p class="mt-0.5 text-[11px] text-fg-secondary">
							Viewer message: &ldquo;{terminationNotice.reason}&rdquo; · Waiting for SSE stream removal
						</p>
					</div>
				</div>
				<button
					type="button"
					onclick={() => (terminationNotice = null)}
					aria-label="Dismiss termination notice"
					class="cursor-pointer rounded-md p-1 text-fg-muted hover:bg-surface hover:text-fg-primary"
				>
					<X class="h-3.5 w-3.5" />
				</button>
			</div>
		{/if}

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
			{@const isEvenStreams = streamsStore.streams.length % 2 === 0}
			<div class="grid grid-cols-1 gap-4 xl:grid-cols-2">
				{#each streamsStore.streams as stream (`${stream.serverId}:${stream.sessionKey}:${stream.media.ratingKey}`)}
					<StreamCard
						{stream}
						onTerminate={authStore.isAdmin ? openTerminateSheet : undefined}
						terminating={terminatingKeys.includes(streamKey(stream))}
					/>
				{/each}

				<div
					class={cn(
						'flex flex-col justify-between gap-3 rounded-xl border border-border bg-surface p-4',
						isEvenStreams && 'xl:col-span-2'
					)}
				>
					<div class="flex items-center justify-between">
						<h3 class="font-heading text-sm font-semibold text-fg-primary">Live Session Breakdown</h3>
						<Radio class="h-4 w-4 text-cyan-hi" />
					</div>
					<div
						class={cn(
							'grid grid-cols-1 gap-3 sm:grid-cols-2',
							isEvenStreams ? 'xl:grid-cols-4' : 'xl:grid-cols-1'
						)}
					>
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
										class={`h-full rounded-full transition-[width] duration-500 ease-[var(--ease-out-quart)] ${row.barClass} ${row.count > 0 ? 'min-w-0.5' : ''}`}
										style:width={statBarWidth(row.pct)}
									></div>
								</div>
							</div>
						{/each}
					</div>
				</div>
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
			<div class="flex flex-col justify-between gap-3 rounded-xl border border-border bg-surface p-4 lg:col-span-2">
				<div class="flex flex-wrap items-start justify-between gap-2">
					<div>
						<h2 class="font-heading text-sm font-semibold text-fg-primary">
							{analyticsMetric === 'duration' ? 'Watch Time Per Day' : 'Plays Per Day'} ({range})
						</h2>
						<p class="mt-0.5 text-[11px] text-fg-muted">
							Daily breakdown by media &amp; viewer · Click or tap any bar for details
						</p>
					</div>
					{#if peakDay && (analyticsMetric === 'duration' ? peakDay.watchDurationSec > 0 : peakDay.playCount > 0)}
						<span class="font-mono text-[11px] font-medium text-primary-text">
							Peak: {analyticsMetric === 'duration' ? formatDurationSec(peakDay.watchDurationSec) : `${peakDay.playCount} plays`} ({peakDay.date})
						</span>
					{/if}
				</div>

				{#if dailyUserLegend.length > 1}
					<div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-[10px] text-fg-secondary">
						{#each dailyUserLegend as entry (entry.id)}
							<span class="inline-flex items-center gap-1.5">
								<span class={cn('h-2 w-2 rounded-full', entry.colorClass)}></span>
								<span class="font-medium text-fg-primary">{entry.label}</span>
							</span>
						{/each}
					</div>
				{/if}

				<BarChart points={dailyBars} heightClass="h-36" highlightPeak={dailyUserLegend.length <= 1}>
					{#snippet detail(_point, idx, close)}
						{@const day = dailyDays[idx]}
						{#if day}
							<div class="flex flex-col gap-2.5 text-xs">
								<div class="flex items-center justify-between gap-2 border-b border-border pb-2">
									<div class="flex items-center gap-1.5">
										<span class="font-mono text-xs font-semibold text-fg-primary">
											{formatCalendarDate(day.date, preferencesStore.prefs.dateFormat)}
										</span>
										{#if peakDay?.date === day.date}
											<Badge tone="primary" mono>PEAK</Badge>
										{/if}
									</div>
									<span class="font-mono text-[11px] text-cyan-hi tabular-nums">
										{day.playCount} {day.playCount === 1 ? 'play' : 'plays'} · {formatDurationSec(day.watchDurationSec)}
									</span>
								</div>

								{#if day.users.length > 0}
									<div class="flex flex-col gap-1">
										<span class="font-heading text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
											Viewers
										</span>
										<div class="flex flex-wrap gap-1.5">
											{#each day.users as viewer (viewer.userId)}
												{@const viewerColor = dailyUserLegend.length > 1 ? (dailyUserColorMap.get(viewer.userId) ?? DAILY_OTHER_COLOR) : null}
												<span class="inline-flex items-center gap-1.5 rounded-md border border-border bg-elevated px-2 py-0.5 text-[11px] text-fg-primary">
													{#if viewerColor}
														<span class={cn('h-1.5 w-1.5 rounded-full', viewerColor)}></span>
													{/if}
													<span class="font-medium">{userDisplayLabel(viewer)}</span>
													<span class="font-mono text-[10px] text-fg-muted tabular-nums">
														{analyticsMetric === 'duration'
															? formatDurationSec(viewer.watchDurationSec)
															: viewer.playCount}
													</span>
												</span>
											{/each}
										</div>
									</div>
								{/if}

								{#if day.media.length > 0}
									<div class="flex flex-col gap-1">
										<span class="font-heading text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
											Played Media
										</span>
										<div class="flex max-h-48 flex-col gap-1 overflow-y-auto">
											{#each day.media as item (`${item.mediaType}:${item.ratingKey}:${item.title}`)}
												<button
													type="button"
													onclick={() => {
														close();
														void mediaDetailsModal.open(item.ratingKey, {
															preferSeries: item.mediaType === 'show'
														});
													}}
													class="group flex w-full cursor-pointer items-center justify-between gap-2 rounded-lg bg-elevated px-2.5 py-1.5 text-left transition-colors hover:bg-subtle/80 focus-visible:outline-1 focus-visible:outline-cyan"
												>
													<div class="min-w-0">
														<p class="truncate font-heading text-[11px] font-semibold text-fg-primary group-hover:text-cyan-hi">
															{item.title}
														</p>
														<p class="truncate text-[10px] text-fg-muted">
															{dailyMediaKindLabel(item.mediaType)}{item.users.length > 0 ? ` · ${item.users.join(', ')}` : ''}
														</p>
													</div>
													<span class="shrink-0 font-mono text-[10px] text-fg-secondary tabular-nums">
														{item.playCount} {item.playCount === 1 ? 'play' : 'plays'} · {formatDurationSec(item.watchDurationSec)}
													</span>
												</button>
											{/each}
										</div>
									</div>
								{/if}
							</div>
						{/if}
					{/snippet}
				</BarChart>
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
								class="group flex w-full cursor-pointer items-center justify-between gap-3 rounded-lg border border-transparent bg-elevated px-3 py-2 text-left transition-[background-color,border-color,transform] duration-150 ease-[var(--ease-out-quart)] hover:border-cyan/50 hover:bg-subtle/60 active:scale-[0.99] motion-reduce:active:scale-100 focus-visible:border-cyan focus-visible:outline-none"
							>
								<div class="flex min-w-0 items-center gap-2.5">
									<span
										class={idx === 0
											? 'font-mono text-xs font-bold text-primary-text'
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

		<!-- Watch Analytics: Clients, Codecs & Transcode Reasons + Viewing Heatmap -->
		<section class="flex flex-col gap-4" aria-label="Watch analytics">
			<ClientsCodecsPanel {clientStats} metric={analyticsMetric} loading={loadingStats} />
			<ViewingHeatmap
				{heatmap}
				range={heatmapRange}
				metric={analyticsMetric}
				loading={loadingHeatmap}
				onchangeRange={(next) => (heatmapRange = next)}
			/>
		</section>
	{/if}
	{/if}
</div>

{#if authStore.isAdmin}
	<TerminateStreamSheet
		stream={terminateTarget}
		bind:open={terminateSheetOpen}
		onOpenChange={(v) => (terminateSheetOpen = v)}
		onTerminated={handleStreamTerminated}
	/>
{/if}
