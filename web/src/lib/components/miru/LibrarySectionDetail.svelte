<script lang="ts">
	import { HardDrive, Sparkles } from 'lucide-svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type {
		LibraryStatsResponse,
		RecentMediaItemResponse,
		StaleMediaListResponse,
		StaleMediaSort
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import BarChart, { type BarPoint } from '$components/miru/BarChart.svelte';
	import KpiCard from '$components/miru/KpiCard.svelte';
	import ProxiedImage from '$components/miru/ProxiedImage.svelte';
	import { latestRequest } from '$lib/latestRequest';
	import { authStore } from '$stores/auth.svelte';
	import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import {
		cn,
		formatBytes,
		formatCalendarDate,
		formatDurationSec,
		formatRelativeTime,
		formatShortDateTime,
		formatStatPercent,
		getCalendarDateAxisParts,
		statBarWidth
	} from '$lib/utils';

	type Props = {
		stats: LibraryStatsResponse;
		recentItems: RecentMediaItemResponse[];
		loadingSection?: boolean;
	};

	let { stats, recentItems, loadingSection = false }: Props = $props();

	type SectionTab = 'overview' | 'hygiene';
	let activeTab = $state<SectionTab>('overview');
	// The hygiene report covers the whole server's playback and is only useful to someone who can delete media.
	const showHygiene = $derived(authStore.isAdmin && activeTab === 'hygiene');
	let unwatchedDays = $state<number>(180);
	let staleSort = $state<StaleMediaSort>('size_desc');
	let staleReport = $state<StaleMediaListResponse | null>(null);
	let loadingStale = $state(false);
	let staleError = $state<string | null>(null);

	const UNWATCHED_THRESHOLDS: ReadonlyArray<{ days: number; label: string }> = [
		{ days: 90, label: '90d' },
		{ days: 180, label: '180d' },
		{ days: 365, label: '365d' },
		{ days: 0, label: 'Never played' }
	];

	const SORT_OPTIONS: ReadonlyArray<{ value: StaleMediaSort; label: string }> = [
		{ value: 'size_desc', label: 'Largest first' },
		{ value: 'age_desc', label: 'Oldest first' }
	];

	const paletteColors = ['bg-chart-1', 'bg-chart-2', 'bg-chart-3', 'bg-chart-4', 'bg-fg-muted'];

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

	const resDist = $derived(mapToDist(stats.byResolution));
	const videoDist = $derived(mapToDist(stats.byVideoCodec));
	const audioDist = $derived(mapToDist(stats.byAudioCodec));

	const GROWTH_UNITS: Record<string, { one: string; many: string }> = {
		movie: { one: 'movie', many: 'movies' },
		show: { one: 'episode', many: 'episodes' },
		artist: { one: 'track', many: 'tracks' }
	};
	const growthUnit = $derived(GROWTH_UNITS[stats.section.sectionType] ?? { one: 'item', many: 'items' });

	const growthBars = $derived.by<BarPoint[]>(() =>
		stats.growth.map((day, idx) => {
			const axis = getCalendarDateAxisParts(day.date, idx > 0 ? stats.growth[idx - 1].date : null);
			const changes = [
				day.added > 0 ? `${growthCount(day.added)} added` : null,
				day.removed > 0 ? `${growthCount(day.removed)} removed` : null
			];
			return {
				id: day.date,
				label: axis.dayLabel,
				subLabel: axis.monthLabel,
				value: day.added,
				segments: [{ label: 'Added', value: day.added, colorClass: 'bg-chart-1' }],
				negative: { label: 'Removed', value: day.removed, colorClass: 'bg-chart-2' },
				ariaLabel: `${formatCalendarDate(day.date, preferencesStore.prefs.dateFormat)}: ${changes.filter(Boolean).join(', ')}`
			};
		})
	);

	function growthCount(count: number): string {
		return `${count.toLocaleString()} ${count === 1 ? growthUnit.one : growthUnit.many}`;
	}

	function formatSizeDelta(bytes: number): string {
		if (bytes === 0) return 'No size change';
		return `${bytes > 0 ? '+' : '−'}${formatBytes(Math.abs(bytes))}`;
	}

	const startStaleRequest = latestRequest();
	let staleReportSection: string | null = null;

	async function loadStaleMedia(sectionKey: string, days: number, sort: StaleMediaSort) {
		if (!sectionKey) return;
		const isLatest = startStaleRequest();
		if (staleReportSection !== sectionKey) {
			staleReportSection = sectionKey;
			staleReport = null;
		}
		loadingStale = true;
		staleError = null;
		try {
			const report = unwrap(
				await api.GET('/api/v1/libraries/{id}/stale', {
					params: {
						path: { id: sectionKey },
						query: {
							unwatchedDays: days,
							sort,
							limit: 100
						}
					}
				})
			);
			if (isLatest()) staleReport = report;
		} catch (e) {
			if (!isLatest()) return;
			staleError = e instanceof MiruApiError ? e.detail : 'Failed to load stale media report';
		} finally {
			if (isLatest()) loadingStale = false;
		}
	}

	$effect(() => {
		const sectionKey = stats.section.sectionKey;
		const days = unwatchedDays;
		const sort = staleSort;
		if (showHygiene && sectionKey) {
			void loadStaleMedia(sectionKey, days, sort);
		}
	});
</script>

<!-- Section View Mode Switcher (Overview vs Hygiene / Stale Media) -->
{#if authStore.isAdmin}
	<div class="flex flex-wrap items-center justify-between gap-3">
		<div
			role="group"
			aria-label="Library view mode"
			class="inline-flex items-center gap-1 rounded-xl border border-border bg-surface p-1"
		>
			<button
				type="button"
				aria-pressed={activeTab === 'overview'}
				onclick={() => (activeTab = 'overview')}
				class={cn(
					'inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 font-heading text-xs font-semibold transition-[background-color,border-color,color,transform] duration-150 ease-[var(--ease-out-quart)] active:scale-[0.98] motion-reduce:active:scale-100 cursor-pointer',
					activeTab === 'overview'
						? 'bg-cyan-soft text-cyan-hi border border-cyan/50'
						: 'text-fg-secondary hover:text-fg-primary border border-transparent'
				)}
			>
				<Sparkles class="h-3.5 w-3.5" />
				Overview & Recent
			</button>
			<button
				type="button"
				aria-pressed={activeTab === 'hygiene'}
				onclick={() => (activeTab = 'hygiene')}
				class={cn(
					'inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 font-heading text-xs font-semibold transition-[background-color,border-color,color,transform] duration-150 ease-[var(--ease-out-quart)] active:scale-[0.98] motion-reduce:active:scale-100 cursor-pointer',
					activeTab === 'hygiene'
						? 'bg-primary-soft text-primary-text border border-primary/50'
						: 'text-fg-secondary hover:text-fg-primary border border-transparent'
				)}
			>
				<HardDrive class="h-3.5 w-3.5" />
				Hygiene / Stale Media
			</button>
		</div>
	</div>
{/if}

{#if !showHygiene}
	<!-- Breakdown + Growth Row -->
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
									class={`h-full rounded-full transition-[width] duration-500 ease-[var(--ease-out-quart)] ${seg.colorClass} ${seg.pct > 0 ? 'min-w-0.5' : ''}`}
									style:width={statBarWidth(seg.pct)}
									title={`${seg.name}: ${seg.count} (${formatStatPercent(seg.pct)})`}
								></div>
							{/each}
						</div>
						<div class="flex flex-wrap gap-x-3 gap-y-1">
							{#each group.rows as seg}
								<span class="inline-flex items-center gap-1.5 font-mono text-[10px] text-fg-secondary">
									<span class={`h-2 w-2 rounded-full ${seg.colorClass}`}></span>
									{seg.name} {formatStatPercent(seg.pct, 0)}
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
					<h2 class="font-heading text-sm font-semibold text-fg-primary">Growth History</h2>
					<p class="text-[11px] text-fg-muted first-letter:uppercase">
						{growthUnit.many} added and removed per day (UTC)
					</p>
				</div>
				{#if growthBars.length > 0}
					<div class="flex shrink-0 items-center gap-3 text-[10px] text-fg-secondary">
						<span class="inline-flex items-center gap-1.5">
							<span class="h-2 w-2 rounded-full bg-chart-1"></span>Added
						</span>
						<span class="inline-flex items-center gap-1.5">
							<span class="h-2 w-2 rounded-full bg-chart-2"></span>Removed
						</span>
					</div>
				{/if}
			</div>

			<BarChart
				points={growthBars}
				heightClass="h-32"
				barClass="max-w-12"
				highlightPeak={false}
				emptyMessage={loadingSection ? 'Loading growth history…' : 'No additions or removals recorded yet'}
			>
				{#snippet detail(_point, idx)}
					{@const day = stats.growth[idx]}
					{#if day}
						<div class="flex flex-col gap-1.5 text-xs">
							<span class="font-mono text-xs font-semibold text-fg-primary">
								{formatCalendarDate(day.date, preferencesStore.prefs.dateFormat)}
							</span>
							{#if day.added > 0}
								<span class="inline-flex items-center gap-1.5 text-fg-secondary">
									<span class="h-2 w-2 rounded-full bg-chart-1"></span>
									+{growthCount(day.added)} added
								</span>
							{/if}
							{#if day.removed > 0}
								<span class="inline-flex items-center gap-1.5 text-fg-secondary">
									<span class="h-2 w-2 rounded-full bg-chart-2"></span>
									−{growthCount(day.removed)} removed
								</span>
							{/if}
							<span class="font-mono text-[11px] text-fg-muted tabular-nums">
								{formatSizeDelta(day.sizeDeltaBytes)}
							</span>
						</div>
					{/if}
				{/snippet}
			</BarChart>

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
					<button
						type="button"
						onclick={() => void mediaDetailsModal.open(item.ratingKey)}
						class="group flex cursor-pointer flex-col gap-1.5 text-left"
					>
						<ProxiedImage
							src={item.thumbUrl}
							alt={item.title}
							fallbackLabel={item.title}
							variant="poster"
							class="aspect-[2/3] w-full transition-transform group-hover:scale-[1.02]"
						/>
						<div class="flex w-full items-center justify-between gap-1">
							<p class="truncate font-heading text-xs font-semibold text-fg-primary group-hover:text-cyan-hi group-hover:underline">
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
					</button>
				{/each}
			</div>
		{/if}
	</section>
{:else}
	<!-- Stale / Unwatched Media Hygiene Tab -->
	<section class="flex flex-col gap-4" aria-label="Stale media hygiene report">
		<!-- Reclaimable Storage KPI Strip -->
		<div class="grid grid-cols-1 gap-3 sm:grid-cols-3">
			<KpiCard
				label="Reclaimable Storage"
				value={staleReport ? formatBytes(staleReport.totalReclaimableBytes) : '—'}
				subtitle={`Across ${staleReport?.totalStaleCount.toLocaleString() ?? 0} matching items in ${stats.section.title}`}
				dotClass="bg-primary"
			/>
			<KpiCard
				label="Stale / Unwatched Items"
				value={staleReport ? staleReport.totalStaleCount.toLocaleString() : '—'}
				subtitle={unwatchedDays === 0 ? 'Threshold: Never played' : `Threshold: Unwatched ≥ ${unwatchedDays} days`}
				dotClass="bg-cyan"
			/>
			<KpiCard
				label="Never Played"
				value={staleReport ? staleReport.neverPlayedCount.toLocaleString() : '—'}
				subtitle="Zero recorded playback sessions in Miru"
				dotClass="bg-warning"
			/>
		</div>

		<div class="flex flex-col gap-4 rounded-xl border border-border bg-surface p-4">
			<!-- Threshold & Sort Controls -->
			<div class="flex flex-wrap items-center justify-between gap-3">
				<div>
					<h2 class="font-heading text-sm font-semibold text-fg-primary">
						Stale & Unwatched Media · {stats.section.title}
					</h2>
					<p class="text-[11px] text-fg-muted">
						Cross-referenced against playback history to surface untouched media consuming disk footprint
					</p>
				</div>

				<div class="flex flex-wrap items-center gap-2">
					<div
						role="group"
						aria-label="Unwatched threshold"
						class="inline-flex items-center gap-0.5 rounded-lg border border-border bg-elevated p-0.5"
					>
						{#each UNWATCHED_THRESHOLDS as thresh (thresh.days)}
							{@const active = unwatchedDays === thresh.days}
							<button
								type="button"
								aria-pressed={active}
								onclick={() => (unwatchedDays = thresh.days)}
								class={cn(
									'rounded-md px-2.5 py-1 font-mono text-[11px] font-medium transition-colors cursor-pointer',
									active
										? 'bg-cyan-soft text-cyan-hi border border-cyan/60 font-semibold'
										: 'text-fg-secondary hover:text-fg-primary border border-transparent'
								)}
							>
								{thresh.label}
							</button>
						{/each}
					</div>

					<div
						role="group"
						aria-label="Stale media sort"
						class="inline-flex items-center gap-0.5 rounded-lg border border-border bg-elevated p-0.5"
					>
						{#each SORT_OPTIONS as opt (opt.value)}
							{@const active = staleSort === opt.value}
							<button
								type="button"
								aria-pressed={active}
								onclick={() => (staleSort = opt.value)}
								class={cn(
									'rounded-md px-2.5 py-1 font-heading text-[11px] font-semibold transition-colors cursor-pointer',
									active
										? 'bg-primary-soft text-primary-text border border-primary/50'
										: 'text-fg-secondary hover:text-fg-primary border border-transparent'
								)}
							>
								{opt.label}
							</button>
						{/each}
					</div>
				</div>
			</div>

			{#if staleError}
				<div class="rounded-lg border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
					{staleError}
				</div>
			{:else if loadingStale && !staleReport}
				<p class="py-10 text-center font-mono text-xs text-fg-muted">
					Analyzing library hygiene…
				</p>
			{:else if !staleReport || staleReport.items.length === 0}
				<p class="py-10 text-center text-xs text-fg-muted">
					No stale or unwatched media items match this threshold. Run a deep library sync if items have not been cached yet.
				</p>
			{:else}
				<div class="grid grid-cols-1 gap-3 md:grid-cols-2">
					{#each staleReport.items as item (item.ratingKey)}
						<button
							type="button"
							onclick={() => void mediaDetailsModal.open(item.ratingKey)}
							class="group flex w-full cursor-pointer items-center gap-3 rounded-xl border border-border bg-elevated/70 p-3 text-left transition-colors hover:border-cyan/60 hover:bg-elevated"
						>
							<ProxiedImage
								src={item.thumbUrl}
								alt={item.title}
								fallbackLabel={item.title}
								variant="poster"
								class="h-20 w-14 shrink-0"
							/>
							<div class="flex min-w-0 flex-1 flex-col gap-1">
								<div class="flex items-start justify-between gap-2">
									<p class="truncate font-heading text-xs font-semibold text-fg-primary group-hover:text-cyan-hi">
										{item.grandparentTitle ? `${item.grandparentTitle} · ${item.title}` : item.title}
									</p>
									<span class="shrink-0 font-mono text-xs font-bold text-primary-text tabular-nums">
										{formatBytes(item.fileSizeBytes)}
									</span>
								</div>

								<div class="flex flex-wrap items-center gap-1.5">
									{#if item.year}
										<Badge tone="neutral" mono>{item.year}</Badge>
									{/if}
									{#if item.videoResolution}
										<Badge tone="cyan" mono>{item.videoResolution.toUpperCase()}</Badge>
									{/if}
									{#if item.videoCodec}
										<Badge tone="neutral" mono>{item.videoCodec.toUpperCase()}</Badge>
									{/if}
									{#if item.playCount === 0 || !item.lastPlayedAtMs}
										<Badge tone="warning">NEVER PLAYED</Badge>
									{:else}
										<Badge tone="neutral" mono>
											{item.playCount} {item.playCount === 1 ? 'play' : 'plays'}
										</Badge>
									{/if}
								</div>

								<div class="flex flex-wrap items-center justify-between gap-2 font-mono text-[10px] text-fg-muted">
									<span>Added {formatShortDateTime(item.addedAtMs, preferencesStore.prefs)}</span>
									<span>
										{item.lastPlayedAtMs
											? `Last played ${formatRelativeTime(item.lastPlayedAtMs, Date.now(), preferencesStore.prefs)}`
											: 'Last played: Never'}
									</span>
								</div>
							</div>
						</button>
					{/each}
				</div>
			{/if}
		</div>
	</section>
{/if}
