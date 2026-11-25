<script lang="ts">
	import { goto } from '$app/navigation';
	import Confirmation from '$components/ui/Confirmation.svelte';
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

<Confirmation
	{titleId}
	title="Sign out of Miru?"
	{description}
	confirmLabel="Sign Out"
	busyLabel="Signing out…"
	busy={authStore.loggingOut}
	{oncancel}
	onconfirm={signOut}
/>
