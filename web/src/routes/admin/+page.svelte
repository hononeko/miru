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
		Trash2,
		Upload
	} from 'lucide-svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type {
		AccountResponse,
		ApiKeyResponse,
		MiruRole,
		PlexConnectionOptionDto,
		PlexConnectionStatus,
		PlexDiscoveredServerDto,
		PlexSelectServerResponse,
		ProblemDetails,
		ReadinessResponse,
		TautulliImportResponse,
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
		formatRelativeTime,
		getApiKeyStatus,
		resolveApiKeyExpiresAtMs,
		safeInitial,
		validatePassword,
		type ApiKeyExpiryPreset
	} from '$lib/utils';

	type WizardStep = 'starting' | 'waiting' | 'expired' | 'discovering' | 'select' | 'saving' | 'done';

	let accounts = $state<AccountResponse[]>([]);
	let apiKeys = $state<ApiKeyResponse[]>([]);
	let mediaUsers = $state<UserSummaryDto[]>([]);
	let readiness = $state<ReadinessResponse | null>(null);
	let plexStatus = $state<PlexConnectionStatus | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

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
		try {
			const [accRes, keyRes, usrRes, readyRes, plexRes] = await Promise.all([
				api.GET('/api/v1/admin/accounts'),
				api.GET('/api/v1/admin/api-keys'),
				api.GET('/api/v1/users'),
				api.GET('/readyz'),
				api.GET('/api/v1/admin/plex/status')
			]);
			accounts = unwrap(accRes).accounts;
			apiKeys = unwrap(keyRes).apiKeys;
			mediaUsers = usrRes.data?.users ?? [];
			readiness = readyRes.data ?? null;
			plexStatus = plexRes.data ?? null;
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Failed to load admin console';
		} finally {
			loading = false;
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
		error = null;
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
			error = err instanceof MiruApiError ? err.detail : 'Failed to update linked media users';
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
				<Server class="h-4 w-4 text-primary" />
				<h2 class="font-heading text-sm font-semibold text-fg-primary">
					Media Server · Plex Connection
				</h2>
			</div>
			{#if plexStatus}
				<div class="flex flex-wrap items-center gap-1.5">
					<Badge
						tone={plexStatus.reachable ? 'direct-play' : plexStatus.configured ? 'hw-transcode' : 'neutral'}
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
				<div class="flex items-start gap-2.5 rounded-xl border border-hw-transcode/50 bg-hw-transcode-soft px-3.5 py-2.5 text-xs text-fg-secondary">
					<AlertTriangle class="mt-0.5 h-4 w-4 shrink-0 text-hw-transcode" />
					<div>
						<p class="font-heading font-semibold text-hw-transcode">MIRU_ENCRYPTION_KEY is not configured</p>
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
									? 'flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-primary bg-primary-soft font-heading text-xs font-bold text-primary-hover'
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
							<Badge tone={revoked ? 'danger' : expired ? 'hw-transcode' : 'direct-play'} dot>
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
				<Loader2 class="h-6 w-6 animate-spin text-primary" />
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
						class="inline-flex items-center justify-center gap-2 rounded-lg bg-primary px-3 py-2 text-xs font-semibold text-white hover:bg-primary-hover"
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
				<AlertTriangle class="h-6 w-6 text-hw-transcode" />
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
										<Badge tone={conn.local ? 'direct-play' : conn.relay ? 'hw-transcode' : 'cyan'} mono>
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
									class="rounded-lg border border-border-strong bg-canvas px-3 py-2 font-mono text-xs text-fg-primary focus:border-cyan focus:outline-none"
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
				class="rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none"
			/>
		</label>
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Email (optional)</span>
			<input
				type="email"
				bind:value={newEmail}
				class="rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none"
			/>
		</label>
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Password</span>
			<input
				type="password"
				required
				minlength={12}
				bind:value={newPassword}
				class="rounded-lg border border-border-strong bg-canvas px-3 py-2 font-mono text-sm text-fg-primary focus:border-cyan focus:outline-none"
			/>
			<span class="text-[10px] text-fg-muted">
				At least 12 characters with 1 lowercase, 1 uppercase, and 1 digit
			</span>
		</label>
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Role</span>
			<select
				bind:value={newRole}
				class="rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none"
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
	onOpenChange={(v) => (linkOpen = v)}
	title={`Link Media Users · ${linkTarget?.username ?? ''}`}
	description="Select which Plex users this account may view"
>
	<div class="flex flex-col gap-3">
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
				class="rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none"
			/>
		</label>
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Role</span>
			<select
				bind:value={newKeyRole}
				class="rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none"
			>
				<option value="USER">USER</option>
				<option value="ADMIN">ADMIN</option>
			</select>
		</label>
		<label class="flex flex-col gap-1">
			<span class="font-heading text-xs font-semibold text-fg-primary">Expiration</span>
			<select
				bind:value={newKeyExpiryPreset}
				class="rounded-lg border border-border-strong bg-canvas px-3 py-2 text-sm text-fg-primary focus:border-cyan focus:outline-none"
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
					class="rounded-lg border border-border-strong bg-canvas px-3 py-2 font-mono text-sm text-fg-primary focus:border-cyan focus:outline-none"
				/>
			</label>
		{/if}
		<Button type="submit" variant="cyan" size="lg" disabled={keySubmitting} class="mt-2 w-full">
			{keySubmitting ? 'Generating…' : 'Generate API Key'}
		</Button>
	</form>
</Sheet>
