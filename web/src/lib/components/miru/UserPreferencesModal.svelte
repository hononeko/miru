<script lang="ts">
	import { goto } from '$app/navigation';
	import { Monitor, Moon, Sun, LogOut, X } from 'lucide-svelte';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { themeStore } from '$stores/theme.svelte';
	import { sheetDrag } from '$lib/actions/sheetDrag';
	import type {
		AnalyticsMetricPreference,
		DateFormatPreference,
		StatsRange,
		ThemePreference,
		TimeFormatPreference,
		UpdateUserPreferencesRequest
	} from '$api/types';
	import { cn, formatPreferencePreview, safeInitial } from '$lib/utils';

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
	let savedOnClose = false;
	let pressedOutside = false;

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
		preferencesStore.closeModal();
	}

	function handleCancel() {
		savedOnClose = false;
		themeStore.set(preferencesStore.prefs.theme);
		preferencesStore.closeModal();
	}

	function isOutsideDialog(e: PointerEvent, dialog: HTMLDialogElement): boolean {
		const r = dialog.getBoundingClientRect();
		return e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
	}

	function handlePointerDown(e: PointerEvent) {
		if (!dialogEl || 'closedBy' in HTMLDialogElement.prototype) return;
		pressedOutside = isOutsideDialog(e, dialogEl);
	}

	function handlePointerUp(e: PointerEvent) {
		if (!dialogEl || 'closedBy' in HTMLDialogElement.prototype) return;
		if (pressedOutside && isOutsideDialog(e, dialogEl)) {
			dialogEl.close();
		}
		pressedOutside = false;
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
	closedby="any"
	aria-labelledby="user-preferences-title"
	onclose={handleDialogClose}
	onpointerdown={handlePointerDown}
	onpointerup={handlePointerUp}
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
	<div class="flex items-start justify-between gap-3 border-b border-border px-5 py-4">
		<div class="flex min-w-0 items-center gap-3">
			<div
				class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary font-heading text-sm font-bold text-white"
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
		<button
			type="button"
			onclick={handleCancel}
			aria-label="Close preferences"
			class="inline-flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-lg text-fg-muted hover:bg-elevated hover:text-fg-primary"
		>
			<X class="h-4 w-4" />
		</button>
	</div>

	<!-- Preferences Form -->
	<form onsubmit={handleSave} class="flex min-h-0 flex-1 flex-col">
		<div class="flex flex-col gap-5 overflow-y-auto px-5 py-4">
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
							class={cn(
								'flex cursor-pointer items-center justify-center gap-1.5 rounded-lg px-3 py-2 font-heading text-xs font-semibold transition-colors',
								active
									? 'bg-surface text-fg-primary shadow-sm border border-border-strong'
									: 'text-fg-secondary hover:text-fg-primary border border-transparent'
							)}
						>
							<Icon class={cn('h-3.5 w-3.5', active ? 'text-primary' : 'text-fg-muted')} />
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
									class={cn(
										'cursor-pointer rounded-lg px-2.5 py-1.5 font-heading text-xs font-semibold transition-colors',
										active
											? 'bg-surface text-fg-primary shadow-sm border border-border-strong'
											: 'text-fg-secondary hover:text-fg-primary border border-transparent'
									)}
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
							class="h-[38px] rounded-xl border border-border bg-elevated px-3 text-xs font-medium text-fg-primary focus:border-cyan focus:outline-none"
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
								class={cn(
									'cursor-pointer rounded-lg px-2.5 py-1.5 font-mono text-xs font-semibold transition-colors',
									active
										? 'border border-cyan/60 bg-cyan-soft text-cyan-hi'
										: 'border border-transparent text-fg-secondary hover:text-fg-primary'
								)}
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
								class={cn(
									'cursor-pointer rounded-lg px-2.5 py-1.5 font-heading text-xs font-semibold transition-colors',
									active
										? 'border border-cyan/60 bg-cyan-soft text-cyan-hi'
										: 'border border-transparent text-fg-secondary hover:text-fg-primary'
								)}
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
								? 'border-cyan bg-cyan'
								: 'border-border-strong bg-subtle'
						)}
					>
						<span
							class={cn(
								'inline-block h-4 w-4 rounded-full bg-white shadow transition-transform',
								draft.relativeTimestamps ? 'translate-x-5.5' : 'translate-x-1'
							)}
						></span>
					</button>
				</div>
			</section>
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
</style>
