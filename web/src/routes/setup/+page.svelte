<script lang="ts">
	import { goto } from '$app/navigation';
	import { ArrowRight, LoaderCircle } from '@lucide/svelte';
	import { MiruApiError } from '$api/client';
	import MiruLogo from '$components/layout/MiruLogo.svelte';
	import Badge from '$components/ui/Badge.svelte';
	import Button from '$components/ui/Button.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { validatePassword } from '$lib/utils';

	let username = $state('admin');
	let email = $state('');
	let password = $state('');
	let confirmPassword = $state('');
	let submitting = $state(false);
	let error = $state<string | null>(null);
	let errorSeq = $state(0);

	async function handleSubmit(e: SubmitEvent) {
		e.preventDefault();
		error = null;

		const passwordError = validatePassword(password);
		if (passwordError) {
			error = passwordError;
			errorSeq += 1;
			return;
		}
		if (password !== confirmPassword) {
			error = 'Passwords do not match.';
			errorSeq += 1;
			return;
		}

		submitting = true;
		try {
			await authStore.setupAdmin(username.trim(), password, email);
			await goto('/');
		} catch (err) {
			error = err instanceof MiruApiError ? err.detail : 'Failed to create initial administrator account';
			errorSeq += 1;
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
		<div class="animate-fade-up flex flex-col items-center gap-2 text-center">
			<MiruLogo size={52} />
			<h1 class="font-display text-3xl font-bold tracking-wide text-fg-primary">Miru (見る)</h1>
			<p class="text-xs text-fg-secondary">First-run local administrator bootstrap</p>
		</div>

		<form
			onsubmit={handleSubmit}
			class="animate-fade-up flex w-full flex-col gap-4 rounded-2xl border border-primary/70 bg-surface p-6 shadow-sm dark:shadow-none sm:p-7"
			style="animation-delay: 60ms;"
		>
			<div class="flex items-start justify-between gap-2">
				<div>
					<h2 class="font-display text-2xl font-bold text-fg-primary">First-Run Setup</h2>
					<p class="text-xs text-fg-secondary">Create the initial Miru admin account</p>
				</div>
				<Badge tone="primary">SETUP REQUIRED</Badge>
			</div>

			{#if error}
				{#key errorSeq}
					<div
						role="alert"
						class="animate-shake rounded-lg border border-danger/50 bg-danger-soft px-3 py-2 text-xs text-danger"
					>
						{error}
					</div>
				{/key}
			{/if}

			<label class="flex flex-col gap-1.5">
				<span class="font-heading text-xs font-semibold text-fg-primary">Admin Username</span>
				<input
					type="text"
					required
					autocomplete="username"
					bind:value={username}
					placeholder="admin"
					class="rounded-lg border border-input-border bg-canvas px-3 py-2.5 text-sm text-fg-primary placeholder:text-fg-muted transition-[border-color,box-shadow] duration-150 focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
			</label>

			<label class="flex flex-col gap-1.5">
				<span class="font-heading text-xs font-semibold text-fg-primary">Email (optional)</span>
				<input
					type="email"
					autocomplete="email"
					bind:value={email}
					placeholder="admin@example.com"
					class="rounded-lg border border-input-border bg-canvas px-3 py-2.5 text-sm text-fg-primary placeholder:text-fg-muted transition-[border-color,box-shadow] duration-150 focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
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
					class="rounded-lg border border-input-border bg-canvas px-3 py-2.5 font-mono text-sm text-fg-primary transition-[border-color,box-shadow] duration-150 focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
				<span class="text-[10px] text-fg-muted">
					At least 12 characters with 1 lowercase, 1 uppercase, and 1 digit · hashed with Argon2id
				</span>
			</label>

			<label class="flex flex-col gap-1.5">
				<span class="font-heading text-xs font-semibold text-fg-primary">Confirm Password</span>
				<input
					type="password"
					required
					minlength={12}
					autocomplete="new-password"
					bind:value={confirmPassword}
					class="rounded-lg border border-input-border bg-canvas px-3 py-2.5 font-mono text-sm text-fg-primary transition-[border-color,box-shadow] duration-150 focus:border-cyan focus:outline-none focus:ring-2 focus:ring-cyan"
				/>
			</label>

			<Button type="submit" variant="primary" size="lg" disabled={submitting} class="group mt-1 w-full">
				{#if submitting}
					<LoaderCircle class="h-4 w-4 animate-spin" />
					Creating Admin…
				{:else}
					Create Admin Account & Sign In
					<ArrowRight
						class="h-4 w-4 transition-transform duration-150 ease-[var(--ease-out-quart)] group-hover:translate-x-0.5 motion-reduce:group-hover:translate-x-0"
					/>
				{/if}
			</Button>
		</form>
	</div>
</div>
