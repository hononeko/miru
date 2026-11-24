<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import {
		AlertTriangle,
		Check,
		CheckCircle2,
		ChevronDown,
		Copy,
		ExternalLink,
		FileSpreadsheet,
		KeyRound,
		Link2,
		Loader2,
		Plus,
		RefreshCw,
		Server,
		ShieldAlert,
		SlidersHorizontal,
		Trash2,
		Upload
	} from 'lucide-svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type {
		AccountResponse,
		ApiKeyResponse,
		GuardrailMode,
		GuardrailRuleDto,
		GuardrailRuleType,
		MiruRole,
		PlexConnectionOptionDto,
		PlexConnectionStatus,
		PlexDiscoveredServerDto,
		PlexSelectServerResponse,
		ProblemDetails,
		ReadinessResponse,
		TautulliImportResponse,
		TerminationAuditAction,
		TerminationAuditEntryDto,
		TerminationAuditSource,
		UserSummaryDto
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import Sheet from '$components/ui/Sheet.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { streamsStore } from '$stores/streams.svelte';
	import {
		API_KEY_EXPIRY_PRESETS,
		formatApiKeyExpiry,
		formatBytes,
		formatDateTime,
		formatFullUtc,
		formatRelativeTime,
		getApiKeyStatus,
		resolveApiKeyExpiresAtMs,
		safeInitial,
		validatePassword,
		type ApiKeyExpiryPreset
	} from '$lib/utils';

	type WizardStep = 'starting' | 'waiting' | 'expired' | 'discovering' | 'select' | 'saving' | 'done';

	let accounts = $state<AccountResponse[]>([]);
	let emailLinkingEnabled = $state(false);
	let apiKeys = $state<ApiKeyResponse[]>([]);
	let mediaUsers = $state<UserSummaryDto[]>([]);
	let readiness = $state<ReadinessResponse | null>(null);
	let plexStatus = $state<PlexConnectionStatus | null>(null);
	let guardrails = $state<GuardrailRuleDto[]>([]);
	let auditEntries = $state<TerminationAuditEntryDto[]>([]);
	let auditTotalCount = $state(0);
	let auditSourceFilter = $state<'ALL' | TerminationAuditSource>('ALL');
	let auditLoading = $state(false);
	let guardrailError = $state<string | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

	// Guardrail Edit Sheet
	let editRuleTarget = $state<GuardrailRuleDto | null>(null);
	let editRuleOpen = $state(false);
	let editRuleMode = $state<GuardrailMode>('DISABLED');
	let editRuleThreshold = $state<number>(30);
	let editRuleMessage = $state('');
	let editRuleSubmitting = $state(false);
	let editRuleError = $state<string | null>(null);

	// Newly created API key one-time reveal
	let createdRawKey = $state<string | null>(null);
	let copied = $state(false);

	// Create Account Sheet
	let createAccountOpen = $state(false);
	let newUsername = $state('');
	let newEmail = $state('');
	let newPassword = $state('');
	let newRole = $state<MiruRole>('USER');
	let accountSubmitting = $state(false);
	let createAccountError = $state<string | null>(null);

	// Link Media Users Sheet
	let linkTarget = $state<AccountResponse | null>(null);
	let linkOpen = $state(false);
	let selectedMediaUserIds = $state<string[]>([]);
	let linkSubmitting = $state(false);
	let linkError = $state<string | null>(null);

	// Create API Key Sheet
	let createKeyOpen = $state(false);
	let newKeyName = $state('');
	let newKeyRole = $state<MiruRole>('USER');
	let newKeyExpiryPreset = $state<ApiKeyExpiryPreset>('never');
	let newKeyCustomDate = $state('');
	let createKeyError = $state<string | null>(null);
	let keySubmitting = $state(false);

	// Tautulli Data Import
	let selectedImportFile = $state<File | null>(null);
	let importSubmitting = $state(false);
	let importError = $state<string | null>(null);
	let importReport = $state<TautulliImportResponse | null>(null);
	let warningsOpen = $state(false);

	function handleImportFileChange(e: Event) {
		const input = e.currentTarget as HTMLInputElement;
		selectedImportFile = input.files?.[0] ?? null;
		importError = null;
	}

	async function runTautulliImport(dryRun: boolean) {
		if (!selectedImportFile) return;
		importSubmitting = true;
		importError = null;
		warningsOpen = false;
		try {
			const body = new FormData();
			body.append('file', selectedImportFile);
			const res = await fetch(`/api/v1/admin/imports/tautulli?dryRun=${dryRun}`, {
				method: 'POST',
				credentials: 'include',
				body
			});
			const payload = await res.json().catch(() => null);
			if (!res.ok) {
				const problem =
					payload && typeof payload === 'object' ? (payload as Partial<ProblemDetails>) : undefined;
				throw new MiruApiError(res.status, problem);
			}
			importReport = payload as TautulliImportResponse;
			if (!dryRun) {
				await loadAll();
			}
		} catch (err) {
			importError = err instanceof MiruApiError ? err.detail : 'Failed to import Tautulli database';
		} finally {
			importSubmitting = false;
		}
	}

	// Plex PIN OAuth Wizard Sheet
	let plexWizardOpen = $state(false);
	let wizardStep = $state<WizardStep>('starting');
	let wizardError = $state<string | null>(null);
	let oauthPinId = $state<number | null>(null);
	let oauthAuthUrl = $state<string>('');
	let oauthExpiresAtMs = $state<number>(0);
	let authUrlCopied = $state(false);
	let nowTickMs = $state<number>(Date.now());
	let discoveredServers = $state<PlexDiscoveredServerDto[]>([]);
	let selectedMachineId = $state<string>('');
	let selectedConnectionUri = $state<string>('');
	let selectResult = $state<PlexSelectServerResponse | null>(null);

	let pollTimerId: ReturnType<typeof setInterval> | null = null;
	let countdownTimerId: ReturnType<typeof setInterval> | null = null;

	function stopOAuthTimers() {
		if (pollTimerId !== null) {
			clearInterval(pollTimerId);
			pollTimerId = null;
		}
		if (countdownTimerId !== null) {
			clearInterval(countdownTimerId);
			countdownTimerId = null;
		}
	}

	onDestroy(() => {
		stopOAuthTimers();
	});
	async function loadAll() {
		loading = true;
		error = null;
		guardrailError = null;
		try {
			const [accRes, keyRes, usrRes, readyRes, plexRes, guardrailsRes, auditRes] = await Promise.all([
				api.GET('/api/v1/admin/accounts'),
				api.GET('/api/v1/admin/api-keys'),
				api.GET('/api/v1/users'),
				api.GET('/readyz'),
				api.GET('/api/v1/admin/plex/status'),
				api.GET('/api/v1/admin/guardrails'),
				api.GET('/api/v1/admin/guardrails/audit', {
					params: {
						query: {
							source: auditSourceFilter === 'ALL' ? undefined : auditSourceFilter,
							limit: 20,
							offset: 0
						}
					}
				})
			]);
			const accountList = unwrap(accRes);
			accounts = accountList.accounts;
			emailLinkingEnabled = accountList.emailLinkingEnabled;
			apiKeys = unwrap(keyRes).apiKeys;
			mediaUsers = usrRes.data?.users ?? [];
			readiness = readyRes.data ?? null;
			plexStatus = plexRes.data ?? null;
			if (guardrailsRes.data) {
				guardrails = guardrailsRes.data.rules;
			} else if (guardrailsRes.error) {
				guardrailError = new MiruApiError(guardrailsRes.response.status, guardrailsRes.error).detail;
			}
			if (auditRes.data) {
				auditEntries = auditRes.data.items;
				auditTotalCount = auditRes.data.totalCount;
			}
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Failed to load admin console';
		} finally {
			loading = false;
		}
	}

	async function loadAudit(sourceFilter: 'ALL' | TerminationAuditSource = auditSourceFilter) {
		auditSourceFilter = sourceFilter;
		auditLoading = true;
		guardrailError = null;
		try {
			const data = unwrap(
				await api.GET('/api/v1/admin/guardrails/audit', {
					params: {
						query: {
							source: sourceFilter === 'ALL' ? undefined : sourceFilter,
							limit: 20,
							offset: 0
						}
					}
				})
			);
			auditEntries = data.items;
			auditTotalCount = data.totalCount;
		} catch (e) {
			guardrailError = e instanceof MiruApiError ? e.detail : 'Failed to load termination audit log';
		} finally {
			auditLoading = false;
		}
	}

	function guardrailTitle(ruleType: GuardrailRuleType): string {
		switch (ruleType) {
			case 'TRANSCODE_4K':
				return '4K Video Transcode';
			case 'PAUSED_TIMEOUT':
				return 'Paused Stream Timeout';
			case 'CONCURRENT_STREAMS':
				return 'Per-User Concurrent Stream Cap';
		}
	}

	function guardrailModeLabel(mode: GuardrailMode): string {
		switch (mode) {
			case 'DISABLED':
				return 'DISABLED';
			case 'WARN':
				return 'DRY-RUN WARN';
			case 'ENFORCE':
				return 'ENFORCE';
		}
	}

	function guardrailModeTone(mode: GuardrailMode): 'neutral' | 'warning' | 'danger' {
		switch (mode) {
			case 'DISABLED':
				return 'neutral';
			case 'WARN':
				return 'warning';
			case 'ENFORCE':
				return 'danger';
		}
	}

	function guardrailThresholdLabel(rule: GuardrailRuleDto): string {
		switch (rule.ruleType) {
			case 'TRANSCODE_4K':
				return 'Any 4K / UHD video transcode';
			case 'PAUSED_TIMEOUT':
				return `${rule.threshold ?? 30}m paused`;
			case 'CONCURRENT_STREAMS':
				return `Max ${rule.threshold ?? 2} streams / user`;
		}
	}

	function auditActionLabel(action: TerminationAuditAction): string {
		switch (action) {
			case 'TERMINATED':
				return 'TERMINATED';
			case 'WARNED':
				return 'DRY-RUN WARN';
			case 'TERMINATE_FAILED':
				return 'FAILED';
		}
	}

	function auditActionTone(action: TerminationAuditAction): 'primary' | 'warning' | 'danger' {
		switch (action) {
			case 'TERMINATED':
				return 'primary';
			case 'WARNED':
				return 'warning';
			case 'TERMINATE_FAILED':
				return 'danger';
		}
	}

	function openEditGuardrail(rule: GuardrailRuleDto) {
		editRuleTarget = rule;
		editRuleMode = rule.mode;
		editRuleThreshold =
			rule.threshold ?? (rule.ruleType === 'PAUSED_TIMEOUT' ? 30 : rule.ruleType === 'CONCURRENT_STREAMS' ? 2 : 0);
		editRuleMessage = rule.message;
		editRuleError = null;
		editRuleOpen = true;
	}

	async function handleSaveGuardrail(e: SubmitEvent) {
		e.preventDefault();
		if (!editRuleTarget || editRuleSubmitting) return;
		editRuleSubmitting = true;
		editRuleError = null;
		try {
			const updated = unwrap(
				await api.PUT('/api/v1/admin/guardrails/{ruleType}', {
					params: { path: { ruleType: editRuleTarget.ruleType } },
					body: {
						mode: editRuleMode,
						threshold: editRuleTarget.ruleType === 'TRANSCODE_4K' ? null : Number(editRuleThreshold),
						message: editRuleMessage.trim() || null
					}
				})
			);
			guardrails = guardrails.map((r) => (r.ruleType === updated.ruleType ? updated : r));
			editRuleOpen = false;
		} catch (err) {
			editRuleError = err instanceof MiruApiError ? err.detail : 'Failed to update guardrail rule';
		} finally {
			editRuleSubmitting = false;
		}
	}

	onMount(() => {
		void loadAll();
	});

	function handleWizardOpenChange(open: boolean) {
		plexWizardOpen = open;
		if (!open) {
			stopOAuthTimers();
		}
	}

	async function startPlexOAuthWizard() {
		stopOAuthTimers();
		plexWizardOpen = true;
		wizardStep = 'starting';
		wizardError = null;
		authUrlCopied = false;
		discoveredServers = [];
		selectedMachineId = '';
		selectedConnectionUri = '';
		selectResult = null;

		try {
			const started = unwrap(await api.POST('/api/v1/admin/plex/oauth/start'));
			oauthPinId = started.pinId;
			oauthAuthUrl = started.authUrl;
			oauthExpiresAtMs = started.expiresAtMs;
			nowTickMs = Date.now();
			wizardStep = 'waiting';

			window.open(started.authUrl, '_blank', 'noopener,noreferrer');

			countdownTimerId = setInterval(() => {
				nowTickMs = Date.now();
				if (nowTickMs >= oauthExpiresAtMs && wizardStep === 'waiting') {
					stopOAuthTimers();
					wizardStep = 'expired';
				}
			}, 1000);

			pollTimerId = setInterval(() => {
				void pollPinOnce();
			}, 2000);
		} catch (err) {
			wizardError = err instanceof MiruApiError ? err.detail : 'Failed to start Plex sign-in';
			wizardStep = 'expired';
		}
	}

	async function pollPinOnce() {
		if (oauthPinId === null || wizardStep !== 'waiting') return;
		try {
			const poll = unwrap(
				await api.GET('/api/v1/admin/plex/oauth/poll/{pinId}', {
					params: { path: { pinId: oauthPinId } }
				})
			);
			oauthExpiresAtMs = poll.expiresAtMs;
			if (poll.status === 'AUTHORIZED') {
				stopOAuthTimers();
				await loadDiscoveredServers(oauthPinId);
			} else if (poll.status === 'EXPIRED') {
				stopOAuthTimers();
				wizardStep = 'expired';
			}
		} catch (err) {
			stopOAuthTimers();
			wizardError = err instanceof MiruApiError ? err.detail : 'Failed to poll Plex authorization';
			wizardStep = 'expired';
		}
	}

	async function loadDiscoveredServers(pinId: number) {
		wizardStep = 'discovering';
		wizardError = null;
		try {
			const res = unwrap(
				await api.GET('/api/v1/admin/plex/oauth/servers', {
					params: { query: { pinId } }
				})
			);
			discoveredServers = res.servers;
			if (res.servers.length > 0) {
				selectedMachineId = res.servers[0].machineId;
				selectedConnectionUri = res.servers[0].connections[0]?.uri ?? '';
			}
			wizardStep = 'select';
		} catch (err) {
			wizardError = err instanceof MiruApiError ? err.detail : 'Failed to discover Plex servers';
			wizardStep = 'expired';
		}
	}

	function chooseServer(server: PlexDiscoveredServerDto) {
		selectedMachineId = server.machineId;
		selectedConnectionUri = server.connections[0]?.uri ?? '';
	}

	async function handleConfirmServerSelection() {
		if (!selectedMachineId || !selectedConnectionUri.trim()) return;
		wizardStep = 'saving';
		wizardError = null;
		try {
			selectResult = unwrap(
				await api.POST('/api/v1/admin/plex/oauth/select', {
					body: {
						pinId: oauthPinId,
						machineId: selectedMachineId,
						connectionUri: selectedConnectionUri.trim()
					}
				})
			);
			wizardStep = 'done';
			void streamsStore.refresh();
			await loadAll();
		} catch (err) {
			wizardError = err instanceof MiruApiError ? err.detail : 'Failed to save Plex server selection';
			wizardStep = 'select';
		}
	}

	async function copyAuthUrl() {
		if (!oauthAuthUrl) return;
		try {
			await navigator.clipboard.writeText(oauthAuthUrl);
			authUrlCopied = true;
		} catch {
			// ignore clipboard permission issues
		}
	}

	const remainingCountdown = $derived.by(() => {
		const remainingSec = Math.max(0, Math.floor((oauthExpiresAtMs - nowTickMs) / 1000));
		const mins = Math.floor(remainingSec / 60);
		const secs = remainingSec % 60;
		return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
	});

	const selectedServer = $derived(
		discoveredServers.find((s) => s.machineId === selectedMachineId) ?? null
	);

	function connectionLabel(conn: PlexConnectionOptionDto): string {
		if (conn.relay) return 'RELAY';
		if (conn.local) return 'LOCAL LAN';
		return 'REMOTE';
	}

	function credentialSourceLabel(status: PlexConnectionStatus): string {
		switch (status.credentialSource) {
			case 'ENV_OVERRIDE':
				return 'ENV OVERRIDE';
			case 'STORED_ENCRYPTED':
				return 'STORED · ENCRYPTED';
			case 'NOT_CONFIGURED':
				return 'NOT CONFIGURED';
		}
	}

	async function handleCreateAccount(e: SubmitEvent) {
		e.preventDefault();
		error = null;
		createAccountError = null;
		const passwordError = validatePassword(newPassword);
		if (passwordError) {
			createAccountError = passwordError;
			return;
		}
		accountSubmitting = true;
		try {
			unwrap(
				await api.POST('/api/v1/admin/accounts', {
					body: {
						username: newUsername.trim(),
						password: newPassword,
						email: newEmail.trim() || null,
						role: newRole
					}
				})
			);
			createAccountOpen = false;
			newUsername = '';
			newEmail = '';
			newPassword = '';
			newRole = 'USER';
			await loadAll();
		} catch (err) {
			createAccountError = err instanceof MiruApiError ? err.detail : 'Failed to create account';
		} finally {
			accountSubmitting = false;
		}
	}

	async function handleDeleteAccount(id: string) {
		error = null;
		try {
			const res = await api.DELETE('/api/v1/admin/accounts/{id}', {
				params: { path: { id } }
			});
			if (res.error) throw new MiruApiError(res.response.status, res.error);
			await loadAll();
		} catch (err) {
			error = err instanceof MiruApiError ? err.detail : 'Failed to delete account';
		}
	}

	function openLinkSheet(account: AccountResponse) {
		linkTarget = account;
		selectedMediaUserIds = [...account.linkedMediaUserIds];
		linkError = null;
		linkOpen = true;
	}

	function toggleMediaUser(id: string) {
		if (selectedMediaUserIds.includes(id)) {
			selectedMediaUserIds = selectedMediaUserIds.filter((x) => x !== id);
		} else {
			selectedMediaUserIds = [...selectedMediaUserIds, id];
		}
	}

	async function handleSaveLinks() {
		if (!linkTarget) return;
		linkSubmitting = true;
		linkError = null;
		try {
			unwrap(
				await api.PUT('/api/v1/admin/accounts/{id}/media-users', {
					params: { path: { id: linkTarget.id } },
					body: { mediaUserIds: selectedMediaUserIds }
				})
			);
			linkOpen = false;
			await loadAll();
		} catch (err) {
			linkError = err instanceof MiruApiError ? err.detail : 'Failed to update linked media users';
		} finally {
			linkSubmitting = false;
		}
	}

	async function handleUseEmailLinking() {
		if (!linkTarget) return;
		linkSubmitting = true;
		linkError = null;
		try {
			unwrap(
				await api.PUT('/api/v1/admin/accounts/{id}/media-user-link-mode', {
					params: { path: { id: linkTarget.id } },
					body: { mode: 'AUTO' }
				})
			);
			linkOpen = false;
			await loadAll();
		} catch (err) {
			linkError = err instanceof MiruApiError ? err.detail : 'Failed to switch to email linking';
		} finally {
			linkSubmitting = false;
		}
	}

	async function handleCreateKey(e: SubmitEvent) {
		e.preventDefault();
		createKeyError = null;
		error = null;
		const expiresAtMs = resolveApiKeyExpiresAtMs(newKeyExpiryPreset, newKeyCustomDate);
		if (Number.isNaN(expiresAtMs)) {
			createKeyError = 'Expiration date must be a valid future date.';
			return;
		}
		keySubmitting = true;
		try {
			const res = unwrap(
				await api.POST('/api/v1/admin/api-keys', {
					body: {
						name: newKeyName.trim(),
						role: newKeyRole,
						expiresAtMs
					}
				})
			);
			createdRawKey = res.rawKey;
			copied = false;
			createKeyOpen = false;
			newKeyName = '';
			newKeyRole = 'USER';
			newKeyExpiryPreset = 'never';
			newKeyCustomDate = '';
			await loadAll();
		} catch (err) {
			createKeyError = err instanceof MiruApiError ? err.detail : 'Failed to create API key';
		} finally {
			keySubmitting = false;
		}
	}

	async function handleRevokeKey(id: string) {
		error = null;
		try {
			const res = await api.DELETE('/api/v1/admin/api-keys/{id}', {
				params: { path: { id } }
			});
			if (res.error) throw new MiruApiError(res.response.status, res.error);
			await loadAll();
		} catch (err) {
			error = err instanceof MiruApiError ? err.detail : 'Failed to revoke API key';
		}
	}

	async function copyKey() {
		if (!createdRawKey) return;
		try {
			await navigator.clipboard.writeText(createdRawKey);
			copied = true;
		} catch {
			// ignore clipboard permission issues
		}
	}

	const activeProviderLabel = $derived.by(() => {
		if (authStore.authMode === 'none') return 'NONE';
		if (authStore.localEnabled && authStore.oidcEnabled) return 'LOCAL + OIDC';
		if (authStore.oidcEnabled) return 'OIDC';
		return 'LOCAL';
	});

	function mediaUserName(id: string): string {
		const found = mediaUsers.find((u) => u.id === id);
		return found ? found.displayName || found.username : id;
	}
</script>

<svelte:head>
	<title>Admin & Access Control · Miru</title>
</svelte:head>

<div class="flex flex-col gap-5">
	<div class="flex flex-wrap items-center justify-between gap-3">
		<div>
			<h1 class="font-display text-2xl font-bold tracking-wide text-fg-primary sm:text-3xl">
				Admin & Access Control
			</h1>
			<p class="mt-0.5 text-xs text-fg-secondary sm:text-sm">
				Manage Plex server connection, local Miru accounts, RBAC links, and headless API keys
			</p>
		</div>
		<div class="flex items-center gap-2">
			<Button variant="cyan" size="md" onclick={() => (createKeyOpen = true)}>
				<KeyRound class="h-3.5 w-3.5" />
				New API Key
			</Button>
			{#if authStore.localEnabled}
				<Button variant="primary" size="md" onclick={() => (createAccountOpen = true)}>
					<Plus class="h-3.5 w-3.5" />
					Create Account
				</Button>
			{/if}
		</div>
	</div>

	{#if error}
		<div role="alert" class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
			{error}
		</div>
	{/if}

	<!-- Media Server · Plex Connection -->
	<section class="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
		<div class="flex flex-wrap items-center justify-between gap-2">
			<div class="flex items-center gap-2">
				<Server class="h-4 w-4 text-primary-text" />
				<h2 class="font-heading text-sm font-semibold text-fg-primary">
					Media Server · Plex Connection
				</h2>
			</div>
			{#if plexStatus}
				<div class="flex flex-wrap items-center gap-1.5">
					<Badge
						tone={plexStatus.reachable ? 'direct-play' : plexStatus.configured ? 'warning' : 'neutral'}
						dot
						mono
					>
						{plexStatus.reachable ? 'CONNECTED' : plexStatus.configured ? 'UNREACHABLE' : 'NOT CONFIGURED'}
					</Badge>
					<Badge
						tone={plexStatus.credentialSource === 'ENV_OVERRIDE'
							? 'cyan'
							: plexStatus.credentialSource === 'STORED_ENCRYPTED'
								? 'primary'
								: 'neutral'}
						mono
					>
						{credentialSourceLabel(plexStatus)}
					</Badge>
				</div>
			{/if}
		</div>

		{#if plexStatus}
			{#if plexStatus.credentialSource === 'ENV_OVERRIDE'}
				<div class="flex items-start gap-2.5 rounded-xl border border-cyan/40 bg-cyan-soft/50 px-3.5 py-2.5 text-xs text-fg-secondary">
					<AlertTriangle class="mt-0.5 h-4 w-4 shrink-0 text-cyan-hi" />
					<div>
						<p class="font-heading font-semibold text-cyan-hi">Environment override active (PLEX_URL / PLEX_TOKEN)</p>
						<p class="mt-0.5 text-[11px]">
							You can still run Plex OAuth to save encrypted credentials in the database, but Miru will continue using environment variables until they are removed.
						</p>
					</div>
				</div>
			{/if}

			{#if !plexStatus.encryptionKeyConfigured}
				<div class="flex items-start gap-2.5 rounded-xl border border-warning/50 bg-warning-soft px-3.5 py-2.5 text-xs text-fg-secondary">
					<AlertTriangle class="mt-0.5 h-4 w-4 shrink-0 text-warning" />
					<div>
						<p class="font-heading font-semibold text-warning">MIRU_ENCRYPTION_KEY is not configured</p>
						<p class="mt-0.5 text-[11px]">
							Set <code class="font-mono text-fg-primary">MIRU_ENCRYPTION_KEY</code> (at least 32 characters) in your environment to save Plex OAuth credentials encrypted at rest.
						</p>
					</div>
				</div>
			{/if}

			<div class="flex flex-col gap-3 rounded-xl border border-border bg-elevated p-3.5 sm:flex-row sm:items-center sm:justify-between">
				<div class="min-w-0">
					<p class="font-heading text-sm font-semibold text-fg-primary">
						{plexStatus.serverName || (plexStatus.configured ? 'Plex Media Server' : 'No Plex server connected')}
					</p>
					<p class="mt-0.5 truncate font-mono text-[11px] text-fg-muted">
						{#if plexStatus.baseUrl}
							{plexStatus.baseUrl}{plexStatus.machineId ? ` · ${plexStatus.machineId}` : ''}
						{:else}
							Use Plex PIN OAuth to sign in and auto-discover your server without extracting X-Plex-Token manually.
						{/if}
					</p>
					{#if plexStatus.statusMessage && !plexStatus.reachable}
						<p class="mt-1 text-[11px] text-fg-secondary">{plexStatus.statusMessage}</p>
					{/if}
				</div>
				<div class="flex shrink-0 items-center gap-2">
					<Button variant="primary" size="md" onclick={startPlexOAuthWizard}>
						<Link2 class="h-3.5 w-3.5" />
						{plexStatus.configured ? 'Reconnect Plex' : 'Connect with Plex'}
					</Button>
				</div>
			</div>
		{:else if loading}
			<p class="py-4 text-center font-mono text-xs text-fg-muted">Checking Plex connection…</p>
		{/if}
	</section>

	<!-- Accounts & Linked Media Users -->
	<section class="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
		<div class="flex flex-wrap items-center justify-between gap-2">
			<h2 class="font-heading text-sm font-semibold text-fg-primary">
				Accounts & Linked Media Users (RBAC)
			</h2>
			<Badge tone="neutral" mono>AUTH = {activeProviderLabel}</Badge>
		</div>

		{#if loading}
			<p class="py-6 text-center font-mono text-xs text-fg-muted">Loading accounts…</p>
		{:else if accounts.length === 0}
			<p class="py-6 text-center text-xs text-fg-muted">No local accounts found.</p>
		{:else}
			<div class="flex flex-col gap-2.5">
				{#each accounts as acc (acc.id)}
					<div class="flex flex-col gap-3 rounded-xl border border-border bg-elevated p-3.5 md:flex-row md:items-center md:justify-between">
						<div class="flex items-center gap-3">
							<div
								class={acc.role === 'ADMIN'
									? 'flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-primary bg-primary-soft font-heading text-xs font-bold text-primary-text'
									: 'flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-cyan bg-cyan-soft font-heading text-xs font-bold text-cyan-hi'}
							>
								{safeInitial(acc.username)}
							</div>
							<div>
								<p class="font-heading text-sm font-semibold text-fg-primary">{acc.username}</p>
								<p class="text-[11px] text-fg-muted">
									{acc.email || 'No email'} · Created {formatRelativeTime(acc.createdAtMs)}
								</p>
							</div>
						</div>

						<div class="flex flex-wrap items-center gap-1.5">
							<Badge tone={acc.authProvider === 'OIDC' ? 'cyan' : 'neutral'} mono>
								{acc.authProvider}
							</Badge>
							{#if acc.authProvider === 'OIDC' && acc.role !== 'ADMIN'}
								<Badge tone="neutral" mono>
									{acc.mediaUserLinkMode === 'MANUAL'
										? 'Linked manually'
										: emailLinkingEnabled
											? 'Linked by email'
											: 'Email linking off'}
								</Badge>
							{/if}
							<Badge tone={acc.role === 'ADMIN' ? 'primary' : 'neutral'}>{acc.role}</Badge>
							{#if acc.role === 'ADMIN'}
								<Badge tone="cyan" mono>All Media Users</Badge>
							{:else if acc.linkedMediaUserIds.length === 0}
								<Badge tone="neutral" mono>No linked media users</Badge>
							{:else}
								{#each acc.linkedMediaUserIds as mId}
									<Badge tone="cyan" mono>{mediaUserName(mId)}</Badge>
								{/each}
							{/if}
						</div>

						<div class="flex items-center gap-2">
							<Button variant="outline" size="sm" onclick={() => openLinkSheet(acc)}>
								<Link2 class="h-3.5 w-3.5" />
								Link Users
							</Button>
							{#if acc.id !== authStore.principal?.accountId}
								<Button variant="destructive" size="sm" onclick={() => handleDeleteAccount(acc.id)}>
									<Trash2 class="h-3.5 w-3.5" />
									Delete
								</Button>
							{/if}
						</div>
					</div>
				{/each}
			</div>
		{/if}
	</section>

	<!-- API Keys -->
	<section class="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
		<div class="flex flex-wrap items-center justify-between gap-2">
			<h2 class="font-heading text-sm font-semibold text-fg-primary">
				API Keys (X-Api-Key / Bearer)
			</h2>
			<span class="font-mono text-[10px] text-fg-muted">
				SHA-256 hashed at rest · Raw key shown once
			</span>
		</div>

		{#if createdRawKey}
			<div class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-direct-play bg-direct-play-soft p-3.5">
				<div class="min-w-0">
					<p class="font-heading text-xs font-semibold text-direct-play">
						New API Key Created — Copy now, it will not be shown again
					</p>
					<p class="mt-1 break-all font-mono text-xs font-semibold text-fg-primary">
						{createdRawKey}
					</p>
				</div>
				<Button variant="cyan" size="sm" onclick={copyKey}>
					{#if copied}
						<Check class="h-3.5 w-3.5" /> Copied
					{:else}
						<Copy class="h-3.5 w-3.5" /> Copy Key
					{/if}
				</Button>
			</div>
		{/if}

		{#if apiKeys.length === 0}
			<p class="py-6 text-center text-xs text-fg-muted">No API keys created yet.</p>
		{:else}
			<div class="flex flex-col gap-2">
				{#each apiKeys as key (key.id)}
					{@const status = getApiKeyStatus(key)}
					{@const revoked = status === 'REVOKED'}
					{@const expired = status === 'EXPIRED'}
					<div class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-elevated px-3.5 py-2.5">
						<div>
							<p class={revoked || expired ? 'font-heading text-xs font-semibold text-fg-muted line-through' : 'font-heading text-xs font-semibold text-fg-primary'}>
								{key.name}
							</p>
							<p class="font-mono text-[10px] text-fg-muted">
								{key.keyPrefix}… · {key.role} · {revoked ? `Revoked ${formatRelativeTime(key.revokedAtMs, Date.now(), preferencesStore.prefs)}` : `Last used ${formatRelativeTime(key.lastUsedAtMs, Date.now(), preferencesStore.prefs)}`} · {formatApiKeyExpiry(key.expiresAtMs, Date.now(), preferencesStore.prefs)}
							</p>
						</div>
						<div class="flex items-center gap-2">
							<Badge tone={revoked ? 'danger' : expired ? 'warning' : 'direct-play'} dot>
								{status}
							</Badge>
							{#if !revoked}
								<Button variant="destructive" size="sm" onclick={() => handleRevokeKey(key.id)}>
									Revoke
								</Button>
							{/if}
						</div>
					</div>
				{/each}
			</div>
		{/if}
	</section>

	<!-- Stream Guardrails & Audit Log -->
	<section class="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
		<div class="flex flex-wrap items-center justify-between gap-2">
			<div class="flex items-center gap-2">
				<ShieldAlert class="h-4 w-4 text-cyan-hi" />
				<div>
					<h2 class="font-heading text-sm font-semibold text-fg-primary">
						Stream Guardrails &amp; Termination Audit
					</h2>
					<p class="mt-0.5 text-[11px] text-fg-muted">
						Automated resource-protection rules (default disabled; test safely with Dry-Run Warn before enforcing)
					</p>
				</div>
			</div>
			<Badge tone="neutral" mono>EVALUATED ON STREAM UPDATES</Badge>
		</div>

		{#if guardrailError}
			<div role="alert" class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
				{guardrailError}
			</div>
		{/if}

		{#if guardrails.length > 0}
			<div class="grid grid-cols-1 gap-2.5 lg:grid-cols-3">
				{#each guardrails as rule (rule.ruleType)}
					<div class="flex flex-col justify-between gap-3 rounded-xl border border-border bg-elevated p-3.5">
						<div class="flex flex-col gap-2">
							<div class="flex flex-wrap items-start justify-between gap-2">
								<h3 class="font-heading text-xs font-semibold text-fg-primary">
									{guardrailTitle(rule.ruleType)}
								</h3>
								<Badge
									tone={guardrailModeTone(rule.mode)}
									dot={rule.mode !== 'DISABLED'}
									mono
								>
									{guardrailModeLabel(rule.mode)}
								</Badge>
							</div>
							<div class="flex flex-wrap items-center gap-1.5">
								<Badge tone="cyan" mono>{guardrailThresholdLabel(rule)}</Badge>
								{#if rule.isOverridden}
									<Badge tone="neutral" mono>CONFIGURED</Badge>
								{/if}
							</div>
							<p class="line-clamp-2 text-[11px] text-fg-secondary" title={rule.message}>
								&ldquo;{rule.message}&rdquo;
							</p>
						</div>

						<div class="flex items-center justify-between gap-2 border-t border-border pt-2.5">
							<span class="truncate font-mono text-[10px] text-fg-muted">
								{#if rule.updatedAtMs}
									{formatRelativeTime(rule.updatedAtMs, Date.now(), preferencesStore.prefs)}{rule.updatedBy ? ` · ${rule.updatedBy}` : ''}
								{:else}
									Environment default
								{/if}
							</span>
							<Button variant="outline" size="sm" onclick={() => openEditGuardrail(rule)}>
								<SlidersHorizontal class="h-3.5 w-3.5" />
								Configure
							</Button>
						</div>
					</div>
				{/each}
			</div>
		{:else if loading}
			<p class="py-4 text-center font-mono text-xs text-fg-muted">Loading guardrail rules…</p>
		{/if}

		<div class="flex flex-col gap-2.5 rounded-xl border border-border bg-elevated p-3.5">
			<div class="flex flex-wrap items-center justify-between gap-2">
				<div class="flex items-center gap-2">
					<h3 class="font-heading text-xs font-semibold text-fg-primary">
						Termination &amp; Guardrail Audit Log
					</h3>
					<Badge tone="neutral" mono>{auditTotalCount}</Badge>
				</div>
				<div class="flex flex-wrap items-center gap-1.5">
					{#each [{ key: 'ALL', label: 'All' }, { key: 'MANUAL', label: 'Manual' }, { key: 'GUARDRAIL', label: 'Guardrail' }] as tab (tab.key)}
						<button
							type="button"
							onclick={() => void loadAudit(tab.key as 'ALL' | TerminationAuditSource)}
							aria-pressed={auditSourceFilter === tab.key}
							class={`cursor-pointer rounded-lg border px-2.5 py-1 font-heading text-[11px] font-medium transition-colors ${
								auditSourceFilter === tab.key
									? 'border-cyan bg-cyan-soft text-cyan-hi'
									: 'border-border bg-canvas text-fg-secondary hover:text-fg-primary'
							}`}
						>
							{tab.label}
						</button>
					{/each}
					<Button
						variant="outline"
						size="sm"
						disabled={auditLoading}
						onclick={() => void loadAudit(auditSourceFilter)}
					>
						<RefreshCw class={`h-3 w-3 ${auditLoading ? 'animate-spin' : ''}`} />
						Refresh
					</Button>
				</div>
			</div>

			{#if auditEntries.length === 0}
				<p class="py-4 text-center text-xs text-fg-muted">
					No termination or guardrail audit events recorded yet.
				</p>
			{:else}
				<div class="flex flex-col gap-2">
					{#each auditEntries as entry (entry.id)}
						<div class="flex flex-col gap-1.5 rounded-lg border border-border bg-canvas px-3 py-2.5 text-xs">
							<div class="flex flex-wrap items-center justify-between gap-2">
								<div class="flex flex-wrap items-center gap-1.5">
									<Badge
										tone={auditActionTone(entry.action)}
										dot
										mono
									>
										{auditActionLabel(entry.action)}
									</Badge>
									<span class="font-heading font-semibold text-fg-primary">
										{entry.actorName}
									</span>
									<span class="text-fg-muted">→</span>
									<span class="font-heading font-semibold text-cyan-hi">
										{entry.targetUsername || entry.targetUserId || 'Unknown user'}
									</span>
									<span class="text-fg-secondary">· {entry.mediaTitle}</span>
									{#if entry.videoResolution || entry.streamDecision}
										<Badge tone="neutral" mono>
											{[entry.videoResolution?.toUpperCase(), entry.streamDecision]
												.filter(Boolean)
												.join(' · ')}
										</Badge>
									{/if}
								</div>
								<span
									class="font-mono text-[10px] text-fg-muted"
									title={formatFullUtc(entry.createdAtMs)}
								>
									{formatDateTime(entry.createdAtMs, preferencesStore.prefs)}
								</span>
							</div>
							<p class="text-[11px] text-fg-secondary">
								&ldquo;{entry.reason}&rdquo;
							</p>
							{#if entry.errorDetail}
								<p class="font-mono text-[10px] text-danger">
									Error: {entry.errorDetail}
								</p>
							{/if}
						</div>
					{/each}
				</div>
			{/if}
		</div>
	</section>

	<!-- Data Import (Tautulli) -->
	<section class="flex flex-col gap-3 rounded-xl border border-border bg-surface p-4">
		<div class="flex flex-wrap items-center justify-between gap-2">
			<div>
				<h2 class="font-heading text-sm font-semibold text-fg-primary">
					Data Import (Tautulli)
				</h2>
				<p class="mt-0.5 text-[11px] text-fg-muted">
					Upload a <span class="font-mono">tautulli.db</span> SQLite database to import historical users and playback sessions.
				</p>
			</div>
			<Badge tone="neutral" mono>IDEMPOTENT · tautulli:&lt;id&gt;</Badge>
		</div>

		<div class="flex flex-col gap-3 rounded-xl border border-border bg-elevated p-3.5 sm:flex-row sm:items-center sm:justify-between">
			<div class="flex min-w-0 flex-1 items-center gap-3">
				<label class="inline-flex cursor-pointer items-center gap-2 rounded-lg border border-border-strong bg-canvas px-3 py-2 font-heading text-xs font-semibold text-fg-primary transition-colors hover:border-cyan">
					<Upload class="h-3.5 w-3.5 text-cyan" />
					<span>Choose .db File</span>
					<input
						type="file"
						accept=".db,.sqlite,.sqlite3"
						onchange={handleImportFileChange}
						class="sr-only"
					/>
				</label>
				{#if selectedImportFile}
					<div class="min-w-0">
						<p class="truncate font-mono text-xs font-semibold text-fg-primary">
							{selectedImportFile.name}
						</p>
						<p class="font-mono text-[10px] text-fg-muted">
							{formatBytes(selectedImportFile.size)}
						</p>
					</div>
				{:else}
					<span class="text-xs text-fg-muted">No database file selected</span>
				{/if}
			</div>

			<div class="flex flex-wrap items-center gap-2">
				<Button
					variant="outline"
					size="md"
					disabled={!selectedImportFile || importSubmitting}
					onclick={() => runTautulliImport(true)}
				>
					<FileSpreadsheet class="h-3.5 w-3.5" />
					{importSubmitting ? 'Processing…' : 'Dry Run Preview'}
				</Button>
				<Button
					variant="primary"
					size="md"
					disabled={!selectedImportFile || importSubmitting}
					onclick={() => runTautulliImport(false)}
				>
					<Upload class="h-3.5 w-3.5" />
					{importSubmitting ? 'Importing…' : 'Run Import'}
				</Button>
			</div>
		</div>

		{#if importError}
			<div role="alert" class="rounded-xl border border-danger/50 bg-danger-soft px-4 py-3 text-xs text-danger">
				{importError}
			</div>
		{/if}

		{#if importReport}
			<div class="flex flex-col gap-3 rounded-xl border border-border bg-elevated p-3.5">
				<div class="flex flex-wrap items-center justify-between gap-2">
					<div class="flex items-center gap-2">
						<Badge tone={importReport.dryRun ? 'cyan' : 'direct-play'} dot>
							{importReport.dryRun ? 'DRY RUN PREVIEW' : 'IMPORT COMPLETE'}
						</Badge>
						<span class="font-mono text-[11px] text-fg-muted">
							Server: {importReport.serverId}
						</span>
					</div>
					<span class="font-mono text-[11px] text-fg-muted">
						Users upserted: {importReport.usersUpserted}
					</span>
				</div>

				<div class="grid grid-cols-2 gap-2.5 sm:grid-cols-5">
					<div class="rounded-lg border border-border bg-canvas px-3 py-2">
						<p class="text-[10px] uppercase tracking-wider text-fg-muted">Users</p>
						<p class="mt-0.5 font-mono text-base font-bold text-fg-primary">{importReport.usersDiscovered}</p>
					</div>
					<div class="rounded-lg border border-border bg-canvas px-3 py-2">
						<p class="text-[10px] uppercase tracking-wider text-fg-muted">Rows Read</p>
						<p class="mt-0.5 font-mono text-base font-bold text-fg-primary">{importReport.historyRowsRead}</p>
					</div>
					<div class="rounded-lg border border-border bg-canvas px-3 py-2">
						<p class="text-[10px] uppercase tracking-wider text-fg-muted">
							{importReport.dryRun ? 'Would Import' : 'Imported'}
						</p>
						<p class="mt-0.5 font-mono text-base font-bold text-direct-play">{importReport.historyImported}</p>
					</div>
					<div class="rounded-lg border border-border bg-canvas px-3 py-2">
						<p class="text-[10px] uppercase tracking-wider text-fg-muted">Skipped Duplicates</p>
						<p class="mt-0.5 font-mono text-base font-bold text-fg-secondary">{importReport.historySkippedDuplicate}</p>
					</div>
					<div class="rounded-lg border border-border bg-canvas px-3 py-2">
						<p class="text-[10px] uppercase tracking-wider text-fg-muted">Malformed</p>
						<p class={importReport.malformedRows > 0 ? 'mt-0.5 font-mono text-base font-bold text-danger' : 'mt-0.5 font-mono text-base font-bold text-fg-secondary'}>
							{importReport.malformedRows}
						</p>
					</div>
				</div>

				{#if importReport.warnings.length > 0}
					<div class="rounded-lg border border-border bg-canvas">
						<button
							type="button"
							onclick={() => (warningsOpen = !warningsOpen)}
							class="flex w-full cursor-pointer items-center justify-between px-3 py-2 text-left text-xs font-semibold text-fg-secondary hover:text-fg-primary"
						>
							<span>Warnings ({importReport.warnings.length})</span>
							<ChevronDown class={warningsOpen ? 'h-4 w-4 rotate-180 transition-transform' : 'h-4 w-4 transition-transform'} />
						</button>
						{#if warningsOpen}
							<ul class="flex flex-col gap-1 border-t border-border px-3 py-2 font-mono text-[11px] text-fg-muted">
								{#each importReport.warnings as warning}
									<li>• {warning}</li>
								{/each}
							</ul>
						{/if}
					</div>
				{/if}
			</div>
		{/if}
	</section>

	<!-- System Health (/readyz) -->
	{#if readiness}
		<section class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-surface px-4 py-3">
			<span class="font-heading text-xs font-semibold text-fg-primary">System Readiness (/readyz)</span>
			<div class="flex flex-wrap items-center gap-2">
				{#each Object.entries(readiness.checks) as [name, check]}
					<Badge tone={check.status === 'healthy' ? 'direct-play' : 'danger'} dot mono>
						{name.toUpperCase()} · {check.status.toUpperCase()}
					</Badge>
				{/each}
			</div>
		</section>
	{/if}
</div>

<!-- Plex PIN OAuth Wizard Sheet -->
<Sheet
	bind:open={plexWizardOpen}
	onOpenChange={handleWizardOpenChange}
	title="Connect Plex Media Server"
	description="Sign in with Plex PIN OAuth to auto-retrieve credentials and discover servers"
>
	<div class="flex flex-col gap-4">
		{#if wizardError}
			<div role="alert" class="rounded-xl border border-danger/50 bg-danger-soft px-3.5 py-2.5 text-xs text-danger">
				{wizardError}
			</div>
		{/if}

		{#if wizardStep === 'starting' || wizardStep === 'discovering' || wizardStep === 'saving'}
			<div class="flex flex-col items-center justify-center gap-3 py-8 text-center">
				<Loader2 class="h-6 w-6 animate-spin text-primary-text" />
				<p class="font-heading text-sm font-semibold text-fg-primary">
					{#if wizardStep === 'starting'}
						Creating Plex OAuth PIN…
					{:else if wizardStep === 'discovering'}
						Discovering Plex servers…
					{:else}
						Saving encrypted credentials & reloading…
					{/if}
				</p>
			</div>
		{:else if wizardStep === 'waiting'}
			<div class="flex flex-col gap-3 rounded-xl border border-border bg-elevated p-4">
				<div class="flex items-center justify-between gap-2">
					<span class="inline-flex items-center gap-2 font-heading text-xs font-semibold text-fg-primary">
						<Loader2 class="h-3.5 w-3.5 animate-spin text-cyan-hi" />
						Waiting for Plex authorization…
					</span>
					<Badge tone="cyan" mono>{remainingCountdown}</Badge>
				</div>
				<p class="text-xs text-fg-secondary">
					Complete sign-in in the Plex tab that opened. If popups are blocked or you are on mobile, use the link below:
				</p>
				<div class="flex flex-col gap-2">
					<a
						href={oauthAuthUrl}
						target="_blank"
						rel="noopener noreferrer"
						class="inline-flex items-center justify-center gap-2 rounded-lg bg-primary px-3 py-2 text-xs font-semibold text-primary-fg hover:bg-primary/85"
					>
						<ExternalLink class="h-3.5 w-3.5" />
						Open app.plex.tv Sign-In
					</a>
					<Button variant="outline" size="sm" onclick={copyAuthUrl} class="w-full">
						{#if authUrlCopied}
							<Check class="h-3.5 w-3.5" /> Link Copied
						{:else}
							<Copy class="h-3.5 w-3.5" /> Copy Fallback Sign-In URL
						{/if}
					</Button>
				</div>
				<p class="break-all rounded-lg bg-canvas px-2.5 py-2 font-mono text-[10px] text-fg-muted">
					{oauthAuthUrl}
				</p>
			</div>
			<Button variant="ghost" size="md" onclick={() => handleWizardOpenChange(false)} class="w-full">
				Cancel
			</Button>
		{:else if wizardStep === 'expired'}
			<div class="flex flex-col items-center gap-3 rounded-xl border border-border bg-elevated p-5 text-center">
				<AlertTriangle class="h-6 w-6 text-warning" />
				<div>
					<p class="font-heading text-sm font-semibold text-fg-primary">Plex Sign-In Expired or Interrupted</p>
					<p class="mt-1 text-xs text-fg-secondary">
						Start a fresh PIN session to authorize Miru with your Plex account.
					</p>
				</div>
				<div class="flex w-full gap-2">
					<Button variant="outline" size="md" onclick={() => handleWizardOpenChange(false)} class="flex-1">
						Close
					</Button>
					<Button variant="primary" size="md" onclick={startPlexOAuthWizard} class="flex-1">
						<RefreshCw class="h-3.5 w-3.5" />
						Retry
					</Button>
				</div>
			</div>
		{:else if wizardStep === 'select'}
			{#if discoveredServers.length === 0}
				<div class="flex flex-col items-center gap-3 rounded-xl border border-border bg-elevated p-5 text-center">
					<p class="font-heading text-sm font-semibold text-fg-primary">No Plex Servers Found</p>
					<p class="text-xs text-fg-secondary">
						This Plex account does not have any accessible Plex Media Server resources.
					</p>
					<Button variant="primary" size="md" onclick={startPlexOAuthWizard} class="w-full">
						Try Another Account
					</Button>
				</div>
			{:else}
				<div class="flex flex-col gap-3">
					<div class="flex flex-col gap-2">
						<span class="font-heading text-xs font-semibold text-fg-primary">1. Select Plex Server</span>
						{#each discoveredServers as srv (srv.machineId)}
							{@const active = srv.machineId === selectedMachineId}
							<button
								type="button"
								onclick={() => chooseServer(srv)}
								class={`flex flex-col gap-1 rounded-xl border p-3 text-left transition-colors cursor-pointer ${
									active ? 'border-cyan bg-cyan-soft/70 ring-1 ring-cyan' : 'border-border bg-elevated hover:border-border-strong'
								}`}
							>
								<div class="flex items-center justify-between gap-2">
									<span class="font-heading text-sm font-semibold text-fg-primary">{srv.name}</span>
									<div class="flex items-center gap-1">
										{#if srv.owned}
											<Badge tone="primary" mono>OWNED</Badge>
										{/if}
										<Badge tone={srv.presence ? 'direct-play' : 'neutral'} dot mono>
											{srv.presence ? 'ONLINE' : 'OFFLINE'}
										</Badge>
									</div>
								</div>
								<p class="font-mono text-[10px] text-fg-muted">
									{[srv.platform, srv.productVersion ? `v${srv.productVersion}` : null, srv.machineId]
										.filter(Boolean)
										.join(' · ')}
								</p>
							</button>
						{/each}
					</div>

					{#if selectedServer}
						<div class="flex flex-col gap-2">
							<span class="font-heading text-xs font-semibold text-fg-primary">2. Choose Connection URI</span>
							{#each selectedServer.connections as conn (conn.uri)}
								{@const checked = selectedConnectionUri === conn.uri}
								<label
									class={`flex cursor-pointer items-center justify-between gap-2 rounded-lg border px-3 py-2.5 text-xs ${
										checked ? 'border-cyan bg-cyan-soft/50' : 'border-border bg-elevated'
									}`}
								>
									<div class="flex min-w-0 items-center gap-2">
										<input
											type="radio"
											name="plex-connection-uri"
											value={conn.uri}
											checked={checked}
											onchange={() => (selectedConnectionUri = conn.uri)}
											class="accent-cyan"
										/>
										<span class="truncate font-mono text-[11px] text-fg-primary">{conn.uri}</span>
									</div>
									<div class="flex shrink-0 items-center gap-1">
										<Badge tone={conn.local ? 'direct-play' : conn.relay ? 'warning' : 'cyan'} mono>
											{connectionLabel(conn)}
										</Badge>
										<Badge tone="neutral" mono>{conn.protocol.toUpperCase()}</Badge>
									</div>
								</label>
							{/each}

							<label class="mt-1 flex flex-col gap-1">
								<span class="font-heading text-[11px] font-semibold text-fg-secondary">
									Or enter custom connection URI
								</span>
								<input
									type="url"
									bind:value={selectedConnectionUri}
									placeholder="http://192.168.1.50:32400"
									class="rounded-lg border border-input-border bg-canvas px-3 py-2 font-mono text-xs text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
								/>
							</label>
						</div>
					{/if}

					<Button
						variant="primary"
						size="lg"
						disabled={!selectedMachineId || !selectedConnectionUri.trim()}
						onclick={handleConfirmServerSelection}
						class="mt-1 w-full"
					>
						Save & Connect Server
					</Button>
				</div>
			{/if}
		{:else if wizardStep === 'done' && selectResult}
			<div class="flex flex-col items-center gap-3 rounded-xl border border-direct-play/50 bg-direct-play-soft p-5 text-center">
				<CheckCircle2 class="h-7 w-7 text-direct-play" />
				<div>
					<p class="font-heading text-sm font-semibold text-fg-primary">
						{selectResult.serverName} Saved
					</p>
					<p class="mt-1 font-mono text-[11px] text-fg-secondary">{selectResult.baseUrl}</p>
					<p class="mt-2 text-xs text-fg-secondary">
						{#if selectResult.envOverrideActive}
							Encrypted credentials were saved to the database, but static environment variables remain active.
						{:else if selectResult.hotReloaded}
							Miru hot-reloaded the live Plex pipeline without restarting.
						{:else}
							Credentials saved.
						{/if}
					</p>
				</div>
				<Button variant="primary" size="md" onclick={() => handleWizardOpenChange(false)} class="w-full">
					Done
				</Button>
			</div>
		{/if}
	</div>
</Sheet>

<!-- Create Account Sheet -->
<Sheet
	bind:open={createAccountOpen}
	onOpenChange={(v) => {
		createAccountOpen = v;
		if (!v) createAccountError = null;
	}}
	title="Create Local Account"
>
	<form onsubmit={handleCreateAccount} class="flex flex-col gap-3.5">
		{#if createAccountError}
			<div role="alert" class="rounded-lg border border-danger/50 bg-danger-soft px-3 py-2 text-xs text-danger">
				{createAccountError}
			</div>
		{/if}
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Username</span>
			<input
				type="text"
				required
				bind:value={newUsername}
				class="rounded-lg border border-input-border bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			/>
		</label>
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Email (optional)</span>
			<input
				type="email"
				bind:value={newEmail}
				class="rounded-lg border border-input-border bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			/>
		</label>
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Password</span>
			<input
				type="password"
				required
				minlength={12}
				bind:value={newPassword}
				class="rounded-lg border border-input-border bg-canvas px-3 py-2 font-mono text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			/>
			<span class="text-[10px] text-fg-muted">
				At least 12 characters with 1 lowercase, 1 uppercase, and 1 digit
			</span>
		</label>
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Role</span>
			<select
				bind:value={newRole}
				class="rounded-lg border border-input-border bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			>
				<option value="USER">USER (scoped to linked media users)</option>
				<option value="ADMIN">ADMIN (full access)</option>
			</select>
		</label>
		<Button type="submit" variant="primary" size="lg" disabled={accountSubmitting} class="mt-2 w-full">
			{accountSubmitting ? 'Creating…' : 'Create Account'}
		</Button>
	</form>
</Sheet>

<!-- Link Media Users Sheet -->
<Sheet
	bind:open={linkOpen}
	onOpenChange={(v) => {
		linkOpen = v;
		if (!v) linkError = null;
	}}
	title={`Link Media Users · ${linkTarget?.username ?? ''}`}
	description="Select which Plex users this account may view"
>
	<div class="flex flex-col gap-3">
		{#if linkError}
			<div role="alert" class="rounded-lg border border-danger/50 bg-danger-soft px-3 py-2 text-xs text-danger">
				{linkError}
			</div>
		{/if}
		{#if linkTarget?.authProvider === 'OIDC' && linkTarget.role !== 'ADMIN'}
			{#if !emailLinkingEnabled}
				<p class="rounded-lg border border-border bg-elevated px-3 py-2.5 text-[11px] text-fg-secondary">
					Automatic email linking is currently disabled, so SSO sign-in never changes these links.
				</p>
			{:else if linkTarget.mediaUserLinkMode === 'AUTO'}
				<p class="rounded-lg border border-border bg-elevated px-3 py-2.5 text-[11px] text-fg-secondary">
					Linked by email: this account is linked to the Plex users with the same verified email,
					updated when its sign-in email changes and after each hourly Plex user sync.
					{#if linkTarget.emailVerified === false}
						Its identity provider has not marked the email as verified, so it has no email links.
					{:else if linkTarget.emailVerified == null}
						Its email is checked at its next SSO sign-in.
					{/if}
					Saving a selection here switches it to manual linking.
				</p>
			{:else}
				<div class="flex flex-col gap-2 rounded-lg border border-border bg-elevated px-3 py-2.5">
					<p class="text-[11px] text-fg-secondary">
						Linked manually: Miru leaves these links as they are. Switching to email linking relinks
						the account right away if its email was checked at its last SSO sign-in, otherwise at its
						next one.
						{#if linkTarget.emailVerified === false}
							Its identity provider has not marked the email as verified, so switching will clear
							its current links.
						{/if}
					</p>
					<Button
						variant="outline"
						size="sm"
						disabled={linkSubmitting}
						onclick={handleUseEmailLinking}
						class="self-start"
					>
						Link by email instead
					</Button>
				</div>
			{/if}
		{/if}
		{#if mediaUsers.length === 0}
			<p class="text-xs text-fg-muted">No Plex media users synced yet.</p>
		{:else}
			<div class="flex flex-col gap-2">
				{#each mediaUsers as mu (mu.id)}
					{@const checked = selectedMediaUserIds.includes(mu.id)}
					<label class="flex cursor-pointer items-center justify-between rounded-lg border border-border bg-elevated px-3 py-2.5 text-xs">
						<span class="font-heading font-semibold text-fg-primary">
							{mu.displayName || mu.username}
							<span class="font-mono font-normal text-fg-muted"> (#{mu.externalUserId})</span>
						</span>
						<input
							type="checkbox"
							{checked}
							onchange={() => toggleMediaUser(mu.id)}
							class="h-4 w-4 accent-cyan"
						/>
					</label>
				{/each}
			</div>
		{/if}
		<Button variant="cyan" size="lg" disabled={linkSubmitting} onclick={handleSaveLinks} class="mt-2 w-full">
			{linkSubmitting ? 'Saving…' : 'Save Linked Users'}
		</Button>
	</div>
</Sheet>

<!-- Create API Key Sheet -->
<Sheet
	bind:open={createKeyOpen}
	onOpenChange={(v) => {
		createKeyOpen = v;
		if (!v) createKeyError = null;
	}}
	title="Create API Key"
>
	<form onsubmit={handleCreateKey} class="flex flex-col gap-3.5">
		{#if createKeyError}
			<div role="alert" class="rounded-lg border border-danger/50 bg-danger-soft px-3 py-2 text-xs text-danger">
				{createKeyError}
			</div>
		{/if}
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Key Name</span>
			<input
				type="text"
				required
				placeholder="e.g. Homepage Widget"
				bind:value={newKeyName}
				class="rounded-lg border border-input-border bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			/>
		</label>
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Role</span>
			<select
				bind:value={newKeyRole}
				class="rounded-lg border border-input-border bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			>
				<option value="USER">USER</option>
				<option value="ADMIN">ADMIN</option>
			</select>
		</label>
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Expiration</span>
			<select
				bind:value={newKeyExpiryPreset}
				class="rounded-lg border border-input-border bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
			>
				{#each API_KEY_EXPIRY_PRESETS as preset}
					<option value={preset.value}>{preset.label}</option>
				{/each}
			</select>
		</label>
		{#if newKeyExpiryPreset === 'custom'}
			<label class="flex flex-col gap-1">
				<span class="font-heading text-xs font-semibold text-fg-primary">Custom Expiration Date</span>
				<input
					type="date"
					required
					bind:value={newKeyCustomDate}
					class="rounded-lg border border-input-border bg-canvas px-3 py-2 font-mono text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
			</label>
		{/if}
		<Button type="submit" variant="cyan" size="lg" disabled={keySubmitting} class="mt-2 w-full">
			{keySubmitting ? 'Generating…' : 'Generate API Key'}
		</Button>
	</form>
</Sheet>

<!-- Configure Guardrail Rule Sheet -->
<Sheet
	bind:open={editRuleOpen}
	onOpenChange={(v) => {
		editRuleOpen = v;
		if (!v) editRuleError = null;
	}}
	title={editRuleTarget ? `Configure ${guardrailTitle(editRuleTarget.ruleType)}` : 'Configure Guardrail'}
	description="Adjust enforcement mode, threshold, and message displayed to viewers"
>
	{#if editRuleTarget}
		<form onsubmit={handleSaveGuardrail} class="flex flex-col gap-3.5">
			{#if editRuleError}
				<div role="alert" class="rounded-lg border border-danger/50 bg-danger-soft px-3 py-2 text-xs text-danger">
					{editRuleError}
				</div>
			{/if}

			<label class="flex flex-col gap-1">
				<span class="font-heading text-xs font-semibold text-fg-primary">Rule Mode</span>
				<select
					value={editRuleMode}
					onchange={(e) => {
						editRuleMode = e.currentTarget.value as GuardrailMode;
					}}
					class="rounded-lg border border-input-border bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				>
					<option value="DISABLED">DISABLED — Rule inactive</option>
					<option value="WARN">DRY-RUN WARN — Log &amp; audit only (do not kill)</option>
					<option value="ENFORCE">ENFORCE — Auto-terminate matching streams</option>
				</select>
			</label>

			{#if editRuleTarget.ruleType === 'PAUSED_TIMEOUT'}
				<label class="flex flex-col gap-1">
					<span class="font-heading text-xs font-semibold text-fg-primary">
						Paused Threshold (minutes)
					</span>
					<input
						type="number"
						min={1}
						max={1440}
						required
						bind:value={editRuleThreshold}
						class="rounded-lg border border-input-border bg-canvas px-3 py-2 font-mono text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
					/>
					<span class="text-[10px] text-fg-muted">
						Continuous paused minutes before triggering (1–1440; resets when resumed).
					</span>
				</label>
			{:else if editRuleTarget.ruleType === 'CONCURRENT_STREAMS'}
				<label class="flex flex-col gap-1">
					<span class="font-heading text-xs font-semibold text-fg-primary">
						Max Concurrent Streams Per User
					</span>
					<input
						type="number"
						min={1}
						max={50}
						required
						bind:value={editRuleThreshold}
						class="rounded-lg border border-input-border bg-canvas px-3 py-2 font-mono text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
					/>
					<span class="text-[10px] text-fg-muted">
						Oldest active streams are kept; newest excess streams are targeted (1–50).
					</span>
				</label>
			{/if}

			<div class="flex flex-col gap-1">
				<div class="flex items-center justify-between gap-2">
					<label for="guardrail-message-input" class="font-heading text-xs font-semibold text-fg-primary">
						Viewer Message
					</label>
					<button
						type="button"
						onclick={() => {
							if (editRuleTarget) editRuleMessage = editRuleTarget.defaultMessage;
						}}
						class="cursor-pointer font-heading text-[11px] font-medium text-cyan-hi hover:underline"
					>
						Reset to default
					</button>
				</div>
				<textarea
					id="guardrail-message-input"
					rows={3}
					maxlength={500}
					bind:value={editRuleMessage}
					class="rounded-lg border border-input-border bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				></textarea>
				<span class="text-[10px] text-fg-muted">
					Shown to the viewer on termination ({editRuleMessage.trim().length}/500).
				</span>
			</div>

			<Button type="submit" variant="primary" size="lg" disabled={editRuleSubmitting} class="mt-2 w-full">
				{editRuleSubmitting ? 'Saving…' : 'Save Guardrail Rule'}
			</Button>
		</form>
	{/if}
</Sheet>
