<script lang="ts">
	import { OctagonX } from 'lucide-svelte';
	import type { ActiveStreamDto } from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import ProxiedImage from './ProxiedImage.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
	import {
		cn,
		formatBandwidthKbps,
		formatClockMs,
		formatMediaTitle,
		getDecisionMeta
	} from '$lib/utils';

	type Props = {
		stream: ActiveStreamDto;
		class?: string;
		onTerminate?: (stream: ActiveStreamDto) => void;
		terminating?: boolean;
	};

	let { stream, class: className = '', onTerminate, terminating = false }: Props = $props();

	const titles = $derived(formatMediaTitle(stream.media));
	const decision = $derived(getDecisionMeta(stream.technical.streamDecision, stream.technical.hwTranscode));
	const stateLower = $derived((stream.state || 'playing').toLowerCase());
	const isPaused = $derived(stateLower === 'paused');
	const isBuffering = $derived(stateLower === 'buffering');
	const progressPct = $derived(Math.min(100, Math.max(0, stream.progressPercent ?? 0)));
	const bufferPct = $derived.by(() => {
		const tp = stream.technical.transcodeProgress;
		if (tp == null || tp <= progressPct) return null;
		return Math.min(100, Math.max(progressPct, tp));
	});

	const videoChain = $derived.by(() => {
		const srcRes = stream.technical.videoResolution?.toUpperCase() ?? '';
		const srcCodec = stream.technical.videoCodec?.toUpperCase() ?? '';
		const dstCodec = stream.technical.transcodeVideoCodec?.toUpperCase() ?? '';
		const left = [srcRes, srcCodec].filter(Boolean).join(' ');
		if (dstCodec && dstCodec !== srcCodec) {
			return `${left || 'Video'} → ${dstCodec}`;
		}
		return left || null;
	});

	const audioChain = $derived.by(() => {
		const src = stream.technical.audioCodec?.toUpperCase() ?? '';
		const dst = stream.technical.transcodeAudioCodec?.toUpperCase() ?? '';
		if (dst && dst !== src) return `${src || 'Audio'} → ${dst}`;
		return src || null;
	});

	const remainingMs = $derived(Math.max(0, (stream.media.durationMs ?? 0) - (stream.viewOffsetMs ?? 0)));
	const remainingMin = $derived(Math.ceil(remainingMs / 60_000));
	const locationUpper = $derived((stream.player.location ?? 'lan').toUpperCase());
	const displayUser = $derived(stream.user.displayName || stream.user.username || 'Unknown');
	const playerLabel = $derived(
		[stream.player.name || stream.player.product, stream.player.platform].filter(Boolean).join(' · ')
	);
</script>

<article
	class={cn(
		'flex flex-col gap-3 rounded-xl border border-border bg-surface p-4 shadow-xs transition-colors hover:border-border-strong dark:shadow-none',
		className
	)}
>
	<div class="flex items-start gap-3.5">
		<button
			type="button"
			onclick={() => void mediaDetailsModal.open(stream.media.ratingKey)}
			aria-label={`View media details for ${titles.primary}`}
			class="shrink-0 cursor-pointer rounded-lg transition-transform hover:scale-[1.02] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan"
		>
			<ProxiedImage
				src={stream.media.thumbUrl}
				alt={titles.primary}
				fallbackLabel={titles.primary}
				variant="poster"
				class="h-24 w-16 sm:h-[104px] sm:w-[70px]"
			/>
		</button>

		<div class="flex min-w-0 flex-1 flex-col gap-2">
			<div class="flex items-start justify-between gap-2">
				<div class="min-w-0">
					<button
						type="button"
						onclick={() => void mediaDetailsModal.open(stream.media.ratingKey)}
						class="block max-w-full cursor-pointer text-left hover:underline"
					>
						<h3 class="truncate font-heading text-sm font-semibold text-fg-primary sm:text-[15px]">
							{titles.primary}
						</h3>
					</button>
					<p class="truncate text-xs text-fg-secondary">{titles.secondary}</p>
				</div>
				{#if isPaused}
					<Badge tone="neutral" dot>Paused</Badge>
				{:else if isBuffering}
					<Badge tone="hw-transcode" dot pulse>Buffering</Badge>
				{:else}
					<Badge tone="direct-play" dot pulse>Playing</Badge>
				{/if}
			</div>

			<div class="flex flex-wrap items-center justify-between gap-2">
				<a
					href={`/users/${encodeURIComponent(stream.user.id)}`}
					class="flex min-w-0 items-center gap-2 hover:underline"
				>
					<ProxiedImage
						src={stream.user.avatarUrl}
						alt={displayUser}
						fallbackLabel={displayUser}
						variant="avatar"
						class="h-5 w-5 text-[9px]"
					/>
					<span class="truncate font-heading text-xs font-semibold text-fg-primary">
						{displayUser}
					</span>
					{#if playerLabel}
						<span class="hidden truncate text-xs text-fg-secondary sm:inline">· {playerLabel}</span>
					{/if}
				</a>

				<div class="flex items-center gap-1.5">
					<Badge tone={locationUpper === 'WAN' ? 'cyan' : 'neutral'} mono>
						{locationUpper}
					</Badge>
					{#if stream.technical.bandwidthKbps}
						<span class="font-mono text-xs font-semibold text-cyan-hi tabular-nums">
							{formatBandwidthKbps(stream.technical.bandwidthKbps)}
						</span>
					{/if}
				</div>
			</div>

			<div class="flex flex-wrap items-center gap-1.5">
				<Badge tone={decision.tone} dot>{decision.label}</Badge>
				{#if videoChain}
					<Badge tone="neutral" mono>{videoChain}</Badge>
				{/if}
				{#if audioChain}
					<Badge tone="neutral" mono class="hidden sm:inline-flex">{audioChain}</Badge>
				{/if}
				{#if stream.technical.throttled || stream.technical.transcodeSpeed}
					<Badge tone="cyan" mono class="hidden sm:inline-flex">
						{stream.technical.throttled ? 'THROTTLED' : 'SPEED'}
						{#if stream.technical.transcodeSpeed}
							· {stream.technical.transcodeSpeed.toFixed(1)}x
						{/if}
					</Badge>
				{/if}
			</div>
		</div>
	</div>

	<!-- Progress + Transcode Buffer Bar -->
	<div class="flex flex-col gap-1.5">
		<div class="relative h-1.5 w-full overflow-hidden rounded-full bg-subtle">
			{#if bufferPct !== null}
				<div
					class="absolute inset-y-0 left-0 rounded-full bg-cyan/45 transition-all duration-500"
					style:width={`${bufferPct}%`}
				></div>
			{/if}
			<div
				class={cn(
					'absolute inset-y-0 left-0 rounded-full transition-all duration-500',
					isPaused ? 'bg-fg-muted' : 'bg-primary'
				)}
				style:width={`${progressPct}%`}
			></div>
		</div>
		<div class="flex items-center justify-between font-mono text-[11px] tabular-nums">
			<span class="text-fg-secondary">
				{formatClockMs(stream.viewOffsetMs)} / {formatClockMs(stream.media.durationMs)}
			</span>
			<span class="text-fg-muted">
				{Math.round(progressPct)}%
				{#if remainingMin > 0}
					· {remainingMin}m left
				{/if}
			</span>
		</div>
	</div>

	{#if authStore.isAdmin && onTerminate}
		<div class="flex items-center justify-between gap-2 border-t border-border pt-2.5">
			<span class="truncate font-mono text-[10px] text-fg-muted">
				{stream.sessionId ? `Session ${stream.sessionId}` : `Key ${stream.sessionKey}`}
			</span>
			<Button
				variant="destructive"
				size="sm"
				disabled={terminating}
				onclick={() => onTerminate(stream)}
				title={`Terminate stream for ${displayUser}`}
			>
				<OctagonX class="h-3.5 w-3.5" />
				{terminating ? 'Terminating…' : 'Terminate'}
			</Button>
		</div>
	{/if}
</article>
