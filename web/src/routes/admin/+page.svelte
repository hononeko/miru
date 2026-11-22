<script lang="ts">
	import { onMount } from 'svelte';
	import { Copy, Check, KeyRound, Plus, Trash2, Link2, Upload, FileSpreadsheet, ChevronDown } from 'lucide-svelte';
	import { api, MiruApiError, unwrap } from '$api/client';
	import type {
		AccountResponse,
		ApiKeyResponse,
		MiruRole,
		ProblemDetails,
		ReadinessResponse,
		TautulliImportResponse,
		UserSummaryDto
	} from '$api/types';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import Sheet from '$components/ui/Sheet.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { formatBytes, formatRelativeTime, safeInitial } from '$lib/utils';

	let accounts = $state<AccountResponse[]>([]);
	let apiKeys = $state<ApiKeyResponse[]>([]);
	let mediaUsers = $state<UserSummaryDto[]>([]);
	let readiness = $state<ReadinessResponse | null>(null);
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

	// Link Media Users Sheet
	let linkTarget = $state<AccountResponse | null>(null);
	let linkOpen = $state(false);
	let selectedMediaUserIds = $state<string[]>([]);
	let linkSubmitting = $state(false);

	// Create API Key Sheet
	let createKeyOpen = $state(false);
	let newKeyName = $state('');
	let newKeyRole = $state<MiruRole>('USER');
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

	async function loadAll() {
		loading = true;
		error = null;
		try {
			const [accRes, keyRes, usrRes, readyRes] = await Promise.all([
				api.GET('/api/v1/admin/accounts'),
				api.GET('/api/v1/admin/api-keys'),
				api.GET('/api/v1/users'),
				api.GET('/readyz')
			]);
			accounts = unwrap(accRes).accounts;
			apiKeys = unwrap(keyRes).apiKeys;
			mediaUsers = usrRes.data?.users ?? [];
			readiness = readyRes.data ?? null;
		} catch (e) {
			error = e instanceof MiruApiError ? e.detail : 'Failed to load admin console';
		} finally {
			loading = false;
		}
	}

	onMount(() => {
		void loadAll();
	});

	async function handleCreateAccount(e: SubmitEvent) {
		e.preventDefault();
		error = null;
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
			error = err instanceof MiruApiError ? err.detail : 'Failed to create account';
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
		keySubmitting = true;
		error = null;
		try {
			const res = unwrap(
				await api.POST('/api/v1/admin/api-keys', {
					body: {
						name: newKeyName.trim(),
						role: newKeyRole
					}
				})
			);
			createdRawKey = res.rawKey;
			copied = false;
			createKeyOpen = false;
			newKeyName = '';
			newKeyRole = 'USER';
			await loadAll();
		} catch (err) {
			error = err instanceof MiruApiError ? err.detail : 'Failed to create API key';
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
				Manage local Miru accounts, RBAC Plex media-user links, and headless API keys
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
					{@const revoked = Boolean(key.revokedAtMs)}
					<div class="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-elevated px-3.5 py-2.5">
						<div>
							<p class={revoked ? 'font-heading text-xs font-semibold text-fg-muted line-through' : 'font-heading text-xs font-semibold text-fg-primary'}>
								{key.name}
							</p>
							<p class="font-mono text-[10px] text-fg-muted">
								{key.keyPrefix}… · {key.role} · {revoked ? `Revoked ${formatRelativeTime(key.revokedAtMs)}` : `Last used ${formatRelativeTime(key.lastUsedAtMs)}`}
							</p>
						</div>
						<div class="flex items-center gap-2">
							<Badge tone={revoked ? 'danger' : 'direct-play'} dot>
								{revoked ? 'REVOKED' : 'ACTIVE'}
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

<!-- Create Account Sheet -->
<Sheet bind:open={createAccountOpen} onOpenChange={(v) => (createAccountOpen = v)} title="Create Local Account">
	<form onsubmit={handleCreateAccount} class="flex flex-col gap-3.5">
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
			<span class="font-heading text-xs font-semibold text-fg-primary">Password (min 12 chars)</span>
			<input
				type="password"
				required
				minlength={12}
				bind:value={newPassword}
				class="rounded-lg border border-border-strong bg-canvas px-3 py-2 font-mono text-sm text-fg-primary focus:border-cyan focus:outline-none"
			/>
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
<Sheet bind:open={createKeyOpen} onOpenChange={(v) => (createKeyOpen = v)} title="Create API Key">
	<form onsubmit={handleCreateKey} class="flex flex-col gap-3.5">
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
		<Button type="submit" variant="cyan" size="lg" disabled={keySubmitting} class="mt-2 w-full">
			{keySubmitting ? 'Generating…' : 'Generate API Key'}
		</Button>
	</form>
</Sheet>
