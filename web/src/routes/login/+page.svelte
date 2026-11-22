<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { LogIn } from 'lucide-svelte';
	import { MiruApiError } from '$api/client';
	import MiruLogo from '$components/layout/MiruLogo.svelte';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import { authStore } from '$stores/auth.svelte';

	let username = $state('');
	let password = $state('');
	let submitting = $state(false);
	let error = $state<string | null>(null);

	const queryErrorMessage = $derived.by(() => {
		const code = page.url.searchParams.get('error');
		if (!code) return null;
		switch (code) {
			case 'forbidden':
				return 'Your account is not a member of an authorized Miru group.';
			case 'invalid_state':
				return 'Your sign-in session expired or was invalid. Please try again.';
			case 'disabled':
				return 'Your Miru account has been disabled.';
			case 'oidc_error':
				return 'Single sign-on failed. Please try again or contact an administrator.';
			default:
				return 'Authentication failed. Please try again.';
		}
	});

	const displayedError = $derived(error ?? queryErrorMessage);

	const authFooterLabel = $derived.by(() => {
		if (authStore.oidcEnabled && authStore.localEnabled) {
			return 'HttpOnly Cookie · Local + OIDC';
		}
		if (authStore.oidcEnabled) {
			return 'HttpOnly Cookie · OIDC PKCE S256';
		}
		return 'HttpOnly Cookie · Argon2id';
	});

	const subtitleText = $derived.by(() => {
		if (authStore.oidcEnabled && authStore.localEnabled) {
			return `Sign in with ${authStore.oidcDisplayName} or local credentials`;
		}
		if (authStore.oidcEnabled) {
			return `Sign in with ${authStore.oidcDisplayName}`;
		}
		return 'Enter your local Miru account credentials';
	});

	async function handleSubmit(e: SubmitEvent) {
		e.preventDefault();
		error = null;
		submitting = true;
		try {
			await authStore.login(username.trim(), password);
			await goto('/');
		} catch (err) {
			error = err instanceof MiruApiError ? err.detail : 'Unable to sign in';
		} finally {
			submitting = false;
		}
	}
</script>

<svelte:head>
	<title>Sign In · Miru</title>
</svelte:head>

<div class="flex min-h-dvh flex-col items-center justify-center bg-canvas px-4 py-10">
	<div class="flex w-full max-w-[420px] flex-col items-center gap-6">
		<div class="flex flex-col items-center gap-2 text-center">
			<MiruLogo size={52} />
			<h1 class="font-display text-3xl font-bold tracking-wide text-fg-primary">Miru (見る)</h1>
			<p class="text-xs text-fg-secondary">Lightweight media server telemetry & analytics</p>
		</div>

		<div class="flex w-full flex-col gap-4 rounded-2xl border border-border bg-surface p-6 shadow-sm dark:shadow-none sm:p-7">
			<div class="flex items-start justify-between gap-2">
				<div>
					<h2 class="font-display text-2xl font-bold text-fg-primary">Sign in</h2>
					<p class="text-xs text-fg-secondary">{subtitleText}</p>
				</div>
				<Badge tone="cyan" mono>{authStore.oidcEnabled && !authStore.localEnabled ? 'SSO' : 'LOGIN'}</Badge>
			</div>

			{#if displayedError}
				<div role="alert" class="rounded-lg border border-danger/50 bg-danger-soft px-3 py-2 text-xs text-danger">
					{displayedError}
				</div>
			{/if}

			{#if authStore.oidcEnabled}
				<a
					href={authStore.oidcLoginUrl ?? '/api/v1/auth/oidc/login'}
					class="inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-cyan px-4 font-heading text-sm font-semibold text-white transition-colors hover:bg-cyan-hi focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-cyan/50"
				>
					<LogIn class="h-4 w-4" />
					Sign in with {authStore.oidcDisplayName}
				</a>
			{/if}

			{#if authStore.oidcEnabled && authStore.localEnabled}
				<div class="flex items-center gap-3">
					<div class="h-px flex-1 bg-border"></div>
					<span class="font-mono text-[10px] uppercase text-fg-muted">or local credentials</span>
					<div class="h-px flex-1 bg-border"></div>
				</div>
			{/if}

			{#if authStore.localEnabled}
				<form onsubmit={handleSubmit} class="flex flex-col gap-4">
					<label class="flex flex-col gap-1.5">
						<span class="font-heading text-xs font-semibold text-fg-primary">Username</span>
						<input
							type="text"
							name="username"
							autocomplete="username"
							required
							bind:value={username}
							placeholder="sergiu"
							class="rounded-lg border border-border-strong bg-canvas px-3 py-2.5 text-sm text-fg-primary placeholder:text-fg-muted focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan/30"
						/>
					</label>

					<label class="flex flex-col gap-1.5">
						<span class="font-heading text-xs font-semibold text-fg-primary">Password</span>
						<input
							type="password"
							name="password"
							autocomplete="current-password"
							required
							bind:value={password}
							placeholder="••••••••••••"
							class="rounded-lg border border-border-strong bg-canvas px-3 py-2.5 font-mono text-sm text-fg-primary placeholder:text-fg-muted focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan/30"
						/>
					</label>

					<Button type="submit" variant="primary" size="lg" disabled={submitting} class="mt-1 w-full">
						{submitting ? 'Signing in…' : 'Sign In'}
					</Button>
				</form>
			{/if}

			<div class="flex items-center justify-between rounded-lg bg-elevated px-3 py-2">
				<span class="font-mono text-[10px] text-fg-muted">{authFooterLabel}</span>
				<Badge tone="direct-play">PWA READY</Badge>
			</div>
		</div>
	</div>
</div>
