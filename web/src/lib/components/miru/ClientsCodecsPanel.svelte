<script lang="ts">
	import type {
		AnalyticsMetricPreference,
		ClientBreakdownItemDto,
		ClientStatsResponse
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import { formatDurationSec } from '$lib/utils';

	type Props = {
		clientStats: ClientStatsResponse | null;
		metric?: AnalyticsMetricPreference;
		loading?: boolean;
	};

	let { clientStats, metric = 'plays', loading = false }: Props = $props();

	type SectionGroup = {
		title: string;
		subtitle: string;
		items: ClientBreakdownItemDto[];
		accentClass: string;
		emptyLabel: string;
	};

	const groups = $derived<SectionGroup[]>([
		{
			title: 'Top Players & Apps',
			subtitle: 'Client product distribution',
			items: clientStats?.players ?? [],
			accentClass: 'bg-cyan',
			emptyLabel: 'No player telemetry in this range'
		},
		{
			title: 'Platforms & OS',
			subtitle: 'Operating system & device family',
			items: clientStats?.platforms ?? [],
			accentClass: 'bg-primary',
			emptyLabel: 'No platform telemetry in this range'
		},
		{
			title: 'Video & Audio Codecs',
			subtitle: 'Source stream codec frequency',
			items: [
				...(clientStats?.videoCodecs ?? []).slice(0, 3).map((c) => ({ ...c, name: `Video · ${c.name}` })),
				...(clientStats?.audioCodecs ?? []).slice(0, 3).map((c) => ({ ...c, name: `Audio · ${c.name}` }))
			],
			accentClass: 'bg-direct-play',
			emptyLabel: 'No codec telemetry in this range'
		},
		{
			title: 'Transcode & Remux Reasons',
			subtitle: clientStats
				? `${clientStats.transcodeOrRemuxPlays} non-Direct-Play sessions`
				: 'Stream conversion breakdown',
			items: clientStats?.transcodeReasons ?? [],
			accentClass: 'bg-hw-transcode',
			emptyLabel: '100% Direct Play — no transcodes or remuxes'
		}
	]);
</script>

<div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
	{#each groups as group (group.title)}
		<div class="flex flex-col justify-between gap-3 rounded-xl border border-border bg-surface p-4">
			<div class="flex items-start justify-between gap-2">
				<div>
					<h3 class="font-heading text-sm font-semibold text-fg-primary">{group.title}</h3>
					<p class="text-[11px] text-fg-muted">{group.subtitle}</p>
				</div>
				<Badge tone="neutral" mono>{group.items.length}</Badge>
			</div>

			{#if loading && !clientStats}
				<p class="py-6 text-center font-mono text-xs text-fg-muted">Loading breakdown…</p>
			{:else if group.items.length === 0}
				<p class="rounded-lg bg-elevated/50 px-3 py-6 text-center text-xs text-fg-muted">
					{group.emptyLabel}
				</p>
			{:else}
				<div class="flex flex-col gap-2.5">
					{#each group.items.slice(0, 5) as item, idx (item.name)}
						<div class="flex flex-col gap-1">
							<div class="flex items-center justify-between gap-2 text-xs">
								<span class="truncate font-medium text-fg-primary" title={item.name}>
									{item.name}
								</span>
								<span class="shrink-0 font-mono text-[11px] text-fg-secondary tabular-nums">
									{metric === 'duration' ? formatDurationSec(item.watchDurationSec) : item.playCount} · {item.percent.toFixed(1)}%
								</span>
							</div>
							<div class="h-1.5 w-full overflow-hidden rounded-full bg-subtle">
								<div
									class={`h-full rounded-full ${idx === 0 ? group.accentClass : 'bg-cyan/75'}`}
									style:width={`${Math.max(4, Math.min(100, item.percent))}%`}
									title={`${item.name}: ${item.playCount} plays (${formatDurationSec(item.watchDurationSec)})`}
								></div>
							</div>
						</div>
					{/each}
				</div>
			{/if}
		</div>
	{/each}
</div>
