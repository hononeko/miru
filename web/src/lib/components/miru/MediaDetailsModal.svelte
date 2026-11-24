<script lang="ts">
	import { onMount } from 'svelte';
	import {
		Film,
		Tv,
		Layers,
		Clock,
		Calendar,
		FolderOpen,
		Play,
		Users,
		HardDrive,
		Subtitles,
		History,
		AlertCircle
	} from 'lucide-svelte';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import ProxiedImage from './ProxiedImage.svelte';
	import { sheetDrag } from '$lib/actions/sheetDrag';
	import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
	import {
		formatBandwidthKbps,
		formatBytes,
		formatClockMs,
		formatDurationSec,
		formatRelativeTime,
		formatStatPercent
	} from '$lib/utils';

	const titleId = 'media-details-modal-title';

	let dialogEl: HTMLDialogElement | undefined = $state();
	// Backdrop candidates that failed to load, so the hero falls back to the next one.
	let failedBackdrops = $state<string[]>([]);

	const data = $derived(mediaDetailsModal.data);

	$effect(() => {
		const el = dialogEl;
		if (!el) return;
		if (mediaDetailsModal.isOpen && !el.open) {
			el.showModal();
		} else if (!mediaDetailsModal.isOpen && el.open) {
			el.close();
		}
	});

	$effect(() => {
		void data;
		failedBackdrops = [];
	});

	onMount(() => {
		const el = dialogEl;
		if (!el || 'closedBy' in HTMLDialogElement.prototype) return;

		const isOutside = (e: PointerEvent) => {
			const rect = el.getBoundingClientRect();
			return (
				e.clientX < rect.left ||
				e.clientX > rect.right ||
				e.clientY < rect.top ||
				e.clientY > rect.bottom
			);
		};

		let pressedOutside = false;
		const onPointerDown = (e: PointerEvent) => {
			pressedOutside = isOutside(e);
		};
		const onPointerUp = (e: PointerEvent) => {
			if (pressedOutside && isOutside(e)) {
				el.close();
			}
			pressedOutside = false;
		};

		el.addEventListener('pointerdown', onPointerDown);
		el.addEventListener('pointerup', onPointerUp);
		return () => {
			el.removeEventListener('pointerdown', onPointerDown);
			el.removeEventListener('pointerup', onPointerUp);
		};
	});

	function handleNativeClose() {
		if (mediaDetailsModal.isOpen) {
			mediaDetailsModal.close();
		}
	}

	// First backdrop that has not failed: the item's art, then its poster, then the season/series poster.
	const heroBackdrop = $derived.by(() => {
		if (!data) return null;
		return (
			[data.artUrl, data.thumbUrl, data.season?.thumbUrl, data.series?.thumbUrl].find(
				(candidate): candidate is string =>
					!!candidate && candidate.startsWith('/api/v1/') && !failedBackdrops.includes(candidate)
			) ?? null
		);
	});

	const heroBackdropSrc = $derived(
		heroBackdrop ? (heroBackdrop.includes('?') ? heroBackdrop : `${heroBackdrop}?w=960`) : null
	);

	const heroPosterSrc = $derived.by(() => {
		if (!data) return null;
		return data.thumbUrl ?? data.season?.thumbUrl ?? data.series?.thumbUrl ?? null;
	});

	const episodeCode = $derived.by(() => {
		if (!data) return null;
		if (data.mediaType === 'episode') {
			const s = data.parentIndex != null ? `S${String(data.parentIndex).padStart(2, '0')}` : '';
			const e = data.index != null ? `E${String(data.index).padStart(2, '0')}` : '';
			return [s, e].filter(Boolean).join(' · ') || null;
		}
		if (data.mediaType === 'season' && data.index != null) {
			return `SEASON ${data.index}`;
		}
		return null;
	});

	const heroSubtitle = $derived.by(() => {
		if (!data) return '';
		if (data.mediaType === 'episode') {
			return [data.series?.title ?? data.grandparentTitle, data.season?.title ?? data.parentTitle, data.year]
				.filter(Boolean)
				.join(' · ');
		}
		if (data.mediaType === 'season') {
			return [data.series?.title ?? data.parentTitle, data.year].filter(Boolean).join(' · ');
		}
		return [data.year, data.durationMs ? formatClockMs(data.durationMs) : null, data.studio]
			.filter(Boolean)
			.join(' · ');
	});

	const seasonProgressPercent = $derived.by(() => {
		const cur = data?.season?.currentEpisodeIndex;
		const total = data?.season?.episodeCount;
		if (cur == null || total == null || total <= 0) return null;
		return Math.min(100, Math.max(4, (cur / total) * 100));
	});

	const historyHref = $derived.by(() => {
		if (!data) return '/history';
		const query = data.series?.title ?? data.title;
		return `/history?q=${encodeURIComponent(query)}`;
	});

	const technicalItems = $derived.by(() => {
		const tech = data?.technical;
		if (!tech) return [];
		const videoValue = [
			tech.videoResolution?.toUpperCase(),
			tech.videoCodec?.toUpperCase(),
			tech.videoProfile ? `(${tech.videoProfile})` : null
		]
			.filter(Boolean)
			.join(' ');
		const audioValue = [
			tech.audioCodec?.toUpperCase(),
			tech.audioChannels ? `${tech.audioChannels}ch` : null
		]
			.filter(Boolean)
			.join(' · ');

		return [
			{ label: 'Video', value: videoValue || '—' },
			{ label: 'Audio', value: audioValue || '—' },
			{ label: 'Container', value: tech.container?.toUpperCase() || '—' },
			{ label: 'Bitrate', value: tech.bitrateKbps ? formatBandwidthKbps(tech.bitrateKbps) : '—' },
			{ label: 'File Size', value: tech.fileSizeBytes ? formatBytes(tech.fileSizeBytes) : '—' },
			{ label: 'Duration', value: data?.durationMs ? formatClockMs(data.durationMs) : '—' }
		];
	});

	const modalHeading = $derived.by(() => {
		if (data) return data.title;
		if (mediaDetailsModal.loading) return 'Loading media details';
		return 'Media Details';
	});
</script>

<dialog
	bind:this={dialogEl}
	aria-labelledby={titleId}
	onclose={handleNativeClose}
	use:sheetDrag={{ onDismiss: () => mediaDetailsModal.close() }}
	{...({ closedby: 'any' } as Record<string, string>)}
	class="media-details-dialog fixed inset-x-0 bottom-0 top-auto z-50 m-0 max-h-[90dvh] w-full max-w-none overflow-hidden rounded-t-2xl border-t border-border-strong bg-surface text-fg-primary shadow-2xl focus:outline-none md:inset-0 md:m-auto md:max-h-[88dvh] md:w-[calc(100%-2rem)] md:max-w-3xl md:rounded-2xl md:border"
>
	{#if mediaDetailsModal.loading}
		<div class="relative border-b border-border px-5 pt-7 pb-4 md:pt-4">
			<div
				data-sheet-drag-handle
				class="absolute inset-x-0 top-0 z-10 flex cursor-grab touch-none justify-center pt-2.5 pb-2 active:cursor-grabbing md:hidden"
			>
				<div class="h-1 w-10 rounded-full bg-border-strong"></div>
			</div>
			<h1 id={titleId} class="font-display text-lg font-bold text-fg-primary">
				{modalHeading}
			</h1>
		</div>
		<div class="flex flex-1 flex-col items-center justify-center gap-3 px-6 py-16 text-center">
			<div class="h-8 w-8 animate-spin rounded-full border-2 border-cyan border-t-transparent"></div>
			<p class="font-mono text-xs text-fg-muted">Fetching metadata &amp; telemetry…</p>
			<Button variant="ghost" size="sm" onclick={() => mediaDetailsModal.close()}>Close</Button>
		</div>
	{:else if mediaDetailsModal.error}
		<div class="relative border-b border-border px-5 pt-7 pb-4 md:pt-4">
			<div
				data-sheet-drag-handle
				class="absolute inset-x-0 top-0 z-10 flex cursor-grab touch-none justify-center pt-2.5 pb-2 active:cursor-grabbing md:hidden"
			>
				<div class="h-1 w-10 rounded-full bg-border-strong"></div>
			</div>
			<h1 id={titleId} class="font-display text-lg font-bold text-fg-primary">
				{modalHeading}
			</h1>
		</div>
		<div class="flex flex-1 flex-col items-center justify-center gap-3 px-6 py-14 text-center">
			<div class="flex h-11 w-11 items-center justify-center rounded-full bg-danger-soft text-danger">
				<AlertCircle class="h-5 w-5" />
			</div>
			<div class="max-w-md">
				<p class="font-heading text-sm font-semibold text-fg-primary">Unable to load media details</p>
				<p class="mt-1 text-xs text-fg-secondary">{mediaDetailsModal.error}</p>
			</div>
			<div class="flex items-center gap-2">
				{#if mediaDetailsModal.ratingKey}
					<Button
						variant="secondary"
						size="sm"
						onclick={() => {
							if (mediaDetailsModal.ratingKey) void mediaDetailsModal.open(mediaDetailsModal.ratingKey);
						}}
					>
						Retry
					</Button>
				{/if}
				<Button variant="ghost" size="sm" onclick={() => mediaDetailsModal.close()}>Close</Button>
			</div>
		</div>
	{:else if data}
		<!-- Scrollable Body -->
		<div class="flex-1 overflow-y-auto">
			<!-- Hero Banner -->
			<div class="relative overflow-hidden border-b border-border bg-elevated">
				<!-- Mobile drag indicator sits inside the hero so art reaches the top edge without floating over scrolled content -->
				<div
					data-sheet-drag-handle
					class="absolute inset-x-0 top-0 z-10 flex cursor-grab touch-none justify-center pt-2.5 pb-2 active:cursor-grabbing md:hidden"
				>
					<div class="h-1 w-10 rounded-full bg-border-strong"></div>
				</div>
				{#if heroBackdropSrc && heroBackdrop}
					{@const currentBackdrop = heroBackdrop}
					<img
						src={heroBackdropSrc}
						alt=""
						aria-hidden="true"
						onerror={() => {
							if (!failedBackdrops.includes(currentBackdrop)) {
								failedBackdrops = [...failedBackdrops, currentBackdrop];
							}
						}}
						class="pointer-events-none absolute inset-0 h-full w-full object-cover opacity-25 blur-[1px]"
					/>
				{/if}
				<div
					class="pointer-events-none absolute inset-0 bg-gradient-to-t from-surface via-surface/80 to-surface/40"
				></div>

				<div class="relative flex flex-col gap-4 p-5 pt-7 sm:flex-row sm:items-end sm:gap-5 sm:p-6 sm:pt-7 md:pt-6">
					<ProxiedImage
						src={heroPosterSrc}
						alt={data.title}
						fallbackLabel={data.title}
						variant="poster"
						class="h-32 w-[86px] shrink-0 shadow-md sm:h-40 sm:w-[108px]"
					/>

					<div class="flex min-w-0 flex-1 flex-col gap-2">
						<div class="flex flex-wrap items-center gap-1.5">
							<Badge tone="primary" mono>{data.mediaType.replace('_', ' ').toUpperCase()}</Badge>
							{#if episodeCode}
								<Badge tone="cyan" mono>{episodeCode}</Badge>
							{/if}
							{#if data.contentRating ?? data.series?.contentRating}
								<Badge tone="neutral" mono>{data.contentRating ?? data.series?.contentRating}</Badge>
							{/if}
							{#if data.technical?.videoResolution}
								<Badge tone="direct-stream" mono>{data.technical.videoResolution.toUpperCase()}</Badge>
							{/if}
						</div>

						<h1 id={titleId} class="font-display text-2xl font-bold leading-tight text-fg-primary sm:text-3xl">
							{data.title}
						</h1>

						{#if heroSubtitle}
							<p class="text-xs font-medium text-fg-secondary sm:text-sm">{heroSubtitle}</p>
						{/if}

						{#if data.genres.length > 0 || (data.series?.genres?.length ?? 0) > 0}
							{@const displayGenres = data.genres.length > 0 ? data.genres : (data.series?.genres ?? [])}
							<div class="flex flex-wrap gap-1.5 pt-0.5">
								{#each displayGenres.slice(0, 6) as genre}
									<span
										class="rounded-md border border-border bg-surface/85 px-2 py-0.5 text-[11px] font-medium text-fg-secondary"
									>
										{genre}
									</span>
								{/each}
							</div>
						{/if}
					</div>
				</div>
			</div>

			<!-- Main Details Content -->
			<div class="flex flex-col gap-4 p-5 sm:p-6">
				<!-- Synopsis -->
				<section class="flex flex-col gap-1.5">
					<h2 class="font-heading text-[11px] font-semibold uppercase tracking-wider text-fg-muted">
						Synopsis
					</h2>
					<p class="text-xs leading-relaxed text-fg-secondary sm:text-sm">
						{data.summary || 'No synopsis available for this item.'}
					</p>
				</section>

				<!-- Dedicated Series Overview + Season Details Cards -->
				{#if data.series || data.season}
					<div class="grid grid-cols-1 gap-3.5 md:grid-cols-2">
						{#if data.series}
							<section class="flex flex-col gap-3 rounded-xl border border-border bg-elevated/70 p-4">
								<div class="flex items-start justify-between gap-2">
									<div class="flex items-center gap-2">
										<Tv class="h-4 w-4 text-cyan-hi" />
										<h2 class="font-heading text-xs font-semibold uppercase tracking-wider text-cyan-hi">
											Series Overview
										</h2>
									</div>
									{#if data.series.ratingKey && data.series.ratingKey !== data.ratingKey}
										<button
											type="button"
											onclick={() => {
												if (data.series?.ratingKey) void mediaDetailsModal.open(data.series.ratingKey);
											}}
											class="cursor-pointer font-mono text-[11px] font-semibold text-cyan-hi hover:underline"
										>
											View Series →
										</button>
									{/if}
								</div>

								<div>
									<p class="font-heading text-sm font-bold text-fg-primary">{data.series.title}</p>
									{#if data.series.year || data.series.studio || data.series.contentRating}
										<p class="mt-0.5 text-xs text-fg-secondary">
											{[data.series.year, data.series.studio, data.series.contentRating]
												.filter(Boolean)
												.join(' · ')}
										</p>
									{/if}
								</div>

								<div class="grid grid-cols-2 gap-2">
									<div class="rounded-lg border border-border bg-surface px-3 py-2">
										<span class="block text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
											Seasons
										</span>
										<span class="font-mono text-base font-bold text-fg-primary tabular-nums">
											{data.series.seasonCount != null ? data.series.seasonCount : '—'}
										</span>
									</div>
									<div class="rounded-lg border border-border bg-surface px-3 py-2">
										<span class="block text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
											Total Episodes
										</span>
										<span class="font-mono text-base font-bold text-fg-primary tabular-nums">
											{data.series.episodeCount != null ? data.series.episodeCount : '—'}
										</span>
									</div>
								</div>

								{#if data.series.summary}
									<p class="line-clamp-4 text-xs leading-relaxed text-fg-secondary">
										{data.series.summary}
									</p>
								{/if}
							</section>
						{/if}

						{#if data.season}
							<section class="flex flex-col gap-3 rounded-xl border border-border bg-elevated/70 p-4">
								<div class="flex items-start justify-between gap-2">
									<div class="flex items-center gap-2">
										<Layers class="h-4 w-4 text-primary-text" />
										<h2 class="font-heading text-xs font-semibold uppercase tracking-wider text-primary-text">
											Season Details
										</h2>
									</div>
									{#if data.season.currentEpisodeIndex != null && data.season.episodeCount != null}
										<Badge tone="primary" mono>
											Episode {data.season.currentEpisodeIndex} of {data.season.episodeCount}
										</Badge>
									{:else if data.season.currentEpisodeIndex != null}
										<Badge tone="primary" mono>Episode {data.season.currentEpisodeIndex}</Badge>
									{:else if data.season.episodeCount != null}
										<Badge tone="neutral" mono>{data.season.episodeCount} Episodes</Badge>
									{/if}
								</div>

								<div class="flex items-center justify-between gap-2">
									<div>
										<p class="font-heading text-sm font-bold text-fg-primary">{data.season.title}</p>
										<p class="mt-0.5 text-xs text-fg-secondary">
											{[
												data.season.seasonIndex != null ? `Season ${data.season.seasonIndex}` : null,
												data.season.year,
												data.season.episodeCount != null ? `${data.season.episodeCount} episodes` : null
											]
												.filter(Boolean)
												.join(' · ')}
										</p>
									</div>
									{#if data.season.ratingKey && data.season.ratingKey !== data.ratingKey}
										<button
											type="button"
											onclick={() => {
												if (data.season?.ratingKey) void mediaDetailsModal.open(data.season.ratingKey);
											}}
											class="shrink-0 cursor-pointer font-mono text-[11px] font-semibold text-primary-text hover:underline"
										>
											View Season →
										</button>
									{/if}
								</div>

								{#if seasonProgressPercent !== null}
									<div class="flex flex-col gap-1">
										<div class="flex items-center justify-between font-mono text-[10px] text-fg-muted">
											<span>Season position</span>
											<span class="tabular-nums">
												{data.season.currentEpisodeIndex}/{data.season.episodeCount}
											</span>
										</div>
										<div class="h-1.5 w-full overflow-hidden rounded-full bg-subtle">
											<div
												class="h-full rounded-full bg-primary"
												style:width={`${seasonProgressPercent}%`}
											></div>
										</div>
									</div>
								{/if}

								{#if data.season.summary}
									<p class="line-clamp-4 text-xs leading-relaxed text-fg-secondary">
										{data.season.summary}
									</p>
								{/if}
							</section>
						{/if}
					</div>
				{:else}
					<!-- Credits & Library strip for standalone movies / items -->
					<section class="grid grid-cols-2 gap-3 rounded-xl border border-border bg-elevated/70 p-4 sm:grid-cols-4">
						<div>
							<span class="flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
								<Film class="h-3 w-3" /> Director
							</span>
							<p class="mt-1 text-xs font-medium text-fg-primary">
								{data.directors.length > 0 ? data.directors.join(', ') : '—'}
							</p>
						</div>
						<div>
							<span class="flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
								<Calendar class="h-3 w-3" /> Released
							</span>
							<p class="mt-1 font-mono text-xs font-medium text-fg-primary">
								{data.originallyAvailableAt || (data.year ? String(data.year) : '—')}
							</p>
						</div>
						<div>
							<span class="flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
								<FolderOpen class="h-3 w-3" /> Library
							</span>
							<p class="mt-1 text-xs font-medium text-fg-primary">
								{data.librarySectionTitle || '—'}
							</p>
						</div>
						<div>
							<span class="flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
								<Clock class="h-3 w-3" /> Added
							</span>
							<p class="mt-1 font-mono text-xs font-medium text-fg-primary">
								{data.addedAtMs ? formatRelativeTime(data.addedAtMs) : '—'}
							</p>
						</div>
					</section>
				{/if}

				<!-- Technical Specs & Miru Play Summary -->
				<div class="grid grid-cols-1 gap-3.5 md:grid-cols-2">
					{#if data.technical}
						<section class="flex flex-col gap-3 rounded-xl border border-border bg-elevated/70 p-4">
							<div class="flex items-center gap-2">
								<HardDrive class="h-4 w-4 text-cyan-hi" />
								<h2 class="font-heading text-xs font-semibold uppercase tracking-wider text-cyan-hi">
									Technical Specs
								</h2>
							</div>
							<dl class="grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
								{#each technicalItems as spec}
									<div>
										<dt class="text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
											{spec.label}
										</dt>
										<dd class="mt-0.5 font-mono text-xs font-medium text-fg-primary tabular-nums">
											{spec.value}
										</dd>
									</div>
								{/each}
							</dl>
							{#if data.technical.subtitleLanguages.length > 0}
								<div class="border-t border-border pt-2.5">
									<span class="flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
										<Subtitles class="h-3 w-3" /> Subtitles
									</span>
									<div class="mt-1.5 flex flex-wrap gap-1">
										{#each data.technical.subtitleLanguages as lang}
											<Badge tone="neutral" mono>{lang}</Badge>
										{/each}
									</div>
								</div>
							{/if}
						</section>
					{/if}

					<section
						class={data.technical
							? 'flex flex-col gap-3 rounded-xl border border-border bg-elevated/70 p-4'
							: 'flex flex-col gap-3 rounded-xl border border-border bg-elevated/70 p-4 md:col-span-2'}
					>
						<div class="flex flex-wrap items-center justify-between gap-2">
							<div class="flex items-center gap-2">
								<Play class="h-4 w-4 text-direct-play" />
								<h2 class="font-heading text-xs font-semibold uppercase tracking-wider text-direct-play">
									Miru Play Summary
								</h2>
							</div>
							{#if data.playSummary.avgCompletionPercent != null}
								<Badge tone="direct-play" mono>
									{formatStatPercent(data.playSummary.avgCompletionPercent, 0)} AVG COMPLETION
								</Badge>
							{/if}
						</div>

						<div class="grid grid-cols-3 gap-2">
							<div class="rounded-lg border border-border bg-surface px-2.5 py-2">
								<span class="block text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
									Plays
								</span>
								<span class="font-mono text-sm font-bold text-fg-primary tabular-nums">
									{data.playSummary.totalPlays}
								</span>
							</div>
							<div class="rounded-lg border border-border bg-surface px-2.5 py-2">
								<span class="flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
									<Users class="h-2.5 w-2.5" /> Users
								</span>
								<span class="font-mono text-sm font-bold text-fg-primary tabular-nums">
									{data.playSummary.uniqueUsers}
								</span>
							</div>
							<div class="rounded-lg border border-border bg-surface px-2.5 py-2">
								<span class="block text-[10px] font-semibold uppercase tracking-wider text-fg-muted">
									Watch Time
								</span>
								<span class="font-mono text-sm font-bold text-fg-primary tabular-nums">
									{formatDurationSec(data.playSummary.totalWatchDurationSec)}
								</span>
							</div>
						</div>

						<div class="flex flex-wrap items-center gap-1.5 text-[11px]">
							<Badge tone="direct-play" mono>{data.playSummary.directPlayCount} Direct</Badge>
							<Badge tone="direct-stream" mono>{data.playSummary.directStreamCount} Remux</Badge>
							<Badge tone="hw-transcode" mono>{data.playSummary.transcodeCount} Transcode</Badge>
						</div>

						<div class="text-xs text-fg-secondary">
							{#if data.playSummary.lastWatchedAtMs}
								Last watched {formatRelativeTime(data.playSummary.lastWatchedAtMs)}
								{#if data.playSummary.lastWatchedUsername}
									by <span class="font-semibold text-fg-primary">{data.playSummary.lastWatchedUsername}</span>
								{/if}
							{:else}
								No playback history recorded for this item.
							{/if}
						</div>

						{#if data.seriesPlaySummary && data.seriesPlaySummary.totalPlays > 0 && data.mediaType !== 'movie' && data.mediaType !== 'show'}
							<div class="border-t border-border pt-2.5 text-[11px] text-fg-secondary">
								<span class="font-heading font-semibold text-fg-primary">Series Total:</span>
								{data.seriesPlaySummary.totalPlays} plays · {data.seriesPlaySummary.uniqueUsers} users ·
								{formatDurationSec(data.seriesPlaySummary.totalWatchDurationSec)} watched
							</div>
						{/if}
					</section>
				</div>
			</div>
		</div>

		<!-- Footer -->
		<footer class="flex items-center justify-between gap-3 border-t border-border bg-surface px-5 py-3.5 safe-pb">
			<a
				href={historyHref}
				onclick={() => mediaDetailsModal.close()}
				class="inline-flex h-9 items-center gap-1.5 rounded-lg border border-border bg-elevated px-3 font-heading text-xs font-semibold text-fg-primary transition-colors hover:border-border-strong hover:bg-subtle"
			>
				<History class="h-3.5 w-3.5 text-cyan-hi" />
				Filter in History
			</a>
			<Button variant="secondary" size="md" onclick={() => mediaDetailsModal.close()}>
				Close
			</Button>
		</footer>
	{/if}
</dialog>

<style>
	.media-details-dialog:modal {
		overscroll-behavior: contain;
	}

	.media-details-dialog::backdrop {
		background: rgb(0 0 0 / 0.65);
		backdrop-filter: blur(4px);
		overflow: hidden;
		overscroll-behavior: contain;
	}

	.media-details-dialog[open] {
		display: flex;
		flex-direction: column;
	}
</style>
