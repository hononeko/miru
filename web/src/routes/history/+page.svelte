<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { Search, ChevronLeft, ChevronRight, X } from 'lucide-svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type { HistoryDetailResponse, HistoryItemDto, UserSummaryDto } from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import Sheet from '$components/ui/Sheet.svelte';
	import PlayDetailContent from '$components/miru/PlayDetailContent.svelte';
	import UnlinkedUserEmptyState from '$components/miru/UnlinkedUserEmptyState.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
	import {
		cn,
		formatDurationSec,
		formatMediaTitle,
		formatShortDateTime,
		getDecisionMeta
	} from '$lib/utils';

	type MediaFilter = '' | 'movie' | 'episode' | 'track' | 'clip' | 'live_tv';
	type DecisionFilter = '' | 'direct_play' | 'direct_stream' | 'transcode';
	type SortField = 'stopped_at' | 'started_at' | 'watched_duration' | 'completion_percent' | 'title';

	let items = $state<HistoryItemDto[]>([]);
	let users = $state<UserSummaryDto[]>([]);
	let pageNum = $state(1);
	let pageSize = $state(25);
	let totalCount = $state(0);
	let totalPages = $state(0);
	let loading = $state(true);
	let error = $state<string | null>(null);

	let searchInput = $state('');
	let q = $state('');
	let userId = $state('');
	let mediaType = $state<MediaFilter>('');
	let decision = $state<DecisionFilter>('');
	let sortBy = $state<SortField>('stopped_at');
	let sortDir = $state<'asc' | 'desc'>('desc');

	// Detail drawer / mobile bottom sheet
	let selectedId = $state<string | null>(null);
	let detailOpen = $state(false);
	let detail = $state<HistoryDetailResponse | null>(null);
	let detailLoading = $state(false);
	let detailError = $state<string | null>(null);

	let debounceTimer: ReturnType<typeof setTimeout> | null = null;

	function onSearchInput(val: string) {
		searchInput = val;
		if (debounceTimer) clearTimeout(debounceTimer);
		debounceTimer = setTimeout(() => {
			q = val.trim();
			pageNum = 1;
		}, 250);
	}

	function clearFilters() {
		searchInput = '';
		q = '';
		userId = '';
		mediaType = '';
		decision = '';
		sortBy = 'stopped_at';
		sortDir = 'desc';
		pageNum = 1;
	}

	async function loadUsers() {
		if (!authStore.isAdmin) {
			users = [];
			return;
		}
		try {
			const res = unwrap(await api.GET('/api/v1/users'));
			users = res.users;
		} catch {
			// Optional user filter dropdown
		}
	}

	async function loadHistory() {
		if (authStore.isUnlinkedUser) {
			items = [];
			totalCount = 0;
			totalPages = 0;
			error = null;
			loading = false;
			return;
		}
		loading = true;
		error = null;
		try {
			const res = unwrap(
				await api.GET('/api/v1/history', {
					params: {
						query: {
							q: q || undefined,
							userId: authStore.isAdmin ? userId || undefined : undefined,
							mediaType: mediaType || undefined,
							decision: decision || undefined,
							sortBy,
							sortDir,
							page: pageNum,
							pageSize
						}
					}
				})
			);
			items = res.items;
			totalCount = res.totalCount;
			totalPages = res.totalPages;
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Failed to load playback history';
		} finally {
			loading = false;
		}
	}

	async function openDetail(id: string) {
		selectedId = id;
		detailOpen = true;
		detailLoading = true;
		detailError = null;
		detail = null;
		try {
			detail = unwrap(
				await api.GET('/api/v1/history/{id}', {
					params: { path: { id } }
				})
			);
		} catch (e) {
			detailError = e instanceof MiruApiError ? e.detail : 'Failed to load play details';
		} finally {
			detailLoading = false;
		}
	}

	onMount(() => {
		void loadUsers();
	});

	let lastUrlQ = $state<string | null>(null);
	$effect(() => {
		const paramQ = page.url.searchParams.get('q')?.trim() ?? '';
		if (paramQ !== lastUrlQ) {
			lastUrlQ = paramQ;
			if (paramQ) {
				searchInput = paramQ;
				q = paramQ;
				pageNum = 1;
			}
		}
	});

	$effect(() => {
		// Re-run whenever query inputs change
		void authStore.isUnlinkedUser;
		void q;
		void userId;
		void mediaType;
		void decision;
		void sortBy;
		void sortDir;
		void pageNum;
		void pageSize;
		void loadHistory();
	});

	const hasActiveFilters = $derived(Boolean(q || userId || mediaType || decision));
</script>

<svelte:head>
	<title>Playback History · Miru</title>
</svelte:head>

<div class="flex flex-col gap-5">
	<!-- Page Header -->
	<div class="flex flex-wrap items-center justify-between gap-3">
		<div>
			<h1 class="font-display text-2xl font-bold tracking-wide text-fg-primary sm:text-3xl">
				Playback History
			</h1>
			<p class="mt-0.5 text-xs text-fg-secondary sm:text-sm">
				{authStore.isAdmin
					? 'Searchable, filterable playback log across all users & devices'
					: 'Your scoped playback history across linked media profiles and devices'}
			</p>
		</div>
		<Badge tone="cyan" mono>{totalCount.toLocaleString()} PLAYS</Badge>
	</div>

	{#if authStore.isUnlinkedUser}
		<UnlinkedUserEmptyState context="playback history" />
	{:else}
	<!-- Filter Toolbar -->
	<div class="flex flex-col gap-2.5 lg:flex-row lg:items-center">
		<div class="relative flex-1">
			<Search class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-fg-muted" />
			<input
				type="search"
				value={searchInput}
				oninput={(e) => onSearchInput(e.currentTarget.value)}
				placeholder="Search title, show, season…"
				aria-label="Search playback history"
				class="w-full rounded-lg border border-input-border bg-surface py-2 pl-9 pr-3 text-sm text-fg-primary placeholder:text-fg-muted focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			/>
		</div>

		<div class="flex flex-wrap items-center gap-2">
			{#if authStore.isAdmin}
				<select
					bind:value={userId}
					onchange={() => (pageNum = 1)}
					aria-label="Filter by user"
					class="rounded-lg border border-input-border bg-surface px-2.5 py-2 text-xs font-medium text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				>
					<option value="">All Users</option>
					{#each users as u}
						<option value={u.id}>{u.displayName || u.username}</option>
					{/each}
				</select>
			{/if}

			<select
				bind:value={mediaType}
				onchange={() => (pageNum = 1)}
				aria-label="Filter by media type"
				class="rounded-lg border border-input-border bg-surface px-2.5 py-2 text-xs font-medium text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			>
				<option value="">All Types</option>
				<option value="movie">Movies</option>
				<option value="episode">Episodes</option>
				<option value="track">Music</option>
				<option value="live_tv">Live TV</option>
			</select>

			<select
				bind:value={decision}
				onchange={() => (pageNum = 1)}
				aria-label="Filter by stream decision"
				class="rounded-lg border border-input-border bg-surface px-2.5 py-2 text-xs font-medium text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			>
				<option value="">All Decisions</option>
				<option value="direct_play">Direct Play</option>
				<option value="direct_stream">Direct Stream</option>
				<option value="transcode">Transcode</option>
			</select>

			<select
				bind:value={sortBy}
				onchange={() => (pageNum = 1)}
				aria-label="Sort field"
				class="rounded-lg border border-input-border bg-surface px-2.5 py-2 text-xs font-medium text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			>
				<option value="stopped_at">Sort: Stopped</option>
				<option value="started_at">Sort: Started</option>
				<option value="watched_duration">Sort: Duration</option>
				<option value="completion_percent">Sort: Completion</option>
				<option value="title">Sort: Title</option>
			</select>

			<Button
				variant="outline"
				size="sm"
				onclick={() => {
					sortDir = sortDir === 'desc' ? 'asc' : 'desc';
					pageNum = 1;
				}}
				aria-label="Toggle sort direction"
			>
				{sortDir === 'desc' ? '↓ Desc' : '↑ Asc'}
			</Button>

			{#if hasActiveFilters}
				<Button variant="ghost" size="sm" onclick={clearFilters}>
					<X class="h-3.5 w-3.5" />
					Reset
				</Button>
			{/if}
		</div>
	</div>

	{#if error}
		<div class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
			{error}
		</div>
	{/if}

	<!-- DESKTOP TABLE (>= md) -->
	<div class="hidden md:block overflow-hidden rounded-xl border border-border bg-surface">
		<div class="overflow-x-auto">
			<table class="w-full border-collapse text-left">
				<thead>
					<tr class="border-b border-border bg-elevated font-heading text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
						<th class="px-4 py-2.5">When</th>
						<th class="px-4 py-2.5">User</th>
						<th class="px-4 py-2.5">Media</th>
						<th class="px-4 py-2.5">Player</th>
						<th class="px-4 py-2.5">Decision</th>
						<th class="px-4 py-2.5 text-right">Duration</th>
						<th class="px-4 py-2.5 text-right">Done</th>
					</tr>
				</thead>
				<tbody class="divide-y divide-border text-xs">
					{#if loading && items.length === 0}
						<tr>
							<td colspan="7" class="px-4 py-12 text-center font-mono text-xs text-fg-muted">
								Loading playback history…
							</td>
						</tr>
					{:else if items.length === 0}
						<tr>
							<td colspan="7" class="px-4 py-12 text-center text-xs text-fg-muted">
								No playback records match your current filters.
							</td>
						</tr>
					{:else}
						{#each items as item (item.id)}
							{@const titles = formatMediaTitle(item)}
							{@const dec = getDecisionMeta(item.streamDecision, item.hwTranscode)}
							{@const selected = selectedId === item.id && detailOpen}
							<tr
								onclick={() => openDetail(item.id)}
								class={cn(
									'cursor-pointer transition-colors',
									selected ? 'bg-primary-soft/80' : 'hover:bg-elevated/70'
								)}
							>
								<td class="whitespace-nowrap px-4 py-3 font-mono text-[11px] text-fg-secondary tabular-nums">
									{formatShortDateTime(item.stoppedAtMs, preferencesStore.prefs)}
								</td>
								<td class="whitespace-nowrap px-4 py-3 font-heading text-xs font-semibold text-fg-primary">
									{item.displayName || item.username || item.userId}
								</td>
								<td class="max-w-[300px] px-4 py-3">
									<button
										type="button"
										onclick={(e) => {
											e.stopPropagation();
											void mediaDetailsModal.open(item.ratingKey);
										}}
										class="block max-w-full cursor-pointer text-left hover:text-cyan-hi hover:underline"
									>
										<p class="truncate font-heading text-xs font-semibold text-fg-primary hover:text-cyan-hi">
											{titles.primary}
										</p>
									</button>
									<p class="truncate text-[11px] text-fg-muted">{titles.secondary}</p>
								</td>
								<td class="whitespace-nowrap px-4 py-3 text-fg-secondary">
									{item.playerName || item.playerProduct || item.playerPlatform || '—'}
								</td>
								<td class="whitespace-nowrap px-4 py-3">
									<Badge tone={dec.tone} dot>{dec.shortLabel}</Badge>
								</td>
								<td class="whitespace-nowrap px-4 py-3 text-right font-mono text-[11px] text-fg-primary tabular-nums">
									{formatDurationSec(item.watchedDurationSec)}
								</td>
								<td
									class={cn(
										'whitespace-nowrap px-4 py-3 text-right font-mono text-[11px] font-semibold tabular-nums',
										item.isWatched ? 'text-direct-play' : 'text-fg-muted'
									)}
								>
									{Math.round(item.completionPercent)}%
								</td>
							</tr>
						{/each}
					{/if}
				</tbody>
			</table>
		</div>
	</div>

	<!-- MOBILE COMPACT CARDS (< md) -->
	<div class="flex flex-col gap-2.5 md:hidden">
		{#if loading && items.length === 0}
			<div class="rounded-xl border border-border bg-surface p-8 text-center font-mono text-xs text-fg-muted">
				Loading playback history…
			</div>
		{:else if items.length === 0}
			<div class="rounded-xl border border-border bg-surface p-8 text-center text-xs text-fg-muted">
				No playback records match your current filters.
			</div>
		{:else}
			{#each items as item (item.id)}
				{@const titles = formatMediaTitle(item)}
				{@const dec = getDecisionMeta(item.streamDecision, item.hwTranscode)}
				{@const selected = selectedId === item.id && detailOpen}
				<div
					class={cn(
						'flex w-full flex-col gap-2 rounded-xl border p-3 text-left transition-colors',
						selected
							? 'border-primary bg-primary-soft/60'
							: 'border-border bg-surface'
					)}
				>
					<div class="flex items-start justify-between gap-2">
						<div class="min-w-0">
							<button
								type="button"
								onclick={() => void mediaDetailsModal.open(item.ratingKey)}
								class="block max-w-full cursor-pointer text-left hover:text-cyan-hi hover:underline"
							>
								<p class="truncate font-heading text-sm font-semibold text-fg-primary">
									{titles.primary}
								</p>
							</button>
							<p class="truncate text-[11px] text-fg-secondary">
								{titles.secondary} · {item.displayName || item.username || item.userId}
							</p>
						</div>
						<span
							class={cn(
								'shrink-0 font-mono text-xs font-bold tabular-nums',
								item.isWatched ? 'text-direct-play' : 'text-fg-muted'
							)}
						>
							{Math.round(item.completionPercent)}%
						</span>
					</div>
					<div class="flex items-center justify-between gap-2">
						<Badge tone={dec.tone} dot>{dec.label}</Badge>
						<div class="flex items-center gap-2.5">
							<span class="font-mono text-[10px] text-fg-muted tabular-nums">
								{formatDurationSec(item.watchedDurationSec)} · {formatShortDateTime(item.stoppedAtMs, preferencesStore.prefs)}
							</span>
							<button
								type="button"
								onclick={() => openDetail(item.id)}
								class="cursor-pointer font-mono text-[10px] font-semibold text-cyan-hi hover:underline"
							>
								Details
							</button>
						</div>
					</div>
				</div>
			{/each}
		{/if}
	</div>

	<!-- Pagination Footer -->
	{#if totalPages > 0}
		<div class="flex flex-wrap items-center justify-between gap-3 pt-1">
			<span class="font-mono text-[11px] text-fg-muted tabular-nums">
				Page {pageNum} of {totalPages} · {totalCount.toLocaleString()} total
			</span>
			<div class="flex items-center gap-1.5">
				<Button
					variant="secondary"
					size="sm"
					disabled={pageNum <= 1 || loading}
					onclick={() => (pageNum = Math.max(1, pageNum - 1))}
				>
					<ChevronLeft class="h-3.5 w-3.5" />
					Prev
				</Button>
				<Button
					variant="secondary"
					size="sm"
					disabled={pageNum >= totalPages || loading}
					onclick={() => (pageNum = Math.min(totalPages, pageNum + 1))}
				>
					Next
					<ChevronRight class="h-3.5 w-3.5" />
				</Button>
			</div>
		</div>
	{/if}
	{/if}
</div>

<!-- Responsive Slide-Over Drawer (Desktop) / Bottom Sheet (Mobile) -->
<Sheet
	bind:open={detailOpen}
	onOpenChange={(v) => (detailOpen = v)}
	title="Play Details"
	description="Technical stream, player, and timing breakdown"
>
	{#if detailLoading}
		<div class="py-10 text-center font-mono text-xs text-fg-muted">Loading session details…</div>
	{:else if detailError}
		<div class="rounded-lg border border-danger/50 bg-danger-soft p-3 text-xs text-danger">
			{detailError}
		</div>
	{:else if detail}
		<PlayDetailContent {detail} />
	{/if}
</Sheet>
