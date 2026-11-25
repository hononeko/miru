<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { ArrowLeft, Calendar, Flame, Sparkles } from 'lucide-svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type {
		AnalyticsMetricPreference,
		StatsRange,
		UserDetailResponse,
		UserStatsResponse,
		WrappedStatsResponse
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import RangeTabs from '$components/ui/RangeTabs.svelte';
	import Sheet from '$components/ui/Sheet.svelte';
	import KpiCard from '$components/miru/KpiCard.svelte';
	import BarChart, { type BarPoint } from '$components/miru/BarChart.svelte';
	import ProxiedImage from '$components/miru/ProxiedImage.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
	import {
		cn,
		formatCalendarDate,
		formatDurationSec,
		formatMediaTitle,
		formatRelativeTime,
		formatShortDateTime,
		formatStatPercent,
		getCalendarDateAxisParts,
		getDecisionMeta,
		statBarWidth
	} from '$lib/utils';

	let lastDefaultRange = preferencesStore.prefs.defaultStatsRange;
	let range = $state<StatsRange>(lastDefaultRange);
	let detail = $state<UserDetailResponse | null>(null);
	let stats = $state<UserStatsResponse | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

	const currentUtcYear = new Date().getUTCFullYear();
	let wrappedYear = $state<number>(currentUtcYear);
	let wrapped = $state<WrappedStatsResponse | null>(null);
	let loadingWrapped = $state(false);
	let wrappedSheetOpen = $state(false);

	$effect(() => {
		const nextDefault = preferencesStore.prefs.defaultStatsRange;
		if (nextDefault !== lastDefaultRange) {
			lastDefaultRange = nextDefault;
			range = nextDefault;
		}
	});

	const userId = $derived(page.params.id ?? '');
	const analyticsMetric = $derived<AnalyticsMetricPreference>(
		preferencesStore.prefs.analyticsMetric
	);

	const availableYears = $derived.by(() => {
		const years = new Set<number>([currentUtcYear, currentUtcYear - 1, currentUtcYear - 2]);
		for (const play of detail?.recentPlays ?? []) {
			const y = new Date(play.stoppedAtMs).getUTCFullYear();
			if (y >= 1970 && y <= 2100) years.add(y);
		}
		return Array.from(years).sort((a, b) => b - a);
	});

	async function loadProfile(
		id: string,
		selectedRange: StatsRange,
		selectedMetric: AnalyticsMetricPreference
	) {
		if (!id) return;
		if (!authStore.isAdmin) {
			loading = false;
			return;
		}
		loading = true;
		error = null;
		try {
			const [dRes, sRes] = await Promise.all([
				api.GET('/api/v1/users/{id}', { params: { path: { id } } }),
				api.GET('/api/v1/users/{id}/stats', {
					params: { path: { id }, query: { range: selectedRange, metric: selectedMetric } }
				})
			]);
			detail = unwrap(dRes);
			stats = unwrap(sRes);
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Failed to load user profile';
		} finally {
			loading = false;
		}
	}

	async function loadWrapped(
		id: string,
		year: number,
		selectedMetric: AnalyticsMetricPreference
	) {
		if (!id || !authStore.isAdmin) return;
		loadingWrapped = true;
		try {
			wrapped = unwrap(
				await api.GET('/api/v1/stats/wrapped/{year}', {
					params: {
						path: { year },
						query: { userId: id, metric: selectedMetric }
					}
				})
			);
		} catch {
			wrapped = null;
		} finally {
			loadingWrapped = false;
		}
	}

	$effect(() => {
		if (!authStore.loading && !authStore.isAdmin) {
			void goto('/');
			return;
		}
		void loadProfile(userId, range, analyticsMetric);
	});

	$effect(() => {
		if (!authStore.loading && authStore.isAdmin && userId) {
			void loadWrapped(userId, wrappedYear, analyticsMetric);
		}
	});

	const dailyDays = $derived(stats?.playsPerDay ?? []);

	const dailyBars = $derived.by<BarPoint[]>(() => {
		const days = dailyDays;
		return days.map((d, idx) => {
			const axis = getCalendarDateAxisParts(d.date, idx > 0 ? days[idx - 1].date : null);
			const formattedDate = formatCalendarDate(d.date, preferencesStore.prefs.dateFormat);
			const playsText = `${d.playCount} ${d.playCount === 1 ? 'play' : 'plays'}`;
			return {
				id: d.date,
				label: axis.dayLabel,
				subLabel: axis.monthLabel,
				value: analyticsMetric === 'duration' ? d.watchDurationSec : d.playCount,
				tooltip: `${formattedDate}: ${playsText} (${formatDurationSec(d.watchDurationSec)})`,
				ariaLabel: `${formattedDate}: ${playsText}, ${formatDurationSec(d.watchDurationSec)}`
			};
		});
	});

	const wrappedMonthBars = $derived.by<BarPoint[]>(() => {
		return (wrapped?.playsByMonth ?? []).map((m) => ({
			label: m.monthName,
			value: analyticsMetric === 'duration' ? m.watchDurationSec : m.playCount,
			tooltip: `${m.monthName} ${wrapped?.year ?? wrappedYear}: ${m.playCount} plays (${formatDurationSec(m.watchDurationSec)})`
		}));
	});

	const peakDay = $derived.by(() => {
		const days = stats?.playsPerDay ?? [];
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
</script>

<svelte:head>
	<title>{detail ? `${detail.user.displayName || detail.user.username} · Users · Miru` : 'User Profile · Miru'}</title>
</svelte:head>

<div class="flex flex-col gap-5">
	{#if !authStore.isAdmin}
		<div role="alert" class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
			Administrator role is required to view user profiles.
		</div>
	{:else if error}
		<div class="flex flex-col gap-3">
			<a href="/users" class="inline-flex items-center gap-1.5 font-mono text-xs text-cyan-hi hover:underline">
				<ArrowLeft class="h-3.5 w-3.5" /> Back to Users
			</a>
			<div class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
				{error}
			</div>
		</div>
	{:else if loading && !detail}
		<div class="rounded-xl border border-border bg-surface p-10 text-center font-mono text-xs text-fg-muted">
			Loading user profile…
		</div>
	{:else if detail && stats}
		<!-- Profile Header -->
		<div class="flex flex-wrap items-center justify-between gap-4">
			<div class="flex items-center gap-4">
				<ProxiedImage
					src={detail.user.avatarUrl}
					alt={detail.user.displayName || detail.user.username}
					fallbackLabel={detail.user.displayName || detail.user.username}
					variant="avatar"
					class="h-14 w-14 text-xl"
				/>
				<div class="flex flex-col gap-1">
					<a href="/users" class="inline-flex items-center gap-1 font-mono text-[11px] text-cyan-hi hover:underline">
						<ArrowLeft class="h-3 w-3" /> Users / {detail.user.displayName || detail.user.username}
					</a>
					<div class="flex flex-wrap items-center gap-2">
						<h1 class="font-display text-2xl font-bold text-fg-primary sm:text-3xl">
							{detail.user.displayName || detail.user.username}
						</h1>
						{#if detail.user.isAdmin}
							<Badge tone="primary">ADMIN</Badge>
						{/if}
						{#if detail.activeStreams.length > 0}
							<Badge tone="direct-play" dot pulse>
								WATCHING {detail.activeStreams[0].media.title}
							</Badge>
						{/if}
					</div>
					<p class="text-xs text-fg-secondary">
						External ID #{detail.user.externalUserId} · Last seen {detail.user.isStreaming ? 'Active now' : formatRelativeTime(detail.user.lastSeenAtMs, Date.now(), preferencesStore.prefs)}
					</p>
				</div>
			</div>

			<RangeTabs value={range} onchange={(r) => (range = r)} />
		</div>

		<!-- KPIs (Plays, Watch Time, Avg Completion, Longest Binge Streak, Direct Play, HW Transcode) -->
		<section class="grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-6 lg:gap-4">
			<KpiCard
				label={`Plays (${range})`}
				value={stats.totalPlays.toLocaleString()}
				subtitle={`${detail.totalPlays.toLocaleString()} lifetime plays`}
				dotClass="bg-primary"
			/>
			<KpiCard
				label={`Watch Time (${range})`}
				value={formatDurationSec(stats.totalWatchDurationSec)}
				subtitle={`${formatDurationSec(detail.totalWatchDurationSec)} lifetime`}
				dotClass="bg-cyan"
			/>
			<KpiCard
				label="Avg Completion"
				value={formatStatPercent(stats.averageCompletionPercent)}
				subtitle={`${stats.completedPlays.toLocaleString()} completed plays`}
				dotClass="bg-direct-play"
			/>
			<KpiCard
				label="Longest Binge Streak"
				value={`${stats.longestBingeStreakDays} ${stats.longestBingeStreakDays === 1 ? 'day' : 'days'}`}
				subtitle={`Peak ${stats.maxDailyPlays} ${stats.maxDailyPlays === 1 ? 'play' : 'plays'} in a day`}
				dotClass="bg-primary"
			/>
			<KpiCard
				label="Direct Play"
				value={formatStatPercent(stats.decisionBreakdown.directPlayPercent)}
				subtitle={`${stats.decisionBreakdown.directPlay} Direct · ${stats.decisionBreakdown.directStream} Remux`}
				dotClass="bg-direct-play"
			/>
			<KpiCard
				label="HW Transcode"
				value={formatStatPercent(stats.decisionBreakdown.hwTranscodePercent)}
				subtitle={`${stats.decisionBreakdown.hwTranscode} GPU · ${stats.decisionBreakdown.transcode} total transcodes`}
				dotClass="bg-hw-transcode"
			/>
		</section>

		<!-- Charts Row + Most Rewatched -->
		<section class="grid grid-cols-1 gap-4 lg:grid-cols-3">
			<div class="flex flex-col justify-between gap-4 rounded-xl border border-border bg-surface p-4">
				<div class="flex items-center justify-between gap-2">
					<h2 class="font-heading text-sm font-semibold text-fg-primary">
						Daily Activity ({range})
					</h2>
					{#if peakDay && (analyticsMetric === 'duration' ? peakDay.watchDurationSec > 0 : peakDay.playCount > 0)}
						<span class="font-mono text-[11px] font-medium text-primary-text">
							Peak: {analyticsMetric === 'duration' ? formatDurationSec(peakDay.watchDurationSec) : `${peakDay.playCount} plays`} ({peakDay.date})
						</span>
					{/if}
				</div>
				<BarChart points={dailyBars} heightClass="h-36">
					{#snippet detail(_point, idx, close)}
						{@const day = dailyDays[idx]}
						{#if day}
							<div class="flex flex-col gap-2 text-xs">
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

								{#if day.media.length > 0}
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
												<span class="truncate font-heading text-[11px] font-semibold text-fg-primary group-hover:text-cyan-hi">
													{item.title}
												</span>
												<span class="shrink-0 font-mono text-[10px] text-fg-secondary tabular-nums">
													{item.playCount} {item.playCount === 1 ? 'play' : 'plays'} · {formatDurationSec(item.watchDurationSec)}
												</span>
											</button>
										{/each}
									</div>
								{/if}
							</div>
						{/if}
					{/snippet}
				</BarChart>
			</div>

			<div class="flex flex-col justify-between gap-4 rounded-xl border border-border bg-surface p-4">
				<div class="flex flex-col gap-3">
					<div class="flex items-center justify-between gap-2">
						<h2 class="font-heading text-sm font-semibold text-fg-primary">Most Rewatched</h2>
					</div>
					{#if stats.mostRewatched.length === 0}
						<p class="rounded-lg bg-elevated/50 px-3 py-6 text-center text-xs text-fg-muted">
							No rewatched items (2+ plays) in this range.
						</p>
					{:else}
						<div class="flex flex-col gap-2">
							{#each stats.mostRewatched.slice(0, 4) as item, idx (item.ratingKey)}
								<button
									type="button"
									onclick={() => void mediaDetailsModal.open(item.ratingKey)}
									class="group flex w-full cursor-pointer items-center justify-between gap-2.5 rounded-lg bg-elevated px-3 py-2 text-left transition-colors hover:bg-subtle/60"
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
											<p class="truncate font-heading text-xs font-semibold text-fg-primary group-hover:text-cyan-hi">
												{item.grandparentTitle ? `${item.grandparentTitle} · ${item.title}` : item.title}
											</p>
											<p class="text-[10px] text-fg-muted">
												{item.playCount} plays · {formatDurationSec(item.watchDurationSec)}
											</p>
										</div>
									</div>
									<Badge tone={idx === 0 ? 'primary' : 'cyan'} mono>{item.playCount}x</Badge>
								</button>
							{/each}
						</div>
					{/if}
				</div>
			</div>

			<div class="flex flex-col justify-between gap-4 rounded-xl border border-border bg-surface p-4">
				<div class="flex flex-col gap-3">
					<h2 class="font-heading text-sm font-semibold text-fg-primary">Top Platforms</h2>
					{#if stats.topPlatforms.length === 0}
						<p class="text-xs text-fg-muted">No platform telemetry in this range.</p>
					{:else}
						{#each stats.topPlatforms.slice(0, 4) as plat, idx}
							<div class="flex flex-col gap-1">
								<div class="flex items-center justify-between text-xs">
									<span class="truncate font-medium text-fg-primary">{plat.platform}</span>
									<span class="shrink-0 font-mono text-[11px] text-fg-secondary tabular-nums">
										{analyticsMetric === 'duration' ? formatDurationSec(plat.watchDurationSec) : plat.playCount} · {formatStatPercent(plat.percent)}
									</span>
								</div>
								<div class="h-1.5 w-full overflow-hidden rounded-full bg-subtle">
									<div
										class={`h-full rounded-full transition-[width] duration-500 ease-[var(--ease-out-quart)] ${idx === 0 ? 'bg-primary' : 'bg-cyan'} ${plat.percent > 0 ? 'min-w-0.5' : ''}`}
										style:width={statBarWidth(plat.percent)}
									></div>
								</div>
							</div>
						{/each}
					{/if}
				</div>

				{#if stats.libraryBreakdown.length > 0}
					<div class="flex flex-wrap gap-1.5 border-t border-border pt-3">
						{#each stats.libraryBreakdown as lib}
							<Badge tone="cyan" mono>
								Sec {lib.librarySectionKey ?? 'unknown'} · {formatStatPercent(lib.percent, 0)}
							</Badge>
						{/each}
					</div>
				{/if}
			</div>
		</section>

		<!-- Year in Review (Wrapped) Section -->
		<section class="flex flex-col gap-4 rounded-xl border border-cyan/40 bg-surface p-4">
			<div class="flex flex-wrap items-center justify-between gap-3">
				<div class="flex items-center gap-2.5">
					<div class="flex h-9 w-9 items-center justify-center rounded-lg bg-cyan-soft text-cyan-hi">
						<Sparkles class="h-4 w-4" />
					</div>
					<div>
						<h2 class="font-heading text-sm font-semibold text-fg-primary">
							Year in Review (Wrapped)
						</h2>
						<p class="text-[11px] text-fg-secondary">
							Annual playback highlights, binge streaks, and favorite titles for {wrappedYear}
						</p>
					</div>
				</div>

				<div class="flex flex-wrap items-center gap-2">
					<div
						role="group"
						aria-label="Wrapped year picker"
						class="inline-flex items-center gap-0.5 rounded-lg border border-border bg-elevated p-0.5"
					>
						{#each availableYears as yr (yr)}
							<button
								type="button"
								aria-pressed={wrappedYear === yr}
								onclick={() => (wrappedYear = yr)}
								class={cn(
									'rounded-md px-2.5 py-1 font-mono text-[11px] font-medium transition-colors cursor-pointer',
									wrappedYear === yr
										? 'bg-primary text-primary-fg font-semibold'
										: 'text-fg-secondary hover:text-fg-primary'
								)}
							>
								{yr}
							</button>
						{/each}
					</div>

					<Button variant="cyan" size="sm" onclick={() => (wrappedSheetOpen = true)}>
						<Calendar class="h-3.5 w-3.5" />
						Open Wrapped Summary
					</Button>
				</div>
			</div>

			{#if loadingWrapped && !wrapped}
				<p class="py-6 text-center font-mono text-xs text-fg-muted">
					Loading {wrappedYear} Wrapped…
				</p>
			{:else if wrapped}
				<div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
					<div class="rounded-lg bg-elevated px-3 py-2.5">
						<span class="text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
							{wrapped.year} Watch Time
						</span>
						<p class="mt-1 font-mono text-base font-bold text-fg-primary tabular-nums">
							{formatDurationSec(wrapped.totalWatchDurationSec)}
						</p>
						<p class="text-[10px] text-fg-secondary">
							{wrapped.totalPlays.toLocaleString()} plays across {wrapped.activeDaysCount} active days
						</p>
					</div>

					<div class="rounded-lg bg-elevated px-3 py-2.5">
						<span class="text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
							Binge & Completion
						</span>
						<p class="mt-1 font-mono text-base font-bold text-cyan-hi tabular-nums">
							{wrapped.longestBingeStreakDays}d streak · {formatStatPercent(wrapped.averageCompletionPercent, 0)}
						</p>
						<p class="text-[10px] text-fg-secondary">
							{wrapped.completedPlays.toLocaleString()} completed sessions
						</p>
					</div>

					<div class="rounded-lg bg-elevated px-3 py-2.5">
						<span class="text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
							Top Movie & Show
						</span>
						<p class="mt-1 truncate font-heading text-xs font-semibold text-fg-primary">
							{wrapped.topMovies[0]?.title ?? 'No movies'}
						</p>
						<p class="truncate text-[10px] text-fg-secondary">
							Show: {wrapped.topShows[0]?.title ?? 'No shows'}
						</p>
					</div>

					<div class="rounded-lg bg-elevated px-3 py-2.5">
						<span class="text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
							Peak Activity
						</span>
						<p class="mt-1 font-mono text-sm font-bold text-primary-text">
							{wrapped.peakMonth ? `${wrapped.peakMonth} · ${wrapped.peakDayOfWeek ?? ''}` : 'No plays'}
						</p>
						<p class="text-[10px] text-fg-secondary">
							{wrapped.uniqueMoviesCount} movies · {wrapped.uniqueShowsCount} shows
						</p>
					</div>
				</div>
			{/if}
		</section>

		<!-- Recent Plays -->
		<section class="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
			<div class="flex items-center justify-between">
				<h2 class="font-heading text-sm font-semibold text-fg-primary">Recent Plays</h2>
				<a href="/history" class="font-heading text-xs font-semibold text-cyan-hi hover:underline">
					View full history →
				</a>
			</div>

			{#if detail.recentPlays.length === 0}
				<p class="py-6 text-center text-xs text-fg-muted">No recent plays recorded yet.</p>
			{:else}
				<div class="flex flex-col gap-2">
					{#each detail.recentPlays.slice(0, 6) as play (play.id)}
						{@const titles = formatMediaTitle(play)}
						{@const dec = getDecisionMeta(play.streamDecision, play.hwTranscode)}
						<div class="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-elevated px-3 py-2.5">
							<div class="min-w-0">
								<button
									type="button"
									onclick={() => void mediaDetailsModal.open(play.ratingKey)}
									class="block max-w-full cursor-pointer text-left hover:text-cyan-hi hover:underline"
								>
									<p class="truncate font-heading text-xs font-semibold text-fg-primary hover:text-cyan-hi">
										{titles.primary}
										{#if titles.secondary}
											<span class="font-sans font-normal text-fg-secondary"> · {titles.secondary}</span>
										{/if}
									</p>
								</button>
								<p class="text-[10px] text-fg-muted">
									{play.playerName || play.playerProduct || 'Unknown client'} · {formatShortDateTime(play.stoppedAtMs, preferencesStore.prefs)}
								</p>
							</div>
							<div class="flex items-center gap-2.5">
								<Badge tone={dec.tone} dot>{dec.shortLabel}</Badge>
								<span class="font-mono text-[11px] text-fg-secondary tabular-nums">
									{formatDurationSec(play.watchedDurationSec)} · {Math.round(play.completionPercent)}%
								</span>
							</div>
						</div>
					{/each}
				</div>
			{/if}
		</section>
	{/if}
</div>

<Sheet
	bind:open={wrappedSheetOpen}
	onOpenChange={(v) => (wrappedSheetOpen = v)}
	title={`${detail?.user.displayName || detail?.user.username || 'User'} · ${wrappedYear} Wrapped`}
	description="Annual playback summary, monthly cadence, top media, and most-rewatched favorites"
>
	{#if !wrapped}
		<p class="py-8 text-center font-mono text-xs text-fg-muted">
			{loadingWrapped ? 'Loading Wrapped summary…' : 'No Wrapped data available'}
		</p>
	{:else}
		<div class="flex flex-col gap-4">
			<div class="grid grid-cols-2 gap-2.5">
				<div class="rounded-lg border border-border bg-elevated p-3">
					<span class="text-[10px] font-semibold uppercase text-fg-muted">Total Watch Time</span>
					<p class="mt-1 font-mono text-lg font-bold text-cyan-hi tabular-nums">
						{formatDurationSec(wrapped.totalWatchDurationSec)}
					</p>
					<p class="text-[10px] text-fg-secondary">{wrapped.totalPlays.toLocaleString()} plays</p>
				</div>
				<div class="rounded-lg border border-border bg-elevated p-3">
					<span class="text-[10px] font-semibold uppercase text-fg-muted">Longest Streak</span>
					<p class="mt-1 flex items-center gap-1 font-mono text-lg font-bold text-primary-text tabular-nums">
						<Flame class="h-4 w-4" />
						{wrapped.longestBingeStreakDays} days
					</p>
					<p class="text-[10px] text-fg-secondary">
						{formatStatPercent(wrapped.averageCompletionPercent)} avg completion
					</p>
				</div>
			</div>

			<div class="flex flex-col gap-2 rounded-lg border border-border bg-elevated p-3">
				<div class="flex items-center justify-between">
					<span class="font-heading text-xs font-semibold text-fg-primary">
						Monthly Activity ({wrapped.year})
					</span>
					{#if wrapped.peakMonth}
						<Badge tone="cyan" mono>Peak: {wrapped.peakMonth}</Badge>
					{/if}
				</div>
				<BarChart points={wrappedMonthBars} heightClass="h-28" />
			</div>

			{#if wrapped.topMovies.length > 0}
				<div class="flex flex-col gap-1.5">
					<h3 class="font-heading text-xs font-semibold text-fg-primary">Top Movies ({wrapped.year})</h3>
					{#each wrapped.topMovies.slice(0, 3) as movie, idx (movie.ratingKey)}
						<div class="flex items-center justify-between rounded-lg bg-elevated px-3 py-2 text-xs">
							<span class="truncate font-medium text-fg-primary">
								{idx + 1}. {movie.title}
							</span>
							<span class="shrink-0 font-mono text-[11px] text-fg-secondary tabular-nums">
								{movie.playCount} plays · {formatDurationSec(movie.watchDurationSec)}
							</span>
						</div>
					{/each}
				</div>
			{/if}

			{#if wrapped.topShows.length > 0}
				<div class="flex flex-col gap-1.5">
					<h3 class="font-heading text-xs font-semibold text-fg-primary">Top Shows ({wrapped.year})</h3>
					{#each wrapped.topShows.slice(0, 3) as show, idx (show.title)}
						<div class="flex items-center justify-between rounded-lg bg-elevated px-3 py-2 text-xs">
							<span class="truncate font-medium text-fg-primary">
								{idx + 1}. {show.title}
							</span>
							<span class="shrink-0 font-mono text-[11px] text-fg-secondary tabular-nums">
								{show.playCount} plays · {formatDurationSec(show.watchDurationSec)}
							</span>
						</div>
					{/each}
				</div>
			{/if}

			{#if wrapped.mostRewatched.length > 0}
				<div class="flex flex-col gap-1.5">
					<h3 class="font-heading text-xs font-semibold text-fg-primary">
						Most Rewatched ({wrapped.year})
					</h3>
					{#each wrapped.mostRewatched.slice(0, 3) as item (item.ratingKey)}
						<div class="flex items-center justify-between rounded-lg bg-elevated px-3 py-2 text-xs">
							<span class="truncate font-medium text-fg-primary">
								{item.grandparentTitle ? `${item.grandparentTitle} · ${item.title}` : item.title}
							</span>
							<Badge tone="primary" mono>{item.playCount}x</Badge>
						</div>
					{/each}
				</div>
			{/if}
		</div>
	{/if}
</Sheet>
