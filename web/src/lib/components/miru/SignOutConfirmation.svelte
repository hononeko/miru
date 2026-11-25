<script lang="ts">
	import { goto } from '$app/navigation';
	import Button from '$components/ui/Button.svelte';
	import { authStore } from '$stores/auth.svelte';

	type Props = {
		titleId: string;
		description?: string;
		oncancel: () => void;
		/** Runs once the session is over, before leaving for the login page. */
		onsignedout?: () => void;
	};

	let { titleId, description, oncancel, onsignedout }: Props = $props();

	async function signOut() {
		// A second press can land before this is taken off the page
		if (authStore.loggingOut || !authStore.authenticated) return;
		const redirectsToOidc = authStore.oidcEnabled && Boolean(authStore.oidcLogoutUrl);
		try {
			await authStore.logout();
		} finally {
			onsignedout?.();
		}
		if (!redirectsToOidc) {
			await goto('/login');
		}
	}
</script>

<div class="flex flex-wrap items-center justify-between gap-x-4 gap-y-2.5">
	<div class="min-w-0">
		<p id={titleId} class="font-heading text-xs font-semibold text-fg-primary">Sign out of Miru?</p>
		{#if description}
			<p id={`${titleId}-description`} class="mt-0.5 text-[11px] text-fg-secondary">{description}</p>
		{/if}
	</div>
	<div class="ml-auto flex items-center gap-2">
		<Button variant="ghost" size="sm" disabled={authStore.loggingOut} onclick={oncancel}>Cancel</Button>
		<Button variant="destructive" size="sm" disabled={authStore.loggingOut} onclick={signOut}>
			{authStore.loggingOut ? 'Signing out…' : 'Sign Out'}
		</Button>
	</div>
</div>
