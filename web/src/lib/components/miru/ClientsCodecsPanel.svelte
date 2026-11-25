<script lang="ts">
	import type {
		AnalyticsMetricPreference,
		ClientBreakdownItemDto,
		ClientStatsResponse
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import SegmentedBar, { type ShareSegment } from './SegmentedBar.svelte';
	import { formatDurationSec, formatStatPercent, statBarWidth } from '$lib/utils';

	type Props = {
		clientStats: ClientStatsResponse | null;
		metric?: AnalyticsMetricPreference;
		loading?: boolean;
	};

	let { clientStats, metric = 'plays', loading = false }: Props = $props();

	/** A labeled run of rows inside a card; percentages are relative to the list they sit in. */
	type ItemList = {
		label?: string;
		items: ClientBreakdownItemDto[];
	};

	type SectionGroup = {
		title: string;
		subtitle: string;
		lists: ItemList[];
		total: number;
		/** Draws each list as one stacked bar instead of a bar per row. */
		stacked?: boolean;
		accentClass?: string;
		emptyLabel: string;
	};

	const MAX_ROWS = 5;
	const CODEC_COLORS = ['bg-chart-1', 'bg-chart-2', 'bg-chart-3', 'bg-chart-4'];
	// Gray reads as "the rest" rather than as one more codec.
	const REMAINING_COLOR = 'bg-fg-muted';

	function singleList(items: ClientBreakdownItemDto[]): Pick<SectionGroup, 'lists' | 'total'> {
		return { lists: [{ items: items.slice(0, MAX_ROWS) }], total: items.length };
	}

	function itemTitle(item: ClientBreakdownItemDto, name = item.name): string {
		return `${name}: ${item.playCount} plays (${formatDurationSec(item.watchDurationSec)})`;
	}

	function codecSegments(items: ClientBreakdownItemDto[]): ShareSegment[] {
		const segments = items.slice(0, CODEC_COLORS.length).map((item, idx) => ({
			name: item.name,
			percent: item.percent,
			colorClass: CODEC_COLORS[idx],
			title: itemTitle(item)
		}));
		const remaining = items.slice(CODEC_COLORS.length);
		if (remaining.length === 0) return segments;

		const sum = {
			name: remaining.length === 1 ? remaining[0].name : 'Other',
			playCount: remaining.reduce((total, item) => total + item.playCount, 0),
			watchDurationSec: remaining.reduce((total, item) => total + item.watchDurationSec, 0),
			percent: remaining.reduce((total, item) => total + item.percent, 0)
		};
		const names = remaining.map((item) => item.name).join(', ');
		return [
			...segments,
			{ name: sum.name, percent: sum.percent, colorClass: REMAINING_COLOR, title: itemTitle(sum, names) }
		];
	}

	const groups = $derived.by<SectionGroup[]>(() => {
		const videoCodecs = clientStats?.videoCodecs ?? [];
		const audioCodecs = clientStats?.audioCodecs ?? [];
		return [
			{
				title: 'Top Players & Apps',
				subtitle: 'Client product distribution',
				...singleList(clientStats?.players ?? []),
				accentClass: 'bg-cyan',
				emptyLabel: 'No player telemetry in this range'
			},
			{
				title: 'Platforms & OS',
				subtitle: 'Operating system & device family',
				...singleList(clientStats?.platforms ?? []),
				accentClass: 'bg-primary',
				emptyLabel: 'No platform telemetry in this range'
			},
			{
				title: 'Video & Audio Codecs',
				subtitle: 'Source stream codec frequency',
				lists: [
					{ label: 'Video', items: videoCodecs },
					{ label: 'Audio', items: audioCodecs }
				].filter((list) => list.items.length > 0),
				total: videoCodecs.length + audioCodecs.length,
				stacked: true,
				emptyLabel: 'No codec telemetry in this range'
			},
			{
				title: 'Transcode & Remux Reasons',
				subtitle: clientStats
					? `${clientStats.transcodeOrRemuxPlays} non-Direct-Play sessions`
					: 'Stream conversion breakdown',
				...singleList(clientStats?.transcodeReasons ?? []),
				accentClass: 'bg-hw-transcode',
				emptyLabel: '100% Direct Play — no transcodes or remuxes'
			}
		];
	});
</script>

<div class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
	{#each groups as group (group.title)}
		<div class="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
			<div class="flex items-start justify-between gap-2">
				<div>
					<h3 class="font-heading text-sm font-semibold text-fg-primary">{group.title}</h3>
					<p class="text-[11px] text-fg-muted">{group.subtitle}</p>
				</div>
				<Badge tone="neutral" mono>{group.total}</Badge>
			</div>

			{#if loading && !clientStats}
				<p class="py-6 text-center font-mono text-xs text-fg-muted">Loading breakdown…</p>
			{:else if group.lists.every((list) => list.items.length === 0)}
				<p class="rounded-lg bg-elevated/50 px-3 py-6 text-center text-xs text-fg-muted">
					{group.emptyLabel}
				</p>
			{:else}
				<div class="flex flex-col gap-3">
					{#each group.lists as list, listIdx (list.label ?? listIdx)}
						<div
							class={listIdx > 0
								? 'flex flex-col gap-2.5 border-t border-border pt-3'
								: 'flex flex-col gap-2.5'}
							role={list.label ? 'group' : undefined}
							aria-label={list.label}
						>
							{#if list.label}
								<p class="font-mono text-[10px] font-semibold tracking-wider text-fg-muted uppercase">
									{list.label}
								</p>
							{/if}
							{#if group.stacked}
								<SegmentedBar segments={codecSegments(list.items)} />
							{:else}
								{#each list.items as item, idx (item.name)}
									<div class="flex flex-col gap-1">
										<div class="flex items-center justify-between gap-2 text-xs">
											<span class="truncate font-medium text-fg-primary" title={item.name}>
												{item.name}
											</span>
											<span class="shrink-0 font-mono text-[11px] text-fg-secondary tabular-nums">
												{metric === 'duration' ? formatDurationSec(item.watchDurationSec) : item.playCount} · {formatStatPercent(item.percent)}
											</span>
										</div>
										<div class="h-1.5 w-full overflow-hidden rounded-full bg-subtle">
											<div
												class={`h-full rounded-full transition-[width] duration-500 ease-[var(--ease-out-quart)] ${idx === 0 ? group.accentClass : 'bg-cyan/75'} ${item.percent > 0 ? 'min-w-0.5' : ''}`}
												style:width={statBarWidth(item.percent)}
												title={itemTitle(item)}
											></div>
										</div>
									</div>
								{/each}
							{/if}
						</div>
					{/each}
				</div>
			{/if}
		</div>
	{/each}
</div>
