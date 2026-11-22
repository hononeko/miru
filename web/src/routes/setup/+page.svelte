<script lang="ts">
	import { goto } from '$app/navigation';
	import { MiruApiError } from '$api/client';
	import MiruLogo from '$components/layout/MiruLogo.svelte';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import { authStore } from '$stores/auth.svelte';

	let username = $state('admin');
	let email = $state('');
	let password = $state('');
	let confirmPassword = $state('');
	let submitting = $state(false);
	let error = $state<string | null>(null);

	async function handleSubmit(e: SubmitEvent) {
		e.preventDefault();
		error = null;

		if (password.length < 12) {
			error = 'Password must be at least 12 characters long.';
			return;
		}
		if (password !== confirmPassword) {
			error = 'Passwords do not match.';
			return;
		}

		submitting = true;
		try {
			await authStore.setupAdmin(username.trim(), password, email);
			await goto('/');
		} catch (err) {
			error = err instanceof MiruApiError ? err.detail : 'Failed to create initial administrator account';
		} finally {
			submitting = false;
		}
	}
</script>

<svelte:head>
	<title>First-Run Setup · Miru</title>
</svelte:head>

<div class="flex min-h-dvh flex-col items-center justify-center bg-canvas px-4 py-10">
	<div class="flex w-full max-w-[440px] flex-col items-center gap-6">
		<div class="flex flex-col items-center gap-2 text-center">
			<MiruLogo size={52} />
			<h1 class="font-display text-3xl font-bold tracking-wide text-fg-primary">Miru (見る)</h1>
			<p class="text-xs text-fg-secondary">First-run local administrator bootstrap</p>
		</div>

		<form
			onsubmit={handleSubmit}
			class="flex w-full flex-col gap-4 rounded-2xl border border-primary/70 bg-surface p-6 shadow-sm dark:shadow-none sm:p-7"
		>
			<div class="flex items-start justify-between gap-2">
				<div>
					<h2 class="font-display text-2xl font-bold text-fg-primary">First-Run Setup</h2>
					<p class="text-xs text-fg-secondary">Create the initial Miru admin account</p>
				</div>
				<Badge tone="primary">SETUP REQUIRED</Badge>
			</div>

			{#if error}
				<div role="alert" class="rounded-lg border border-danger/50 bg-danger-soft px-3 py-2 text-xs text-danger">
					{error}
				</div>
			{/if}

			<label class="flex flex-col gap-1.5">
				<span class="font-heading text-xs font-semibold text-fg-primary">Admin Username</span>
				<input
					type="text"
					required
					autocomplete="username"
					bind:value={username}
					placeholder="admin"
					class="rounded-lg border border-border-strong bg-canvas px-3 py-2.5 text-sm text-fg-primary placeholder:text-fg-muted focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan/30"
				/>
			</label>

			<label class="flex flex-col gap-1.5">
				<span class="font-heading text-xs font-semibold text-fg-primary">Email (optional)</span>
				<input
					type="email"
					autocomplete="email"
					bind:value={email}
					placeholder="admin@example.com"
					class="rounded-lg border border-border-strong bg-canvas px-3 py-2.5 text-sm text-fg-primary placeholder:text-fg-muted focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan/30"
				/>
			</label>

			<label class="flex flex-col gap-1.5">
				<span class="font-heading text-xs font-semibold text-fg-primary">Password</span>
				<input
					type="password"
					required
					minlength={12}
					autocomplete="new-password"
					bind:value={password}
					placeholder="Minimum 12 characters"
					class="rounded-lg border border-border-strong bg-canvas px-3 py-2.5 font-mono text-sm text-fg-primary placeholder:text-fg-muted focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan/30"
				/>
				<span class="text-[10px] text-fg-muted">Minimum 12 characters · hashed with Argon2id</span>
			</label>

			<label class="flex flex-col gap-1.5">
				<span class="font-heading text-xs font-semibold text-fg-primary">Confirm Password</span>
				<input
					type="password"
					required
					minlength={12}
					autocomplete="new-password"
					bind:value={confirmPassword}
					placeholder="Re-enter password"
					class="rounded-lg border border-border-strong bg-canvas px-3 py-2.5 font-mono text-sm text-fg-primary placeholder:text-fg-muted focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan/30"
				/>
			</label>

			<Button type="submit" variant="primary" size="lg" disabled={submitting} class="mt-1 w-full">
				{submitting ? 'Creating Admin…' : 'Create Admin Account & Sign In'}
			</Button>
		</form>
	</div>
</div>
