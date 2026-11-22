<script lang="ts">
	import '../app.css';
	import type { Snippet } from 'svelte';
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import AppShell from '$components/layout/AppShell.svelte';
	import MiruLogo from '$components/layout/MiruLogo.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { streamsStore } from '$stores/streams.svelte';

	type Props = {
		children?: Snippet;
	};

	let { children }: Props = $props();

	const isAuthPage = $derived(page.url.pathname === '/login' || page.url.pathname === '/setup');

	onMount(() => {
		void authStore.init();
		return () => {
			streamsStore.disconnect();
		};
	});

	$effect(() => {
		if (authStore.loading) return;

		const pathname = page.url.pathname;

		if (authStore.setupRequired && authStore.localEnabled) {
			streamsStore.disconnect();
			if (pathname !== '/setup') {
				void goto('/setup');
			}
			return;
		}

		if (authStore.authMode !== 'none' && !authStore.authenticated) {
			preferencesStore.clearLoadedAccount();
			streamsStore.disconnect();
			if (pathname !== '/login') {
				void goto('/login');
			}
			return;
		}

		const accountKey = authStore.principal?.accountId ?? authStore.authMode;
		if (preferencesStore.loadedAccountId !== accountKey) {
			void preferencesStore.loadForAccount(accountKey);
		}

		if (pathname === '/login' || pathname === '/setup') {
			void goto('/');
			return;
		}

		if (pathname === '/admin' && !authStore.isAdmin) {
			void goto('/');
			return;
		}

		streamsStore.connect();
	});
</script>

<svelte:head>
	<title>Miru — Media Server Telemetry</title>
</svelte:head>

{#if authStore.loading}
	<div class="flex min-h-dvh flex-col items-center justify-center gap-3 bg-canvas text-fg-secondary">
		<MiruLogo size={44} />
		<p class="font-mono text-xs text-fg-muted">Connecting to Miru…</p>
	</div>
{:else if isAuthPage}
	{@render children?.()}
{:else}
	<AppShell>
		{@render children?.()}
	</AppShell>
{/if}
