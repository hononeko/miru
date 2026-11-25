<script lang="ts">
	import { onMount } from 'svelte';
	import {
		AlertTriangle,
		CheckCircle2,
		ChevronDown,
		KeyRound,
		Lock,
		Pencil,
		Plus,
		Power,
		RefreshCw,
		Send,
		Trash2,
		Webhook
	} from '@lucide/svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type {
		WebhookDeliveryDto,
		WebhookDeliveryStatus,
		WebhookEventType,
		WebhookTargetDto
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import Sheet from '$components/ui/Sheet.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { formatDateTime, formatFullUtc } from '$lib/utils';

	type EventMeta = {
		key: WebhookEventType;
		label: string;
		description: string;
	};

	const WEBHOOK_EVENTS: ReadonlyArray<EventMeta> = [
		{
			key: 'transcode.started',
			label: 'Transcode Started',
			description: 'A stream begins or transitions into video transcoding'
		},
		{
			key: 'stream.concurrent_limit',
			label: 'Concurrent Stream Limit',
			description: 'A user exceeds the configured simultaneous stream threshold'
		},
		{
			key: 'stream.buffering_spike',
			label: 'Buffering Spike',
			description: 'A session buffers repeatedly inside the sliding window'
		},
		{
			key: 'stream.terminated',
			label: 'Stream Terminated',
			description: 'An admin or automated guardrail terminates a live stream'
		},
		{
			key: 'server.unreachable',
			label: 'Server Unreachable',
			description: 'Plex fails consecutive health/reconciliation checks'
		},
		{
			key: 'server.recovered',
			label: 'Server Recovered',
			description: 'Plex reachability returns after an outage'
		}
	];

	const EVENT_LABELS: Record<string, string> = {
		'transcode.started': 'transcode.started',
		'stream.concurrent_limit': 'stream.concurrent_limit',
		'stream.buffering_spike': 'stream.buffering_spike',
		'stream.terminated': 'stream.terminated',
		'server.unreachable': 'server.unreachable',
		'server.recovered': 'server.recovered',
		'webhook.test': 'webhook.test'
	};

	const DELIVERY_PAGE_SIZE = 20;

	let targets = $state<WebhookTargetDto[]>([]);
	let encryptionKeyConfigured = $state(true);
	let loadingTargets = $state(true);
	let panelError = $state<string | null>(null);

	let deliveries = $state<WebhookDeliveryDto[]>([]);
	let deliveryTotalCount = $state(0);
	let deliveryStatusFilter = $state<'ALL' | WebhookDeliveryStatus>('ALL');
	let deliveryTargetFilter = $state<string>('ALL');
	let deliveryOffset = $state(0);
	let loadingDeliveries = $state(false);
	let expandedErrorIds = $state<Record<string, boolean>>({});

	// Per-target transient states
	let testingTargetId = $state<string | null>(null);
	let togglingTargetId = $state<string | null>(null);
	let confirmDeleteTargetId = $state<string | null>(null);
	let deletingTargetId = $state<string | null>(null);
	let testResultsByTargetId = $state<Record<string, WebhookDeliveryDto>>({});

	// Create / Edit Sheet state
	let sheetOpen = $state(false);
	let editingTarget = $state<WebhookTargetDto | null>(null);
	let formName = $state('');
	let formUrl = $state('');
	let formOriginalRedactedUrl = $state('');
	let formEnabled = $state(true);
	let formEvents = $state<WebhookEventType[]>(['transcode.started', 'stream.terminated']);
	let formCooldownSeconds = $state(60);
	let formConcurrentThreshold = $state(3);
	let formBufferingThreshold = $state(3);
	let formBufferingWindowSeconds = $state(120);
	let formTranscode4kOnly = $state(false);
	let formSigningSecret = $state('');
	let formClearSigningSecret = $state(false);
	let formAuthHeaderName = $state('');
	let formAuthHeaderValue = $state('');
	let formClearAuthHeader = $state(false);
	let formSubmitting = $state(false);
	let formError = $state<string | null>(null);

	async function loadTargets() {
		loadingTargets = true;
		panelError = null;
		try {
			const res = unwrap(await api.GET('/api/v1/admin/webhooks'));
			targets = res.targets;
			encryptionKeyConfigured = res.encryptionKeyConfigured;
		} catch (e) {
			panelError = e instanceof MiruApiError ? e.detail : 'Failed to load outbound webhooks';
		} finally {
			loadingTargets = false;
		}
	}

	async function loadDeliveries(
		statusFilter: 'ALL' | WebhookDeliveryStatus = deliveryStatusFilter,
		targetFilter: string = deliveryTargetFilter,
		offset: number = deliveryOffset
	) {
		deliveryStatusFilter = statusFilter;
		deliveryTargetFilter = targetFilter;
		deliveryOffset = offset;
		loadingDeliveries = true;
		try {
			const data = unwrap(
				await api.GET('/api/v1/admin/webhooks/deliveries', {
					params: {
						query: {
							status: statusFilter === 'ALL' ? undefined : statusFilter,
							targetId: targetFilter === 'ALL' ? undefined : targetFilter,
							limit: DELIVERY_PAGE_SIZE,
							offset
						}
					}
				})
			);
			deliveries = data.items;
			deliveryTotalCount = data.totalCount;
		} catch (e) {
			panelError = e instanceof MiruApiError ? e.detail : 'Failed to load webhook deliveries';
		} finally {
			loadingDeliveries = false;
		}
	}

	async function refreshAll() {
		await Promise.all([loadTargets(), loadDeliveries(deliveryStatusFilter, deliveryTargetFilter, 0)]);
	}

	onMount(() => {
		void refreshAll();
	});

	function openCreateSheet() {
		editingTarget = null;
		formName = '';
		formUrl = '';
		formOriginalRedactedUrl = '';
		formEnabled = true;
		formEvents = ['transcode.started', 'stream.terminated'];
		formCooldownSeconds = 60;
		formConcurrentThreshold = 3;
		formBufferingThreshold = 3;
		formBufferingWindowSeconds = 120;
		formTranscode4kOnly = false;
		formSigningSecret = '';
		formClearSigningSecret = false;
		formAuthHeaderName = '';
		formAuthHeaderValue = '';
		formClearAuthHeader = false;
		formError = null;
		sheetOpen = true;
	}

	function openEditSheet(target: WebhookTargetDto) {
		editingTarget = target;
		formName = target.name;
		formUrl = target.url;
		formOriginalRedactedUrl = target.url;
		formEnabled = target.enabled;
		formEvents = [...target.subscribedEvents];
		formCooldownSeconds = target.cooldownSeconds;
		formConcurrentThreshold = target.concurrentStreamThreshold;
		formBufferingThreshold = target.bufferingSpikeThreshold;
		formBufferingWindowSeconds = target.bufferingWindowSeconds;
		formTranscode4kOnly = target.transcode4kOnly;
		formSigningSecret = '';
		formClearSigningSecret = false;
		formAuthHeaderName = target.authHeaderName ?? '';
		formAuthHeaderValue = '';
		formClearAuthHeader = false;
		formError = null;
		sheetOpen = true;
	}

	function toggleEvent(eventKey: WebhookEventType) {
		if (formEvents.includes(eventKey)) {
			formEvents = formEvents.filter((item) => item !== eventKey);
		} else {
			formEvents = [...formEvents, eventKey];
		}
	}

	async function handleSaveTarget(e: SubmitEvent) {
		e.preventDefault();
		if (formSubmitting) return;
		formError = null;

		if (formEvents.length === 0) {
			formError = 'Select at least one webhook event to subscribe to.';
			return;
		}

		formSubmitting = true;
		try {
			const trimmedName = formName.trim();
			const trimmedUrl = formUrl.trim();
			const trimmedHeaderName = formAuthHeaderName.trim();

			if (editingTarget) {
				const urlChanged = trimmedUrl !== formOriginalRedactedUrl;
				const headerNameChanged = trimmedHeaderName !== (editingTarget.authHeaderName ?? '');
				const saved = unwrap(
					await api.PATCH('/api/v1/admin/webhooks/{id}', {
						params: { path: { id: editingTarget.id } },
						body: {
							name: trimmedName,
							url: urlChanged ? trimmedUrl : null,
							enabled: formEnabled,
							subscribedEvents: formEvents,
							cooldownSeconds: Number(formCooldownSeconds),
							concurrentStreamThreshold: Number(formConcurrentThreshold),
							bufferingSpikeThreshold: Number(formBufferingThreshold),
							bufferingWindowSeconds: Number(formBufferingWindowSeconds),
							transcode4kOnly: formTranscode4kOnly,
							signingSecret:
								!formClearSigningSecret && formSigningSecret.length > 0 ? formSigningSecret : null,
							clearSigningSecret: formClearSigningSecret,
							authHeaderName:
								!formClearAuthHeader && (headerNameChanged || formAuthHeaderValue.length > 0) && trimmedHeaderName
									? trimmedHeaderName
									: null,
							authHeaderValue:
								!formClearAuthHeader && formAuthHeaderValue.length > 0 ? formAuthHeaderValue : null,
							clearAuthHeader: formClearAuthHeader
						}
					})
				);
				targets = targets.map((t) => (t.id === saved.id ? saved : t));
			} else {
				const created = unwrap(
					await api.POST('/api/v1/admin/webhooks', {
						body: {
							name: trimmedName,
							url: trimmedUrl,
							enabled: formEnabled,
							subscribedEvents: formEvents,
							cooldownSeconds: Number(formCooldownSeconds),
							concurrentStreamThreshold: Number(formConcurrentThreshold),
							bufferingSpikeThreshold: Number(formBufferingThreshold),
							bufferingWindowSeconds: Number(formBufferingWindowSeconds),
							transcode4kOnly: formTranscode4kOnly,
							signingSecret: formSigningSecret.length > 0 ? formSigningSecret : null,
							authHeaderName: trimmedHeaderName.length > 0 ? trimmedHeaderName : null,
							authHeaderValue: formAuthHeaderValue.length > 0 ? formAuthHeaderValue : null
						}
					})
				);
				targets = [...targets, created];
			}
			sheetOpen = false;
		} catch (err) {
			formError = err instanceof MiruApiError ? err.detail : 'Failed to save webhook target';
		} finally {
			formSubmitting = false;
		}
	}

	async function handleToggleEnabled(target: WebhookTargetDto) {
		if (togglingTargetId === target.id) return;
		togglingTargetId = target.id;
		panelError = null;
		try {
			const updated = unwrap(
				await api.PATCH('/api/v1/admin/webhooks/{id}', {
					params: { path: { id: target.id } },
					body: { enabled: !target.enabled }
				})
			);
			targets = targets.map((t) => (t.id === updated.id ? updated : t));
		} catch (err) {
			panelError = err instanceof MiruApiError ? err.detail : 'Failed to toggle webhook target';
		} finally {
			togglingTargetId = null;
		}
	}

	async function handleSendTest(target: WebhookTargetDto) {
		if (testingTargetId === target.id) return;
		testingTargetId = target.id;
		panelError = null;
		try {
			const delivery = unwrap(
				await api.POST('/api/v1/admin/webhooks/{id}/test', {
					params: { path: { id: target.id } }
				})
			);
			testResultsByTargetId = {
				...testResultsByTargetId,
				[target.id]: delivery
			};
			await loadDeliveries(deliveryStatusFilter, deliveryTargetFilter, 0);
		} catch (err) {
			panelError = err instanceof MiruApiError ? err.detail : 'Failed to dispatch test webhook';
		} finally {
			testingTargetId = null;
		}
	}

	async function handleDeleteTarget(id: string) {
		if (deletingTargetId === id) return;
		deletingTargetId = id;
		panelError = null;
		try {
			const res = await api.DELETE('/api/v1/admin/webhooks/{id}', {
				params: { path: { id } }
			});
			if (res.error) throw new MiruApiError(res.response.status, res.error);
			targets = targets.filter((t) => t.id !== id);
			confirmDeleteTargetId = null;
			const nextFilter = deliveryTargetFilter === id ? 'ALL' : deliveryTargetFilter;
			await loadDeliveries(deliveryStatusFilter, nextFilter, 0);
		} catch (err) {
			panelError = err instanceof MiruApiError ? err.detail : 'Failed to delete webhook target';
		} finally {
			deletingTargetId = null;
		}
	}

	function deliveryStatusTone(status: WebhookDeliveryStatus): 'direct-play' | 'warning' | 'danger' {
		switch (status) {
			case 'DELIVERED':
				return 'direct-play';
			case 'RETRYING':
				return 'warning';
			case 'FAILED':
				return 'danger';
		}
	}

	function toggleErrorDetail(id: string) {
		expandedErrorIds = {
			...expandedErrorIds,
			[id]: !expandedErrorIds[id]
		};
	}

	const canPrevPage = $derived(deliveryOffset > 0);
	const canNextPage = $derived(deliveryOffset + DELIVERY_PAGE_SIZE < deliveryTotalCount);
</script>

<section class="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
	<div class="flex flex-wrap items-center justify-between gap-2">
		<div class="flex items-center gap-2">
			<Webhook class="h-4 w-4 text-primary-text" />
			<div>
				<h2 class="font-heading text-sm font-semibold text-fg-primary">
					Outbound Webhooks &amp; Delivery Log
				</h2>
				<p class="mt-0.5 text-[11px] text-fg-muted">
					Push signed JSON events for transcodes, stream caps, buffering spikes, terminations, and Plex outages
				</p>
			</div>
		</div>
		<Button variant="primary" size="sm" onclick={openCreateSheet}>
			<Plus class="h-3.5 w-3.5" />
			Add Webhook
		</Button>
	</div>

	{#if panelError}
		<div role="alert" class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
			{panelError}
		</div>
	{/if}

	{#if !encryptionKeyConfigured}
		<div class="flex items-start gap-2.5 rounded-xl border border-warning/50 bg-warning-soft px-3.5 py-2.5 text-xs text-fg-secondary">
			<AlertTriangle class="mt-0.5 h-4 w-4 shrink-0 text-warning" />
			<div>
				<p class="font-heading font-semibold text-warning">MIRU_ENCRYPTION_KEY is not configured</p>
				<p class="mt-0.5 text-[11px]">
					Webhooks without a signing secret or auth header work normally, but set
					<code class="font-mono text-fg-primary">MIRU_ENCRYPTION_KEY</code> (&ge; 32 chars) to store
					HMAC signing secrets or custom auth headers encrypted at rest.
				</p>
			</div>
		</div>
	{/if}

	{#if loadingTargets}
		<p class="py-5 text-center font-mono text-xs text-fg-muted">Loading outbound webhooks…</p>
	{:else if targets.length === 0}
		<div class="rounded-xl border border-dashed border-border bg-elevated/60 px-4 py-6 text-center">
			<p class="font-heading text-xs font-semibold text-fg-primary">No outbound webhooks configured</p>
			<p class="mt-1 text-[11px] text-fg-muted">
				Add an HTTP endpoint (such as media-webhook-notifier or a custom automation receiver) to stream Miru events.
			</p>
		</div>
	{:else}
		<div class="flex flex-col gap-2.5">
			{#each targets as target (target.id)}
				{@const lastTest = testResultsByTargetId[target.id]}
				<div class="flex flex-col gap-3 rounded-xl border border-border bg-elevated p-3.5">
					<div class="flex flex-col gap-2.5 lg:flex-row lg:items-start lg:justify-between">
						<div class="min-w-0 flex-1">
							<div class="flex flex-wrap items-center gap-1.5">
								<h3 class="font-heading text-sm font-semibold text-fg-primary">{target.name}</h3>
								<Badge tone={target.enabled ? 'direct-play' : 'neutral'} dot mono>
									{target.enabled ? 'ENABLED' : 'DISABLED'}
								</Badge>
								{#if target.hasSigningSecret}
									<Badge tone="cyan" mono>
										<Lock class="h-2.5 w-2.5" />
										Signed
									</Badge>
								{/if}
								{#if target.hasAuthHeader}
									<Badge tone="primary" mono>
										<KeyRound class="h-2.5 w-2.5" />
										Auth header{target.authHeaderName ? `: ${target.authHeaderName}` : ''}
									</Badge>
								{/if}
								{#if target.transcode4kOnly}
									<Badge tone="hw-transcode" mono>4K ONLY</Badge>
								{/if}
							</div>

							<p class="mt-1 truncate font-mono text-[11px] text-fg-muted" title={target.url}>
								{target.url}
							</p>

							<div class="mt-2 flex flex-wrap items-center gap-1.5">
								{#each target.subscribedEvents as ev (ev)}
									<Badge tone="neutral" mono>{EVENT_LABELS[ev] ?? ev}</Badge>
								{/each}
							</div>

							<p class="mt-2 font-mono text-[10px] text-fg-muted">
								Cooldown {target.cooldownSeconds}s · Concurrent &gt; {target.concurrentStreamThreshold} ·
								Buffering &ge; {target.bufferingSpikeThreshold} / {target.bufferingWindowSeconds}s
							</p>
						</div>

						<div class="flex flex-wrap items-center gap-1.5">
							<Button
								variant="outline"
								size="sm"
								disabled={togglingTargetId === target.id}
								onclick={() => void handleToggleEnabled(target)}
							>
								<Power class="h-3.5 w-3.5" />
								{target.enabled ? 'Disable' : 'Enable'}
							</Button>
							<Button
								variant="cyan"
								size="sm"
								disabled={testingTargetId === target.id}
								onclick={() => void handleSendTest(target)}
							>
								<Send class="h-3.5 w-3.5" />
								{testingTargetId === target.id ? 'Sending…' : 'Send Test'}
							</Button>
							<Button variant="outline" size="sm" onclick={() => openEditSheet(target)}>
								<Pencil class="h-3.5 w-3.5" />
								Edit
							</Button>
							{#if confirmDeleteTargetId === target.id}
								<Button
									variant="destructive"
									size="sm"
									disabled={deletingTargetId === target.id}
									onclick={() => void handleDeleteTarget(target.id)}
								>
									{deletingTargetId === target.id ? 'Deleting…' : 'Confirm Delete'}
								</Button>
								<Button
									variant="ghost"
									size="sm"
									disabled={deletingTargetId === target.id}
									onclick={() => (confirmDeleteTargetId = null)}
								>
									Cancel
								</Button>
							{:else}
								<Button
									variant="destructive"
									size="sm"
									onclick={() => (confirmDeleteTargetId = target.id)}
								>
									<Trash2 class="h-3.5 w-3.5" />
									Delete
								</Button>
							{/if}
						</div>
					</div>

					{#if lastTest}
						<div
							role="status"
							class={`flex flex-wrap items-center justify-between gap-2 rounded-lg border px-3 py-2 text-xs ${
								lastTest.status === 'DELIVERED'
									? 'border-direct-play/50 bg-direct-play-soft text-direct-play'
									: 'border-danger/50 bg-danger-soft text-danger'
							}`}
						>
							<div class="flex items-center gap-2">
								{#if lastTest.status === 'DELIVERED'}
									<CheckCircle2 class="h-3.5 w-3.5 shrink-0" />
								{:else}
									<AlertTriangle class="h-3.5 w-3.5 shrink-0" />
								{/if}
								<span class="font-heading font-semibold">
									Test {lastTest.status === 'DELIVERED' ? 'Delivered' : 'Failed'}
								</span>
								{#if lastTest.httpStatus !== null && lastTest.httpStatus !== undefined}
									<span class="font-mono text-[11px]">HTTP {lastTest.httpStatus}</span>
								{/if}
								{#if lastTest.durationMs !== null && lastTest.durationMs !== undefined}
									<span class="font-mono text-[11px]">{lastTest.durationMs}ms</span>
								{/if}
							</div>
							{#if lastTest.errorMessage}
								<span class="font-mono text-[11px]">{lastTest.errorMessage}</span>
							{/if}
						</div>
					{/if}
				</div>
			{/each}
		</div>
	{/if}

	<!-- Recent Deliveries -->
	<div class="flex flex-col gap-2.5 rounded-xl border border-border bg-elevated p-3.5">
		<div class="flex flex-wrap items-center justify-between gap-2">
			<div class="flex items-center gap-2">
				<h3 class="font-heading text-xs font-semibold text-fg-primary">Recent Deliveries</h3>
				<Badge tone="neutral" mono>{deliveryTotalCount}</Badge>
			</div>
			<div class="flex flex-wrap items-center gap-1.5">
				{#each [{ key: 'ALL', label: 'All' }, { key: 'DELIVERED', label: 'Delivered' }, { key: 'RETRYING', label: 'Retrying' }, { key: 'FAILED', label: 'Failed' }] as tab (tab.key)}
					<button
						type="button"
						onclick={() => void loadDeliveries(tab.key as 'ALL' | WebhookDeliveryStatus, deliveryTargetFilter, 0)}
						aria-pressed={deliveryStatusFilter === tab.key}
						class={`cursor-pointer rounded-lg border px-2.5 py-1 font-heading text-[11px] font-medium transition-colors ${
							deliveryStatusFilter === tab.key
								? 'border-cyan bg-cyan-soft text-cyan-hi'
								: 'border-border bg-canvas text-fg-secondary hover:text-fg-primary'
						}`}
					>
						{tab.label}
					</button>
				{/each}

				{#if targets.length > 0}
					<select
						aria-label="Filter deliveries by target"
						value={deliveryTargetFilter}
						onchange={(e) => void loadDeliveries(deliveryStatusFilter, e.currentTarget.value, 0)}
						class="rounded-lg border border-input-border bg-canvas px-2 py-1 text-[11px] text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
					>
						<option value="ALL">All targets</option>
						{#each targets as t (t.id)}
							<option value={t.id}>{t.name}</option>
						{/each}
					</select>
				{/if}

				<Button
					variant="outline"
					size="sm"
					disabled={loadingDeliveries}
					onclick={() => void loadDeliveries(deliveryStatusFilter, deliveryTargetFilter, deliveryOffset)}
				>
					<RefreshCw class={`h-3 w-3 ${loadingDeliveries ? 'animate-spin' : ''}`} />
					Refresh
				</Button>
			</div>
		</div>

		{#if deliveries.length === 0}
			<p class="py-4 text-center text-xs text-fg-muted">
				No webhook deliveries recorded yet.
			</p>
		{:else}
			<div class="flex flex-col gap-2">
				{#each deliveries as item (item.id)}
					{@const errorExpanded = Boolean(expandedErrorIds[item.id])}
					<div class="flex flex-col gap-1.5 rounded-lg border border-border bg-canvas px-3 py-2.5 text-xs">
						<div class="flex flex-wrap items-center justify-between gap-2">
							<div class="flex flex-wrap items-center gap-1.5">
								<Badge tone={deliveryStatusTone(item.status)} dot mono>
									{item.status}
								</Badge>
								<Badge tone="cyan" mono>{item.eventType}</Badge>
								<span class="font-heading font-semibold text-fg-primary">{item.targetName}</span>
								{#if item.httpStatus !== null && item.httpStatus !== undefined}
									<Badge tone="neutral" mono>HTTP {item.httpStatus}</Badge>
								{/if}
								{#if item.durationMs !== null && item.durationMs !== undefined}
									<span class="font-mono text-[10px] text-fg-muted tabular-nums">
										{item.durationMs}ms
									</span>
								{/if}
								<span class="font-mono text-[10px] text-fg-muted tabular-nums">
									{item.attemptCount} {item.attemptCount === 1 ? 'attempt' : 'attempts'}
								</span>
							</div>

							<div class="flex items-center gap-2">
								{#if item.errorMessage}
									<button
										type="button"
										onclick={() => toggleErrorDetail(item.id)}
										aria-expanded={errorExpanded}
										class="inline-flex cursor-pointer items-center gap-1 rounded-md border border-danger/40 bg-danger-soft px-2 py-0.5 font-heading text-[10px] font-semibold text-danger"
									>
										<span>{errorExpanded ? 'Hide error' : 'Error details'}</span>
										<ChevronDown
											class={`h-3 w-3 transition-transform ${errorExpanded ? 'rotate-180' : ''}`}
										/>
									</button>
								{/if}
								<span
									class="font-mono text-[10px] text-fg-muted"
									title={formatFullUtc(item.createdAtMs)}
								>
									{formatDateTime(item.createdAtMs, preferencesStore.prefs)}
								</span>
							</div>
						</div>

						{#if item.errorMessage && errorExpanded}
							<p class="rounded-md border border-danger/30 bg-danger-soft/60 px-2.5 py-1.5 font-mono text-[11px] text-danger">
								{item.errorMessage}
							</p>
						{/if}
					</div>
				{/each}
			</div>

			{#if deliveryTotalCount > DELIVERY_PAGE_SIZE}
				<div class="flex items-center justify-between border-t border-border pt-2 text-[11px] text-fg-muted">
					<span class="font-mono tabular-nums">
						Showing {deliveryOffset + 1}–{Math.min(deliveryOffset + deliveries.length, deliveryTotalCount)} of {deliveryTotalCount}
					</span>
					<div class="flex items-center gap-1.5">
						<Button
							variant="outline"
							size="sm"
							disabled={!canPrevPage || loadingDeliveries}
							onclick={() =>
								void loadDeliveries(
									deliveryStatusFilter,
									deliveryTargetFilter,
									Math.max(0, deliveryOffset - DELIVERY_PAGE_SIZE)
								)}
						>
							Previous
						</Button>
						<Button
							variant="outline"
							size="sm"
							disabled={!canNextPage || loadingDeliveries}
							onclick={() =>
								void loadDeliveries(
									deliveryStatusFilter,
									deliveryTargetFilter,
									deliveryOffset + DELIVERY_PAGE_SIZE
								)}
						>
							Next
						</Button>
					</div>
				</div>
			{/if}
		{/if}
	</div>
</section>

<!-- Create / Edit Webhook Sheet -->
<Sheet
	bind:open={sheetOpen}
	onOpenChange={(v) => {
		sheetOpen = v;
		if (!v) formError = null;
	}}
	title={editingTarget ? `Edit Webhook · ${editingTarget.name}` : 'Add Outbound Webhook'}
	description="Configure destination URL, event subscriptions, cooldowns, and optional authentication"
>
	<form onsubmit={handleSaveTarget} class="flex flex-col gap-3.5">
		{#if formError}
			<div role="alert" class="rounded-lg border border-danger/50 bg-danger-soft px-3 py-2 text-xs text-danger">
				{formError}
			</div>
		{/if}

		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Target Name</span>
			<input
				type="text"
				required
				maxlength={100}
				placeholder="e.g. Media Webhook Notifier"
				bind:value={formName}
				class="rounded-lg border border-input-border bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			/>
		</label>

		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Destination URL</span>
			<input
				type="url"
				required
				maxlength={2048}
				placeholder="http://media-webhook-notifier.media.svc.cluster.local:8080/events"
				bind:value={formUrl}
				class="rounded-lg border border-input-border bg-canvas px-3 py-2 font-mono text-xs text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			/>
			{#if editingTarget && formOriginalRedactedUrl.includes('?***')}
				<span class="text-[10px] text-fg-muted">
					Query parameters are redacted; leave unchanged to keep the existing stored URL.
				</span>
			{/if}
		</label>

		<label class="flex cursor-pointer items-center justify-between rounded-lg border border-border bg-elevated px-3 py-2.5 text-xs">
			<span class="font-heading font-semibold text-fg-primary">Enabled</span>
			<input type="checkbox" bind:checked={formEnabled} class="h-4 w-4 accent-cyan" />
		</label>

		<div class="flex flex-col gap-1.5">
			<span class="font-heading text-xs font-semibold text-fg-primary">Subscribed Events</span>
			<div class="flex flex-col gap-1.5">
				{#each WEBHOOK_EVENTS as ev (ev.key)}
					{@const checked = formEvents.includes(ev.key)}
					<label class="flex cursor-pointer items-start justify-between gap-2 rounded-lg border border-border bg-elevated px-3 py-2 text-xs">
						<div class="min-w-0">
							<p class="font-heading font-semibold text-fg-primary">
								{ev.label}
								<span class="ml-1 font-mono text-[10px] font-normal text-fg-muted">{ev.key}</span>
							</p>
							<p class="mt-0.5 text-[10px] text-fg-muted">{ev.description}</p>
						</div>
						<input
							type="checkbox"
							{checked}
							onchange={() => toggleEvent(ev.key)}
							class="mt-0.5 h-4 w-4 shrink-0 accent-cyan"
						/>
					</label>
				{/each}
			</div>
		</div>

		<label class="flex cursor-pointer items-center justify-between rounded-lg border border-border bg-elevated px-3 py-2 text-xs">
			<div>
				<span class="font-heading font-semibold text-fg-primary">4K Video Transcodes Only</span>
				<p class="text-[10px] text-fg-muted">Only emit transcode.started for 4K / UHD streams</p>
			</div>
			<input type="checkbox" bind:checked={formTranscode4kOnly} class="h-4 w-4 accent-cyan" />
		</label>

		<div class="grid grid-cols-2 gap-2.5">
			<label class="flex flex-col gap-1">
				<span class="font-heading text-[11px] font-semibold text-fg-primary">Cooldown (sec)</span>
				<input
					type="number"
					min={0}
					max={86400}
					required
					bind:value={formCooldownSeconds}
					class="rounded-lg border border-input-border bg-canvas px-2.5 py-1.5 font-mono text-xs text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
			</label>
			<label class="flex flex-col gap-1">
				<span class="font-heading text-[11px] font-semibold text-fg-primary">Concurrent Limit</span>
				<input
					type="number"
					min={2}
					max={50}
					required
					bind:value={formConcurrentThreshold}
					class="rounded-lg border border-input-border bg-canvas px-2.5 py-1.5 font-mono text-xs text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
			</label>
			<label class="flex flex-col gap-1">
				<span class="font-heading text-[11px] font-semibold text-fg-primary">Buffering Spikes</span>
				<input
					type="number"
					min={2}
					max={20}
					required
					bind:value={formBufferingThreshold}
					class="rounded-lg border border-input-border bg-canvas px-2.5 py-1.5 font-mono text-xs text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
			</label>
			<label class="flex flex-col gap-1">
				<span class="font-heading text-[11px] font-semibold text-fg-primary">Buffering Window (sec)</span>
				<input
					type="number"
					min={10}
					max={3600}
					required
					bind:value={formBufferingWindowSeconds}
					class="rounded-lg border border-input-border bg-canvas px-2.5 py-1.5 font-mono text-xs text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
			</label>
		</div>

		<!-- Signing Secret -->
		<div class="flex flex-col gap-2 rounded-xl border border-border bg-elevated p-3">
			<label class="flex flex-col gap-1">
				<span class="font-heading text-xs font-semibold text-fg-primary">
					HMAC Signing Secret (optional)
				</span>
				<input
					type="password"
					autocomplete="new-password"
					maxlength={512}
					disabled={formClearSigningSecret}
					placeholder={editingTarget?.hasSigningSecret ? 'Leave blank to keep existing secret' : 'Sent as X-Miru-Signature-256'}
					bind:value={formSigningSecret}
					class="rounded-lg border border-input-border bg-canvas px-3 py-2 font-mono text-xs text-fg-primary disabled:opacity-50 focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
			</label>
			{#if editingTarget?.hasSigningSecret}
				<label class="flex cursor-pointer items-center gap-2 text-[11px] text-fg-secondary">
					<input
						type="checkbox"
						bind:checked={formClearSigningSecret}
						onchange={() => {
							if (formClearSigningSecret) formSigningSecret = '';
						}}
						class="h-3.5 w-3.5 accent-cyan"
					/>
					<span>Clear existing signing secret</span>
				</label>
			{/if}
		</div>

		<!-- Optional Custom Auth Header -->
		<div class="flex flex-col gap-2 rounded-xl border border-border bg-elevated p-3">
			<span class="font-heading text-xs font-semibold text-fg-primary">
				Custom Auth Header (optional)
			</span>
			<label class="flex flex-col gap-1">
				<span class="font-heading text-[11px] text-fg-secondary">Header Name</span>
				<input
					type="text"
					maxlength={128}
					disabled={formClearAuthHeader}
					placeholder="e.g. Authorization or X-Api-Key"
					bind:value={formAuthHeaderName}
					class="rounded-lg border border-input-border bg-canvas px-3 py-1.5 font-mono text-xs text-fg-primary disabled:opacity-50 focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
			</label>
			<label class="flex flex-col gap-1">
				<span class="font-heading text-[11px] text-fg-secondary">Header Value</span>
				<input
					type="password"
					autocomplete="new-password"
					maxlength={2048}
					disabled={formClearAuthHeader}
					placeholder={editingTarget?.hasAuthHeader ? 'Leave blank to keep existing value' : 'e.g. Bearer <token>'}
					bind:value={formAuthHeaderValue}
					class="rounded-lg border border-input-border bg-canvas px-3 py-1.5 font-mono text-xs text-fg-primary disabled:opacity-50 focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
			</label>
			{#if editingTarget?.hasAuthHeader}
				<label class="flex cursor-pointer items-center gap-2 text-[11px] text-fg-secondary">
					<input
						type="checkbox"
						bind:checked={formClearAuthHeader}
						onchange={() => {
							if (formClearAuthHeader) {
								formAuthHeaderName = '';
								formAuthHeaderValue = '';
							}
						}}
						class="h-3.5 w-3.5 accent-cyan"
					/>
					<span>Clear existing auth header</span>
				</label>
			{/if}
		</div>

		<Button type="submit" variant="primary" size="lg" disabled={formSubmitting} class="mt-1 w-full">
			{formSubmitting ? 'Saving…' : editingTarget ? 'Save Webhook Changes' : 'Create Webhook'}
		</Button>
	</form>
</Sheet>
