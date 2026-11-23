<script lang="ts">
	import type { LibraryStatsResponse, RecentMediaItemResponse } from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import BarChart, { type BarPoint } from '$components/miru/BarChart.svelte';
	import ProxiedImage from '$components/miru/ProxiedImage.svelte';
	import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
	import { formatBytes, formatDurationSec, formatRelativeTime } from '$lib/utils';

	type Props = {
		stats: LibraryStatsResponse;
		recentItems: RecentMediaItemResponse[];
		loadingSection?: boolean;
	};

	let { stats, recentItems, loadingSection = false }: Props = $props();

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

	const resDist = $derived(mapToDist(stats.byResolution));
	const videoDist = $derived(mapToDist(stats.byVideoCodec));
	const audioDist = $derived(mapToDist(stats.byAudioCodec));

	const snapshotBars = $derived.by<BarPoint[]>(() => {
		const snaps = [...stats.snapshots].reverse().slice(-8);
		return snaps.map((s) => ({
			label: new Date(s.capturedAtMs).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }),
			value: s.itemCount,
			tooltip: `${s.itemCount.toLocaleString()} items · ${formatBytes(s.totalSizeBytes)}`
		}));
	});
</script>

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
