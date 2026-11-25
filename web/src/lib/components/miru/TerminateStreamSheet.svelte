<script lang="ts">
	import { OctagonX } from '@lucide/svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type { ActiveStreamDto, TerminateStreamResponse } from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import Sheet from '$components/ui/Sheet.svelte';
	import ProxiedImage from '$components/miru/ProxiedImage.svelte';
	import { formatMediaTitle, getDecisionMeta } from '$lib/utils';

	type Props = {
		stream: ActiveStreamDto | null;
		open: boolean;
		onOpenChange: (open: boolean) => void;
		onTerminated?: (result: TerminateStreamResponse, stream: ActiveStreamDto) => void;
	};

	const REASON_PRESETS: ReadonlyArray<{ label: string; text: string }> = [
		{
			label: '4K Transcode',
			text: '4K video transcoding is disabled on this server. Please select Direct Play or a 1080p version.'
		},
		{
			label: 'Paused Too Long',
			text: 'Your stream was terminated because it remained paused for too long.'
		},
		{
			label: 'Maintenance',
			text: 'The media server is going down briefly for maintenance. Please try again in a few minutes.'
		},
		{
			label: 'Bandwidth Limit',
			text: 'Your stream was stopped to protect shared server bandwidth.'
		}
	];

	let { stream, open = $bindable(), onOpenChange, onTerminated }: Props = $props();

	let reason = $state('');
	let submitting = $state(false);
	let error = $state<string | null>(null);
	let lastStreamId = $state<string | null>(null);

	$effect(() => {
		const nextId = stream ? `${stream.serverId}:${stream.sessionKey}:${stream.media.ratingKey}` : null;
		if (open && nextId !== lastStreamId) {
			reason = '';
			error = null;
			submitting = false;
			lastStreamId = nextId;
		} else if (!open) {
			error = null;
			submitting = false;
			lastStreamId = null;
		}
	});

	const titles = $derived(stream ? formatMediaTitle(stream.media) : null);
	const decision = $derived(
		stream ? getDecisionMeta(stream.technical.streamDecision, stream.technical.hwTranscode) : null
	);
	const displayUser = $derived(
		stream ? stream.user.displayName || stream.user.username || 'Unknown' : ''
	);
	const playerLabel = $derived(
		stream
			? [stream.player.name || stream.player.product, stream.player.platform]
					.filter(Boolean)
					.join(' · ')
			: ''
	);
	const stateLabel = $derived.by(() => {
		const raw = (stream?.state || 'playing').toLowerCase();
		if (raw === 'paused') return 'Paused';
		if (raw === 'buffering') return 'Buffering';
		return 'Playing';
	});

	async function handleSubmit(e: SubmitEvent) {
		e.preventDefault();
		if (!stream || submitting) return;
		const targetSessionId = stream.sessionId?.trim() || stream.sessionKey;
		if (!targetSessionId) return;

		submitting = true;
		error = null;
		try {
			const trimmedReason = reason.trim();
			const result = unwrap(
				await api.POST('/api/v1/streams/{sessionId}/terminate', {
					params: { path: { sessionId: targetSessionId } },
					body: { reason: trimmedReason.length > 0 ? trimmedReason : null }
				})
			);
			const terminatedStream = stream;
			onOpenChange(false);
			onTerminated?.(result, terminatedStream);
		} catch (err) {
			error = err instanceof MiruApiError ? err.detail : 'Failed to terminate stream';
		} finally {
			submitting = false;
		}
	}
</script>

<Sheet
	bind:open
	{onOpenChange}
	title="Terminate Active Stream"
	description="Stop playback on Plex and display an optional message to the viewer"
>
	{#if stream && titles && decision}
		<form onsubmit={handleSubmit} class="flex flex-col gap-4">
			{#if error}
				<div
					role="alert"
					class="rounded-xl border border-danger/50 bg-danger-soft px-3.5 py-2.5 text-xs text-danger"
				>
					{error}
				</div>
			{/if}

			<div class="flex flex-col gap-2.5 rounded-xl border border-border bg-elevated p-3.5">
				<div class="flex items-start justify-between gap-2">
					<div class="min-w-0">
						<p class="truncate font-heading text-sm font-semibold text-fg-primary">
							{titles.primary}
						</p>
						{#if titles.secondary}
							<p class="truncate text-xs text-fg-secondary">{titles.secondary}</p>
						{/if}
					</div>
					<Badge tone={stream.state.toLowerCase() === 'paused' ? 'neutral' : 'direct-play'} dot>
						{stateLabel}
					</Badge>
				</div>

				<div class="flex flex-wrap items-center justify-between gap-2 text-xs">
					<div class="flex min-w-0 items-center gap-2">
						<ProxiedImage
							src={stream.user.avatarUrl}
							alt={displayUser}
							fallbackLabel={displayUser}
							variant="avatar"
							class="h-5 w-5 text-[9px]"
						/>
						<span class="truncate font-heading font-semibold text-fg-primary">{displayUser}</span>
						{#if playerLabel}
							<span class="truncate text-fg-muted">· {playerLabel}</span>
						{/if}
					</div>
				</div>

				<div class="flex flex-wrap items-center gap-1.5">
					<Badge tone={decision.tone} dot>{decision.label}</Badge>
					{#if stream.technical.videoResolution}
						<Badge tone="neutral" mono>{stream.technical.videoResolution.toUpperCase()}</Badge>
					{/if}
					<Badge tone={stream.player.location?.toLowerCase() === 'wan' ? 'cyan' : 'neutral'} mono>
						{(stream.player.location ?? 'lan').toUpperCase()}
					</Badge>
				</div>
			</div>

			<div class="flex flex-col gap-2">
				<span class="font-heading text-xs font-semibold text-fg-primary">Quick-fill Presets</span>
				<div class="flex flex-wrap gap-1.5">
					{#each REASON_PRESETS as preset}
						<button
							type="button"
							onclick={() => (reason = preset.text)}
							class="cursor-pointer rounded-lg border border-border-strong bg-elevated px-2.5 py-1.5 font-heading text-[11px] font-medium text-fg-secondary transition-colors hover:border-cyan hover:text-fg-primary"
						>
							{preset.label}
						</button>
					{/each}
				</div>
			</div>

			<label class="flex flex-col gap-1.5">
				<div class="flex items-center justify-between gap-2">
					<span class="font-heading text-xs font-semibold text-fg-primary">Message to viewer</span>
					<span class="font-mono text-[10px] text-fg-muted tabular-nums">
						{reason.trim().length}/500
					</span>
				</div>
				<textarea
					rows={3}
					maxlength={500}
					bind:value={reason}
					placeholder="Optional message shown in the viewer's Plex client (leave blank for default)"
					class="rounded-xl border border-input-border bg-canvas px-3 py-2 text-sm text-fg-primary placeholder:text-fg-muted focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				></textarea>
				<span class="text-[11px] text-fg-muted">
					Leaving this blank sends &ldquo;Stream terminated by administrator&rdquo;.
				</span>
			</label>

			<div class="flex items-center gap-2 pt-1">
				<Button
					variant="outline"
					size="lg"
					disabled={submitting}
					onclick={() => onOpenChange(false)}
					class="flex-1"
				>
					Cancel
				</Button>
				<Button type="submit" variant="destructive" size="lg" disabled={submitting} class="flex-1">
					<OctagonX class="h-4 w-4" />
					{submitting ? 'Terminating…' : 'Terminate Stream'}
				</Button>
			</div>
		</form>
	{/if}
</Sheet>
