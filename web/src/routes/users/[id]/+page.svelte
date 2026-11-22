<script lang="ts">
	import { page } from '$app/state';
	import { ArrowLeft } from 'lucide-svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type { StatsRange, UserDetailResponse, UserStatsResponse } from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import RangeTabs from '$components/ui/RangeTabs.svelte';
	import KpiCard from '$components/miru/KpiCard.svelte';
	import BarChart, { type BarPoint } from '$components/miru/BarChart.svelte';
	import ProxiedImage from '$components/miru/ProxiedImage.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import {
		formatDurationSec,
		formatMediaTitle,
		formatRelativeTime,
		formatShortDateTime,
		getDecisionMeta
	} from '$lib/utils';

	let lastDefaultRange = preferencesStore.prefs.defaultStatsRange;
	let range = $state<StatsRange>(lastDefaultRange);
	let detail = $state<UserDetailResponse | null>(null);
	let stats = $state<UserStatsResponse | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

	$effect(() => {
		const nextDefault = preferencesStore.prefs.defaultStatsRange;
		if (nextDefault !== lastDefaultRange) {
			lastDefaultRange = nextDefault;
			range = nextDefault;
		}
	});

	const userId = $derived(page.params.id ?? '');

	async function loadProfile(id: string, selectedRange: StatsRange) {
		if (!id) return;
		loading = true;
		error = null;
		try {
			const [dRes, sRes] = await Promise.all([
				api.GET('/api/v1/users/{id}', { params: { path: { id } } }),
				api.GET('/api/v1/users/{id}/stats', { params: { path: { id }, query: { range: selectedRange } } })
			]);
			detail = unwrap(dRes);
			stats = unwrap(sRes);
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Failed to load user profile';
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void loadProfile(userId, range);
	});

	const dailyBars = $derived.by<BarPoint[]>(() => {
		const days = stats?.playsPerDay ?? [];
		return days.slice(-14).map((d) => ({
			label: d.date.slice(8, 10),
			value: d.playCount,
			tooltip: `${d.date}: ${d.playCount} plays (${formatDurationSec(d.watchDurationSec)})`
		}));
	});

	const peakDay = $derived.by(() => {
		const days = stats?.playsPerDay ?? [];
		if (days.length === 0) return null;
		return days.reduce((best, cur) => (cur.playCount > best.playCount ? cur : best), days[0]);
	});
</script>

<svelte:head>
	<title>{detail ? `${detail.user.displayName || detail.user.username} · Users · Miru` : 'User Profile · Miru'}</title>
</svelte:head>

<div class="flex flex-col gap-5">
	{#if error}
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
						<ArrowLeft class="h-3 w-3" /> Users / {detail.user.username}
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

		<!-- KPIs -->
		<section class="grid grid-cols-2 gap-3 lg:grid-cols-4 lg:gap-4">
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
				label="Direct Play"
				value={`${stats.decisionBreakdown.directPlayPercent.toFixed(1)}%`}
				subtitle={`${stats.decisionBreakdown.directPlay} Direct · ${stats.decisionBreakdown.directStream} Remux`}
				dotClass="bg-direct-play"
			/>
			<KpiCard
				label="HW Transcode"
				value={`${stats.decisionBreakdown.hwTranscodePercent.toFixed(1)}%`}
				subtitle={`${stats.decisionBreakdown.hwTranscode} GPU · ${stats.decisionBreakdown.transcode} total transcodes`}
				dotClass="bg-hw-transcode"
			/>
		</section>

		<!-- Charts Row -->
		<section class="grid grid-cols-1 gap-4 lg:grid-cols-3">
			<div class="flex flex-col justify-between gap-4 rounded-xl border border-border bg-surface p-4 lg:col-span-2">
				<div class="flex items-center justify-between gap-2">
					<h2 class="font-heading text-sm font-semibold text-fg-primary">
						Daily Activity ({range})
					</h2>
					{#if peakDay && peakDay.playCount > 0}
						<span class="font-mono text-[11px] font-medium text-primary-hover">
							Peak: {peakDay.playCount} plays ({peakDay.date})
						</span>
					{/if}
				</div>
				<BarChart points={dailyBars} heightClass="h-36" />
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
										{plat.playCount} · {plat.percent.toFixed(1)}%
									</span>
								</div>
								<div class="h-1.5 w-full overflow-hidden rounded-full bg-subtle">
									<div
										class={idx === 0 ? 'h-full rounded-full bg-primary' : 'h-full rounded-full bg-cyan'}
										style:width={`${Math.max(4, plat.percent)}%`}
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
								Sec {lib.librarySectionKey} · {lib.percent.toFixed(0)}%
							</Badge>
						{/each}
					</div>
				{/if}
			</div>
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
								<p class="truncate font-heading text-xs font-semibold text-fg-primary">
									{titles.primary}
									{#if titles.secondary}
										<span class="font-sans font-normal text-fg-secondary"> · {titles.secondary}</span>
									{/if}
								</p>
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
