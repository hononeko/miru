<script lang="ts">
	import { goto } from '$app/navigation';
	import { MiruApiError } from '$api/client';
	import MiruLogo from '$components/layout/MiruLogo.svelte';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import { authStore } from '$stores/auth.svelte';

	let username = $state('');
	let password = $state('');
	let submitting = $state(false);
	let error = $state<string | null>(null);

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

		<form
			onsubmit={handleSubmit}
			class="flex w-full flex-col gap-4 rounded-2xl border border-border bg-surface p-6 shadow-sm dark:shadow-none sm:p-7"
		>
			<div class="flex items-start justify-between gap-2">
				<div>
					<h2 class="font-display text-2xl font-bold text-fg-primary">Sign in</h2>
					<p class="text-xs text-fg-secondary">Enter your local Miru account credentials</p>
				</div>
				<Badge tone="cyan" mono>LOGIN</Badge>
			</div>

			{#if error}
				<div role="alert" class="rounded-lg border border-danger/50 bg-danger-soft px-3 py-2 text-xs text-danger">
					{error}
				</div>
			{/if}

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

			<div class="flex items-center justify-between rounded-lg bg-elevated px-3 py-2">
				<span class="font-mono text-[10px] text-fg-muted">HttpOnly Cookie · Argon2id</span>
				<Badge tone="direct-play">PWA READY</Badge>
			</div>
		</form>
	</div>
</div>
