<script lang="ts">
	import { goto } from '$app/navigation';
	import { Monitor, Moon, Sun, LogOut } from 'lucide-svelte';
	import { api, MiruApiError } from '$api/client';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { themeStore } from '$stores/theme.svelte';
	import { modalDialog } from '$lib/actions/modalDialog';
	import { sheetDrag } from '$lib/actions/sheetDrag';
	import type {
		AnalyticsMetricPreference,
		DateFormatPreference,
		StatsRange,
		ThemePreference,
		TimeFormatPreference,
		UpdateUserPreferencesRequest
	} from '$api/types';
	import { cn, formatPreferencePreview, safeInitial, validatePassword } from '$lib/utils';

	let dialogEl: HTMLDialogElement | undefined = $state();
	let draft = $state<UpdateUserPreferencesRequest>({
		theme: preferencesStore.prefs.theme,
		timeFormat: preferencesStore.prefs.timeFormat,
		dateFormat: preferencesStore.prefs.dateFormat,
		defaultStatsRange: preferencesStore.prefs.defaultStatsRange,
		relativeTimestamps: preferencesStore.prefs.relativeTimestamps,
		analyticsMetric: preferencesStore.prefs.analyticsMetric
	});
	let previewSampleMs = $state(Date.now());
	let saveError = $state<string | null>(null);
	let currentPassword = $state('');
	let newPassword = $state('');
	let confirmNewPassword = $state('');
	let passwordSubmitting = $state(false);
	let passwordError = $state<string | null>(null);
	let passwordSuccess = $state<string | null>(null);
	let savedOnClose = false;

	const canChangePassword = $derived(
		authStore.authMode !== 'none' &&
			authStore.authenticated &&
			authStore.principal?.authSource !== 'API_KEY' &&
			authStore.principal?.authProvider === 'LOCAL'
	);

	const THEME_OPTIONS: { value: ThemePreference; label: string; icon: typeof Monitor }[] = [
		{ value: 'system', label: 'System', icon: Monitor },
		{ value: 'dark', label: 'Dark', icon: Moon },
		{ value: 'light', label: 'Light', icon: Sun }
	];

	const TIME_OPTIONS: { value: TimeFormatPreference; label: string }[] = [
		{ value: '24h', label: '24-hour' },
		{ value: '12h', label: '12-hour' }
	];

	const DATE_OPTIONS: { value: DateFormatPreference; label: string }[] = [
		{ value: 'iso', label: 'YYYY-MM-DD (ISO)' },
		{ value: 'mdy', label: 'MMM D, YYYY (US)' },
		{ value: 'dmy', label: 'DD/MM/YYYY (EU)' }
	];

	const RANGE_OPTIONS: StatsRange[] = ['7d', '30d', '90d', 'all'];

	const ANALYTICS_METRIC_OPTIONS: { value: AnalyticsMetricPreference; label: string }[] = [
		{ value: 'plays', label: 'Number of Plays' },
		{ value: 'duration', label: 'Playback Duration' }
	];

	function choiceClass(active: boolean): string {
		return cn(
			'cursor-pointer rounded-lg border px-2.5 py-1.5 font-heading text-xs font-semibold transition-colors',
			active
				? 'border-border-strong bg-surface text-fg-primary shadow-sm'
				: 'border-transparent text-fg-secondary hover:text-fg-primary'
		);
	}

	const previewText = $derived(formatPreferencePreview(draft, previewSampleMs));

	function syncDraftFromStore() {
		draft = {
			theme: preferencesStore.prefs.theme,
			timeFormat: preferencesStore.prefs.timeFormat,
			dateFormat: preferencesStore.prefs.dateFormat,
			defaultStatsRange: preferencesStore.prefs.defaultStatsRange,
			relativeTimestamps: preferencesStore.prefs.relativeTimestamps,
			analyticsMetric: preferencesStore.prefs.analyticsMetric
		};
		previewSampleMs = Date.now();
		saveError = null;
		currentPassword = '';
		newPassword = '';
		confirmNewPassword = '';
		passwordError = null;
		passwordSuccess = null;
	}

	$effect(() => {
		if (!dialogEl) return;
		if (preferencesStore.modalOpen) {
			syncDraftFromStore();
			savedOnClose = false;
			if (!dialogEl.open) {
				dialogEl.showModal();
			}
		} else if (dialogEl.open) {
			dialogEl.close();
		}
	});

	function selectTheme(nextTheme: ThemePreference) {
		draft.theme = nextTheme;
		themeStore.set(nextTheme);
	}

	function handleDialogClose() {
		if (dialogEl) {
			dialogEl.returnValue = '';
		}
		if (!savedOnClose) {
			themeStore.set(preferencesStore.prefs.theme);
		}
		saveError = null;
		currentPassword = '';
		newPassword = '';
		confirmNewPassword = '';
		passwordError = null;
		passwordSuccess = null;
		preferencesStore.closeModal();
	}

	function handleCancel() {
		savedOnClose = false;
		themeStore.set(preferencesStore.prefs.theme);
		preferencesStore.closeModal();
	}

	async function handleSave(e: SubmitEvent) {
		e.preventDefault();
		saveError = null;
		try {
			await preferencesStore.save(draft);
			savedOnClose = true;
			preferencesStore.closeModal();
		} catch (err) {
			saveError = err instanceof Error ? err.message : 'Failed to save preferences';
		}
	}

	async function handleChangePassword() {
		passwordError = null;
		passwordSuccess = null;
		if (!currentPassword) {
			passwordError = 'Current password is required.';
			return;
		}
		const validationMsg = validatePassword(newPassword);
		if (validationMsg) {
			passwordError = validationMsg;
			return;
		}
		if (newPassword !== confirmNewPassword) {
			passwordError = 'New passwords do not match.';
			return;
		}
		passwordSubmitting = true;
		try {
			const res = await api.POST('/api/v1/auth/me/password', {
				body: {
					currentPassword,
					newPassword
				}
			});
			if (res.error) {
				throw new MiruApiError(res.response.status, res.error);
			}
			currentPassword = '';
			newPassword = '';
			confirmNewPassword = '';
			passwordSuccess = 'Password updated. Other active sessions have been signed out.';
		} catch (err) {
			passwordError = err instanceof MiruApiError ? err.detail : 'Failed to update password';
		} finally {
			passwordSubmitting = false;
		}
	}

	async function handleSignOut() {
		savedOnClose = false;
		themeStore.set(preferencesStore.prefs.theme);
		preferencesStore.closeModal();
		const redirectsToOidc = authStore.oidcEnabled && Boolean(authStore.oidcLogoutUrl);
		await authStore.logout();
		if (!redirectsToOidc) {
			await goto('/login');
		}
	}
</script>

<dialog
	bind:this={dialogEl}
	aria-labelledby="user-preferences-title"
	onclose={handleDialogClose}
	use:modalDialog
	use:sheetDrag={{ onDismiss: handleCancel }}
	class="m-0 mt-auto w-full max-w-none rounded-t-2xl border-t border-border-strong bg-surface p-0 text-fg-primary shadow-2xl open:flex open:flex-col max-h-[90dvh] md:m-auto md:w-full md:max-w-lg md:rounded-2xl md:border"
>
	<div
		data-sheet-drag-handle
		class="flex shrink-0 cursor-grab touch-none justify-center pt-2.5 pb-1 active:cursor-grabbing md:hidden"
	>
		<div class="h-1 w-10 rounded-full bg-border-strong"></div>
	</div>

	<!-- Account Summary Header -->
	<div class="flex items-start gap-3 border-b border-border px-5 py-4">
		<div class="flex min-w-0 items-center gap-3">
			<div
				class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary font-heading text-sm font-bold text-primary-fg"
			>
				{safeInitial(authStore.principal?.username)}
			</div>
			<div class="min-w-0">
				<div class="flex flex-wrap items-center gap-2">
					<h2 id="user-preferences-title" class="truncate font-heading text-base font-semibold text-fg-primary">
						{authStore.principal?.username ?? 'Guest'}
					</h2>
					<Badge tone={authStore.principal?.role === 'ADMIN' ? 'primary' : 'neutral'}>
						{authStore.principal?.role ?? 'USER'}
					</Badge>
				</div>
				<p class="mt-0.5 truncate text-xs text-fg-secondary">
					{#if authStore.principal?.email}
						{authStore.principal.email}
					{:else if authStore.authMode === 'none'}
						Local dev bypass session
					{:else}
						Preferences & display settings
					{/if}
				</p>
			</div>
		</div>
	</div>

	<!-- Preferences form: flex-auto, not flex-1, because WebKit before 26.4 resolves a 0% basis to 0px in an
	auto-height dialog and collapses the modal -->
	<form onsubmit={handleSave} class="flex min-h-0 flex-auto flex-col">
		<div data-modal-body class="flex min-h-0 flex-col gap-5 overflow-y-auto overscroll-contain px-5 py-4">
			{#if saveError}
				<div class="rounded-xl border border-danger/50 bg-danger-soft px-3.5 py-2.5 text-xs text-danger">
					{saveError}
				</div>
			{/if}

			<!-- Appearance -->
			<section class="flex flex-col gap-2">
				<div class="flex items-center justify-between gap-2">
					<span class="font-heading text-xs font-semibold uppercase tracking-wider text-fg-secondary">
						Appearance
					</span>
					{#if draft.theme === 'system'}
						<span class="font-mono text-[11px] text-fg-muted">
							System ({themeStore.systemTheme})
						</span>
					{/if}
				</div>
				<div
					role="radiogroup"
					aria-label="Color theme"
					class="grid grid-cols-3 gap-1 rounded-xl border border-border bg-elevated p-1"
				>
					{#each THEME_OPTIONS as opt}
						{@const active = draft.theme === opt.value}
						{@const Icon = opt.icon}
						<button
							type="button"
							role="radio"
							aria-checked={active}
							onclick={() => selectTheme(opt.value)}
							class={cn(choiceClass(active), 'flex items-center justify-center gap-1.5 px-3 py-2')}
						>
							<Icon class={cn('h-3.5 w-3.5', active ? 'text-primary-text' : 'text-fg-muted')} />
							{opt.label}
						</button>
					{/each}
				</div>
			</section>

			<!-- Date & Time Format -->
			<section class="flex flex-col gap-3">
				<span class="font-heading text-xs font-semibold uppercase tracking-wider text-fg-secondary">
					Date & Time Format
				</span>

				<div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
					<div class="flex flex-col gap-1.5">
						<span class="text-xs font-medium text-fg-secondary">Time Format</span>
						<div
							role="radiogroup"
							aria-label="Time format"
							class="grid grid-cols-2 gap-1 rounded-xl border border-border bg-elevated p-1"
						>
							{#each TIME_OPTIONS as opt}
								{@const active = draft.timeFormat === opt.value}
								<button
									type="button"
									role="radio"
									aria-checked={active}
									onclick={() => (draft.timeFormat = opt.value)}
									class={choiceClass(active)}
								>
									{opt.label}
								</button>
							{/each}
						</div>
					</div>

					<label class="flex flex-col gap-1.5">
						<span class="text-xs font-medium text-fg-secondary">Date Layout</span>
						<select
							bind:value={draft.dateFormat}
							aria-label="Date layout"
							class="h-[38px] rounded-xl border border-input-border bg-elevated px-3 text-xs font-medium text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
						>
							{#each DATE_OPTIONS as opt}
								<option value={opt.value}>{opt.label}</option>
							{/each}
						</select>
					</label>
				</div>

				<div class="flex items-center justify-between gap-2 rounded-xl border border-border bg-elevated/70 px-3.5 py-2.5">
					<span class="text-[11px] font-medium text-fg-muted">Preview</span>
					<span class="font-mono text-xs font-semibold text-cyan-hi tabular-nums">
						{previewText}
					</span>
				</div>
			</section>

			<!-- Dashboard Defaults -->
			<section class="flex flex-col gap-3">
				<span class="font-heading text-xs font-semibold uppercase tracking-wider text-fg-secondary">
					Dashboard Defaults
				</span>

				<div class="flex flex-col gap-1.5">
					<span class="text-xs font-medium text-fg-secondary">Default Stats Range</span>
					<div
						role="radiogroup"
						aria-label="Default stats range"
						class="grid grid-cols-4 gap-1 rounded-xl border border-border bg-elevated p-1"
					>
						{#each RANGE_OPTIONS as rangeOpt}
							{@const active = draft.defaultStatsRange === rangeOpt}
							<button
								type="button"
								role="radio"
								aria-checked={active}
								onclick={() => (draft.defaultStatsRange = rangeOpt)}
								class={choiceClass(active)}
							>
								{rangeOpt}
							</button>
						{/each}
					</div>
				</div>

				<div class="flex flex-col gap-1.5">
					<div class="flex items-center justify-between gap-2">
						<span class="text-xs font-medium text-fg-secondary">Advanced Analytics Calculation</span>
						<span class="font-mono text-[11px] text-fg-muted">
							{draft.analyticsMetric === 'duration' ? 'By watch time' : 'By play count'}
						</span>
					</div>
					<div
						role="radiogroup"
						aria-label="Advanced analytics metric"
						class="grid grid-cols-2 gap-1 rounded-xl border border-border bg-elevated p-1"
					>
						{#each ANALYTICS_METRIC_OPTIONS as metricOpt}
							{@const active = draft.analyticsMetric === metricOpt.value}
							<button
								type="button"
								role="radio"
								aria-checked={active}
								onclick={() => (draft.analyticsMetric = metricOpt.value)}
								class={choiceClass(active)}
							>
								{metricOpt.label}
							</button>
						{/each}
					</div>
					<p class="text-[11px] text-fg-muted">
						Controls whether heatmaps, client/codec shares, rankings, and Wrapped peaks are weighted by play count or playback duration
					</p>
				</div>

				<div class="flex items-center justify-between gap-3 rounded-xl border border-border bg-elevated px-3.5 py-3">
					<div class="min-w-0">
						<p id="relative-ts-label" class="text-xs font-semibold text-fg-primary">
							Relative Timestamps
						</p>
						<p class="text-[11px] text-fg-muted">
							Show "5m ago" alongside or instead of absolute timestamps
						</p>
					</div>
					<button
						type="button"
						role="switch"
						aria-checked={draft.relativeTimestamps}
						aria-labelledby="relative-ts-label"
						onclick={() => (draft.relativeTimestamps = !draft.relativeTimestamps)}
						class={cn(
							'relative inline-flex h-6 w-11 shrink-0 cursor-pointer items-center rounded-full border transition-colors',
							draft.relativeTimestamps
								? 'border-switch-on bg-switch-on'
								: 'border-border-strong bg-subtle'
						)}
					>
						<span
							class={cn(
								'inline-block h-4 w-4 rounded-full shadow transition-transform duration-200 ease-[var(--ease-out-quart)]',
								draft.relativeTimestamps ? 'translate-x-5.5 bg-switch-knob-on' : 'translate-x-1 bg-white'
							)}
						></span>
					</button>
				</div>
			</section>

			{#if canChangePassword}
				<section aria-label="Change Password" class="flex flex-col gap-3 border-t border-border pt-4">
					<div class="flex items-center justify-between gap-2">
						<span class="font-heading text-xs font-semibold uppercase tracking-wider text-fg-secondary">
							Change Password
						</span>
						<span class="font-mono text-[10px] text-fg-muted">Local account</span>
					</div>

					{#if passwordError}
						<div role="alert" class="rounded-xl border border-danger/50 bg-danger-soft px-3.5 py-2.5 text-xs text-danger">
							{passwordError}
						</div>
					{/if}
					{#if passwordSuccess}
						<div role="status" class="rounded-xl border border-direct-play/50 bg-direct-play-soft px-3.5 py-2.5 text-xs text-direct-play">
							{passwordSuccess}
						</div>
					{/if}

					<div class="flex flex-col gap-2.5 rounded-xl border border-border bg-elevated p-3.5">
						<label class="flex flex-col gap-1">
							<span class="text-xs font-medium text-fg-secondary">Current Password</span>
							<input
								type="password"
								autocomplete="current-password"
								bind:value={currentPassword}
								class="rounded-lg border border-input-border bg-canvas px-3 py-2 font-mono text-xs text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
							/>
						</label>
						<div class="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
							<label class="flex flex-col gap-1">
								<span class="text-xs font-medium text-fg-secondary">New Password</span>
								<input
									type="password"
									autocomplete="new-password"
									bind:value={newPassword}
									class="rounded-lg border border-input-border bg-canvas px-3 py-2 font-mono text-xs text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
								/>
							</label>
							<label class="flex flex-col gap-1">
								<span class="text-xs font-medium text-fg-secondary">Confirm New Password</span>
								<input
									type="password"
									autocomplete="new-password"
									bind:value={confirmNewPassword}
									class="rounded-lg border border-input-border bg-canvas px-3 py-2 font-mono text-xs text-fg-primary focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
								/>
							</label>
						</div>
						<div class="flex flex-wrap items-center justify-between gap-2 pt-0.5">
							<span class="text-[10px] text-fg-muted">
								Min 12 chars with lowercase, uppercase, and digit
							</span>
							<Button
								type="button"
								variant="outline"
								size="sm"
								disabled={passwordSubmitting}
								onclick={handleChangePassword}
							>
								{passwordSubmitting ? 'Updating…' : 'Update Password'}
							</Button>
						</div>
					</div>
				</section>
			{/if}
		</div>

		<!-- Footer -->
		<div class="flex flex-wrap items-center justify-between gap-2 border-t border-border px-5 py-3.5 safe-pb">
			<div>
				{#if authStore.authMode !== 'none' && authStore.authenticated}
					<Button variant="destructive" size="sm" onclick={handleSignOut}>
						<LogOut class="h-3.5 w-3.5" />
						Sign Out
					</Button>
				{/if}
			</div>
			<div class="flex items-center gap-2">
				<Button variant="ghost" size="sm" onclick={handleCancel}>
					Cancel
				</Button>
				<Button type="submit" variant="primary" size="sm" disabled={preferencesStore.saving}>
					{preferencesStore.saving ? 'Saving…' : 'Save Preferences'}
				</Button>
			</div>
		</div>
	</form>
</dialog>

<style>
	dialog:modal {
		overscroll-behavior: contain;
	}

	dialog::backdrop {
		background-color: rgb(0 0 0 / 0.6);
		backdrop-filter: blur(2px);
		overflow: hidden;
		overscroll-behavior: contain;
	}

	dialog[open] {
		animation: miru-sheet-up 230ms var(--ease-out-quart) backwards;
	}

	dialog[open]::backdrop {
		animation: miru-fade-in 180ms var(--ease-out-quart) backwards;
	}

	@media (min-width: 768px) {
		dialog[open] {
			animation: miru-dialog-in 210ms var(--ease-out-quart) backwards;
		}
	}

	@media (prefers-reduced-motion: reduce) {
		dialog[open],
		dialog[open]::backdrop {
			animation: none;
		}
	}
</style>
