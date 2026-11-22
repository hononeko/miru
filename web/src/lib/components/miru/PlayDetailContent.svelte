<script lang="ts">
	import type { HistoryDetailResponse } from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import ProxiedImage from './ProxiedImage.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
	import {
		formatBandwidthKbps,
		formatClockMs,
		formatDateTime,
		formatDurationSec,
		formatMediaTitle,
		getDecisionMeta
	} from '$lib/utils';

	type Props = {
		detail: HistoryDetailResponse;
	};

	let { detail }: Props = $props();

	const titles = $derived(formatMediaTitle(detail.media));
	const decision = $derived(getDecisionMeta(detail.streamTech.streamDecision, detail.streamTech.hwTranscode));
	const pct = $derived(Math.min(100, Math.max(0, detail.completionPercent)));
</script>

<div class="flex flex-col gap-4">
	<!-- Media Hero -->
	<div class="flex items-start gap-3.5">
		<button
			type="button"
			onclick={() => void mediaDetailsModal.open(detail.media.ratingKey)}
			aria-label={`View media details for ${titles.primary}`}
			class="shrink-0 cursor-pointer rounded-lg transition-transform hover:scale-[1.02] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan"
		>
			<ProxiedImage
				src={detail.media.thumbUrl}
				alt={titles.primary}
				fallbackLabel={titles.primary}
				variant="poster"
				class="h-24 w-16"
			/>
		</button>
		<div class="flex min-w-0 flex-1 flex-col gap-1.5">
			<button
				type="button"
				onclick={() => void mediaDetailsModal.open(detail.media.ratingKey)}
				class="cursor-pointer text-left hover:underline"
			>
				<h3 class="font-display text-xl font-bold leading-tight text-fg-primary">
					{titles.primary}
				</h3>
			</button>
			<p class="text-xs text-fg-secondary">{titles.secondary}</p>
			<div class="mt-0.5 flex flex-wrap items-center gap-1.5">
				<Badge tone={detail.isWatched ? 'direct-play' : 'neutral'} dot>
					{detail.isWatched ? `Watched · ${Math.round(pct)}%` : `${Math.round(pct)}%`}
				</Badge>
				{#if detail.username}
					<a href={`/users/${encodeURIComponent(detail.userId)}`}>
						<Badge tone="primary">{detail.username}</Badge>
					</a>
				{/if}
				<button
					type="button"
					onclick={() => void mediaDetailsModal.open(detail.media.ratingKey)}
					class="cursor-pointer font-mono text-[11px] font-semibold text-cyan-hi hover:underline"
				>
					Media info →
				</button>
			</div>
		</div>
	</div>

	<!-- Completion bar -->
	<div class="flex flex-col gap-2 rounded-xl border border-border bg-elevated p-3">
		<div class="flex items-center justify-between text-xs">
			<span class="font-heading font-semibold text-fg-secondary">Completion</span>
			<span class="font-mono font-semibold text-direct-play tabular-nums">
				{formatClockMs(detail.viewOffsetMs)} / {formatClockMs(detail.media.durationMs)} ({pct.toFixed(1)}%)
			</span>
		</div>
		<div class="h-1.5 w-full overflow-hidden rounded-full bg-subtle">
			<div
				class="h-full rounded-full bg-direct-play"
				style:width={`${pct}%`}
			></div>
		</div>
	</div>

	<!-- Stream & Transcode -->
	<div class="flex flex-col gap-2 rounded-xl border border-border bg-elevated p-3.5">
		<h4 class="font-heading text-[10px] font-semibold uppercase tracking-wider text-cyan-hi">
			Stream & Transcode
		</h4>
		<dl class="flex flex-col gap-1.5 text-xs">
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Decision</dt>
				<dd><Badge tone={decision.tone} dot>{decision.label}</Badge></dd>
			</div>
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Video</dt>
				<dd class="font-mono text-[11px] text-fg-primary">
					{[detail.streamTech.videoResolution?.toUpperCase(), detail.streamTech.videoCodec?.toUpperCase(), detail.streamTech.videoDecision].filter(Boolean).join(' · ') || '—'}
				</dd>
			</div>
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Audio</dt>
				<dd class="font-mono text-[11px] text-fg-primary">
					{[detail.streamTech.audioCodec?.toUpperCase(), detail.streamTech.audioDecision].filter(Boolean).join(' · ') || '—'}
				</dd>
			</div>
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Hardware Accel</dt>
				<dd class="font-mono text-[11px] text-cyan-hi">
					{detail.streamTech.hwTranscode ? 'Enabled (HW)' : 'No'}
				</dd>
			</div>
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Bandwidth</dt>
				<dd class="font-mono text-[11px] text-fg-primary tabular-nums">
					{detail.streamTech.bandwidthKbps ? formatBandwidthKbps(detail.streamTech.bandwidthKbps) : '—'}
				</dd>
			</div>
		</dl>
	</div>

	<!-- Player & Network -->
	<div class="flex flex-col gap-2 rounded-xl border border-border bg-elevated p-3.5">
		<h4 class="font-heading text-[10px] font-semibold uppercase tracking-wider text-cyan-hi">
			Player & Network
		</h4>
		<dl class="flex flex-col gap-1.5 text-xs">
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Player</dt>
				<dd class="font-mono text-[11px] text-fg-primary">{detail.player.playerName || '—'}</dd>
			</div>
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Platform / App</dt>
				<dd class="font-mono text-[11px] text-fg-secondary">
					{[detail.player.playerPlatform, detail.player.playerProduct].filter(Boolean).join(' · ') || '—'}
				</dd>
			</div>
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Network</dt>
				<dd class="font-mono text-[11px] text-direct-play">
					{[detail.player.location?.toUpperCase(), detail.player.ipAddress].filter(Boolean).join(' · ') || '—'}
				</dd>
			</div>
		</dl>
	</div>

	<!-- Session Timing -->
	<div class="flex flex-col gap-2 rounded-xl border border-border bg-elevated p-3.5">
		<h4 class="font-heading text-[10px] font-semibold uppercase tracking-wider text-cyan-hi">
			Session Timing
		</h4>
		<dl class="flex flex-col gap-1.5 text-xs">
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Started</dt>
				<dd class="font-mono text-[11px] text-fg-secondary">{formatDateTime(detail.startedAtMs, preferencesStore.prefs)}</dd>
			</div>
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Stopped</dt>
				<dd class="font-mono text-[11px] text-fg-secondary">{formatDateTime(detail.stoppedAtMs, preferencesStore.prefs)}</dd>
			</div>
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Watched / Paused</dt>
				<dd class="font-mono text-[11px] text-fg-primary tabular-nums">
					{formatDurationSec(detail.watchedDurationSec)} / {formatDurationSec(detail.pausedDurationSec)}
				</dd>
			</div>
		</dl>
	</div>
</div>
