<script lang="ts">
	import type { HistoryDetailResponse } from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import ProxiedImage from './ProxiedImage.svelte';
	import ServerBadge from './ServerBadge.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { serversStore } from '$stores/servers.svelte';
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
		onopenMedia: (ratingKey: string, serverId?: string | null) => void;
	};

	let { detail, onopenMedia }: Props = $props();

	const titles = $derived(formatMediaTitle(detail.media));
	const decision = $derived(getDecisionMeta(detail.streamTech.streamDecision, detail.streamTech.hwTranscode));
	const pct = $derived(Math.min(100, Math.max(0, detail.completionPercent)));
	const userLabel = $derived(detail.displayName || detail.username || detail.userId);

	function formatCodecFlow(source: string | null | undefined, target: string | null | undefined): string | null {
		const src = source?.trim().toUpperCase() || null;
		const dst = target?.trim().toUpperCase() || null;
		if (src && dst && src !== dst) return `${src} → ${dst}`;
		if (dst && !src) return `→ ${dst}`;
		return src;
	}

	const videoSummary = $derived(
		[
			detail.streamTech.videoResolution?.toUpperCase(),
			formatCodecFlow(detail.streamTech.videoCodec, detail.streamTech.transcodeVideoCodec),
			detail.streamTech.videoDecision
		]
			.filter(Boolean)
			.join(' · ') || '—'
	);

	const audioSummary = $derived(
		[
			formatCodecFlow(detail.streamTech.audioCodec, detail.streamTech.transcodeAudioCodec),
			detail.streamTech.audioDecision
		]
			.filter(Boolean)
			.join(' · ') || '—'
	);

	const containerSummary = $derived(
		formatCodecFlow(detail.streamTech.container, detail.streamTech.transcodeContainer)
	);

	const subtitleSummary = $derived.by(() => {
		const raw = detail.streamTech.subtitleDecision?.trim().toLowerCase();
		if (!raw) return null;
		if (raw === 'burn') return 'Burn-in';
		if (raw === 'transcode') return 'Transcode';
		if (raw === 'copy') return 'Copy';
		return raw;
	});
</script>

<div class="flex flex-col gap-4">
	<!-- Media Hero -->
	<div class="flex items-start gap-3.5">
		<button
			type="button"
			onclick={() => onopenMedia(detail.media.ratingKey, detail.serverId)}
			aria-label={`View media details for ${titles.primary}`}
			class="shrink-0 cursor-pointer rounded-lg transition-transform hover:scale-[1.02] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan"
		>
			<ProxiedImage
				src={detail.media.thumbUrl}
				serverId={detail.serverId}
				alt={titles.primary}
				fallbackLabel={titles.primary}
				variant="poster"
				class="h-24 w-16"
			/>
		</button>
		<div class="flex min-w-0 flex-1 flex-col gap-1.5">
			<button
				type="button"
				onclick={() => onopenMedia(detail.media.ratingKey, detail.serverId)}
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
				{#if serversStore.hasMultipleServers || serversStore.isDisconnected(detail.serverId)}
					<ServerBadge
						serverId={detail.serverId}
						serverType={detail.serverType}
						serverName={detail.serverName}
					/>
				{/if}
				{#if userLabel}
					<a href={`/users/${encodeURIComponent(detail.userId)}`}>
						<Badge tone="primary">{userLabel}</Badge>
					</a>
				{/if}
				<button
					type="button"
					onclick={() => onopenMedia(detail.media.ratingKey, detail.serverId)}
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
					{videoSummary}
				</dd>
			</div>
			<div class="flex items-center justify-between gap-2">
				<dt class="text-fg-muted">Audio</dt>
				<dd class="font-mono text-[11px] text-fg-primary">
					{audioSummary}
				</dd>
			</div>
			{#if containerSummary}
				<div class="flex items-center justify-between gap-2">
					<dt class="text-fg-muted">Container</dt>
					<dd class="font-mono text-[11px] text-fg-primary">{containerSummary}</dd>
				</div>
			{/if}
			{#if subtitleSummary}
				<div class="flex items-center justify-between gap-2">
					<dt class="text-fg-muted">Subtitles</dt>
					<dd class="font-mono text-[11px] text-fg-primary">{subtitleSummary}</dd>
				</div>
			{/if}
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
