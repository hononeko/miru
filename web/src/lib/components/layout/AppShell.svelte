<script lang="ts">
	import type { Snippet } from 'svelte';
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { Popover } from 'bits-ui';
	import { api } from '$api/client';
	import {
		Radio,
		History,
		Users,
		Library,
		ShieldCheck,
		Sun,
		Moon,
		LogOut
	} from '@lucide/svelte';
	import MiruLogo from './MiruLogo.svelte';
	import Badge from '$components/ui/Badge.svelte';
	import ProxiedImage from '$components/miru/ProxiedImage.svelte';
	import ServerStatusList from '$components/miru/ServerStatusList.svelte';
	import ServerStatusPopover from '$components/miru/ServerStatusPopover.svelte';
	import SignOutConfirmation from '$components/miru/SignOutConfirmation.svelte';
	import UserPreferencesModal from '$components/miru/UserPreferencesModal.svelte';
	import MediaDetailsModal from '$components/miru/MediaDetailsModal.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { preferencesStore } from '$stores/preferences.svelte';
	import { serversStore } from '$stores/servers.svelte';
	import { streamsStore } from '$stores/streams.svelte';
	import { themeStore } from '$stores/theme.svelte';
	import { cn } from '$lib/utils';

	type Props = {
		children?: Snippet;
	};

	let { children }: Props = $props();

	const signOutTitleId = 'sidebar-sign-out-title';

	let serverVersion = $state<string | null>(null);
	let signOutOpen = $state(false);

	onMount(() => {
		if (typeof window === 'undefined') return;
		void api
			.GET('/healthz')
			.then((res) => {
				if (res.data?.version) {
					serverVersion = res.data.version;
				}
			})
			.catch(() => {});
	});

	const navItems = $derived.by(() => {
		const items = [
			{ href: '/', label: 'Live', icon: Radio, badge: streamsStore.totalCount > 0 ? String(streamsStore.totalCount) : null },
			{ href: '/history', label: 'History', icon: History, badge: null }
		];
		if (authStore.isAdmin) {
			items.push({ href: '/users', label: 'Users', icon: Users, badge: null });
		}
		items.push({ href: '/libraries', label: 'Libraries', icon: Library, badge: null });
		if (authStore.isAdmin) {
			items.push({ href: '/admin', label: 'Admin', icon: ShieldCheck, badge: null });
		}
		return items;
	});

	function isActive(href: string, pathname: string): boolean {
		if (href === '/') return pathname === '/';
		return pathname === href || pathname.startsWith(`${href}/`);
	}

	const sseTone = $derived.by(() => {
		if (streamsStore.status === 'connected') return 'direct-play' as const;
		if (streamsStore.status === 'connecting' || streamsStore.status === 'reconnecting') {
			return 'warning' as const;
		}
		return 'danger' as const;
	});

	const sseShortLabel = $derived.by(() => {
		if (streamsStore.status === 'connected') {
			return streamsStore.totalCount > 0 ? `LIVE · ${streamsStore.totalCount}` : 'LIVE';
		}
		if (streamsStore.status === 'reconnecting' || streamsStore.status === 'connecting') return 'SYNCING';
		return 'OFFLINE';
	});

	const telemetryTargetLabel = $derived(streamsStore.serverName ?? 'Telemetry');

	const sseDotClass = $derived(
		streamsStore.status === 'connected'
			? 'bg-direct-play'
			: streamsStore.status === 'offline'
				? 'bg-danger'
				: 'bg-warning animate-pulse'
	);

	const sseStatusLabel = $derived(streamsStore.status === 'connected' ? 'SSE' : streamsStore.status);

	function handleSignOutOpenChange(open: boolean) {
		if (!authStore.loggingOut) signOutOpen = open;
	}
</script>

<div class="flex min-h-dvh w-full bg-canvas text-fg-primary">
	<!-- EXPANDED DESKTOP SIDEBAR (lg: >=1024px) -->
	<aside
		class="hidden lg:flex lg:w-60 lg:shrink-0 lg:flex-col lg:justify-between border-r border-border bg-surface p-4 sticky top-0 h-dvh"
	>
		<div class="flex flex-col gap-4">
			<div class="flex items-center justify-between px-1">
				<a href="/" class="group flex items-center gap-2.5">
					<MiruLogo size={30} />
					<span class="font-display text-xl font-bold tracking-wide text-fg-primary">Miru</span>
				</a>
				<Badge tone={sseTone} dot pulse={streamsStore.status === 'connected'} mono>
					{streamsStore.status === 'connected' ? 'LIVE' : sseShortLabel}
				</Badge>
			</div>

			<div class="h-px bg-border"></div>

			<nav aria-label="Primary navigation" class="flex flex-col gap-1">
				{#each navItems as item}
					{@const active = isActive(item.href, page.url.pathname)}
					{@const Icon = item.icon}
					{#if item.href === '/admin'}
						<div class="my-1.5 h-px bg-border"></div>
					{/if}
					<a
						href={item.href}
						aria-current={active ? 'page' : undefined}
						class={cn(
							'group flex items-center justify-between rounded-lg px-3 py-2 font-heading text-[13px] transition-[background-color,color,transform] duration-150 ease-[var(--ease-out-quart)] active:scale-[0.985] motion-reduce:active:scale-100',
							active
								? 'bg-primary-soft font-semibold text-fg-primary'
								: 'font-medium text-fg-secondary hover:bg-elevated hover:text-fg-primary'
						)}
					>
						<span class="flex items-center gap-2.5">
							<Icon
								class={cn(
									'h-4 w-4 transition-colors duration-150',
									active ? 'text-primary-text' : 'text-fg-muted group-hover:text-fg-primary'
								)}
							/>
							{item.label}
						</span>
						{#if item.badge}
							<span class="rounded-full bg-cyan px-2 py-0.5 font-mono text-[10px] font-semibold text-cyan-fg">
								{item.badge}
							</span>
						{/if}
					</a>
				{/each}
			</nav>
		</div>

		<div class="flex flex-col gap-2.5">
			{#if serversStore.hasMultipleServers}
				<div class="flex flex-col rounded-lg border border-border bg-canvas" data-testid="sidebar-server-status">
					<div class="flex flex-col gap-2 px-3 pt-2 pb-2.5">
						<p class="font-heading text-[10px] font-semibold tracking-wider text-fg-muted uppercase">
							Media servers
						</p>
						<ServerStatusList servers={serversStore.configuredServers} stale={serversStore.stale} />
					</div>
					<div
						class="flex items-center justify-between gap-2 border-t border-border px-3 py-2"
						title="Miru's live connection to this browser"
					>
						<span class="flex min-w-0 items-center gap-2 font-heading text-[11px] font-semibold text-fg-primary">
							<span class={cn('h-2 w-2 shrink-0 rounded-full', sseDotClass)}></span>
							<span class="truncate">Live updates</span>
						</span>
						<span class="shrink-0 font-mono text-[10px] font-semibold uppercase text-fg-secondary">
							{sseStatusLabel}
						</span>
					</div>
				</div>
			{:else}
				<div class="flex items-center justify-between gap-2 rounded-lg border border-border bg-canvas px-3 py-2">
					<span class="flex min-w-0 items-center gap-2 font-heading text-[11px] font-semibold text-fg-primary">
						<span class={cn('h-2 w-2 shrink-0 rounded-full', sseDotClass)}></span>
						<span class="truncate" title={telemetryTargetLabel}>
							{telemetryTargetLabel}
						</span>
					</span>
					<span class="shrink-0 font-mono text-[10px] font-semibold uppercase text-fg-secondary">
						{sseStatusLabel}
					</span>
				</div>
			{/if}

			<div class="flex items-center justify-between gap-1 rounded-lg border border-border bg-elevated px-2 py-1.5">
				<button
					type="button"
					onclick={() => preferencesStore.openModal()}
					aria-label="Open user preferences"
					class="flex min-w-0 flex-1 cursor-pointer items-center gap-2 rounded-md px-1 py-0.5 text-left transition-colors hover:bg-surface"
				>
					<ProxiedImage
						src={authStore.principal?.avatarUrl}
						alt={authStore.principal?.username ?? 'Guest'}
						fallbackLabel={authStore.principal?.username ?? 'Guest'}
						variant="avatar"
						class="h-7 w-7 text-xs"
					/>
					<div class="min-w-0">
						<p class="truncate font-heading text-xs font-semibold text-fg-primary">
							{authStore.principal?.username ?? 'Guest'}
						</p>
						<p class="text-[10px] text-fg-muted">
							{authStore.principal?.role === 'ADMIN' ? 'Admin' : 'User'}
						</p>
					</div>
				</button>
				<div class="flex shrink-0 items-center gap-1">
					<button
						type="button"
						onclick={() => themeStore.toggle()}
						aria-label="Toggle color theme"
						class="inline-flex h-7 w-7 items-center justify-center rounded-md text-fg-secondary transition-[background-color,color,transform] duration-150 hover:bg-surface hover:text-fg-primary active:scale-90 motion-reduce:active:scale-100 cursor-pointer"
					>
						{#key themeStore.resolved}
							{#if themeStore.resolved === 'dark'}
								<Sun class="animate-icon-pop h-3.5 w-3.5" />
							{:else}
								<Moon class="animate-icon-pop h-3.5 w-3.5" />
							{/if}
						{/key}
					</button>
					{#if authStore.authMode !== 'none' && authStore.authenticated}
						<Popover.Root bind:open={() => signOutOpen, handleSignOutOpenChange}>
							<Popover.Trigger
								type="button"
								aria-label="Sign out"
								class="inline-flex h-7 w-7 items-center justify-center rounded-md text-fg-secondary transition-[background-color,color,transform] duration-150 hover:bg-danger-soft hover:text-danger active:scale-90 motion-reduce:active:scale-100 cursor-pointer data-[state=open]:bg-danger-soft data-[state=open]:text-danger"
							>
								<LogOut class="h-3.5 w-3.5" />
							</Popover.Trigger>
							<Popover.Portal>
								<Popover.Content
									side="top"
									align="end"
									sideOffset={8}
									collisionPadding={12}
									aria-labelledby={signOutTitleId}
									class="z-50 w-60 max-w-[calc(100vw-1.5rem)] rounded-xl border border-border-strong bg-surface p-3 text-fg-primary shadow-xl outline-none data-[state=open]:animate-popover-in"
								>
									<Popover.Arrow class="text-border-strong" width={10} height={5} />
									<SignOutConfirmation
										titleId={signOutTitleId}
										oncancel={() => handleSignOutOpenChange(false)}
										onsignedout={() => (signOutOpen = false)}
									/>
								</Popover.Content>
							</Popover.Portal>
						</Popover.Root>
					{/if}
				</div>
			</div>
			{#if serverVersion}
				<p class="px-1 text-center font-mono text-[10px] text-fg-muted">
					Miru v{serverVersion}
				</p>
			{/if}
		</div>
	</aside>

	<!-- MEDIUM TABLET NAVIGATION RAIL (md: 768px..1023px) -->
	<aside
		class="hidden md:flex lg:hidden w-20 shrink-0 flex-col items-center justify-between border-r border-border bg-surface py-4 px-2 sticky top-0 h-dvh"
	>
		<div class="flex w-full flex-col items-center gap-4">
			<a href="/" aria-label="Miru Home" class="group">
				<MiruLogo size={32} />
			</a>
			<div class="h-px w-10 bg-border"></div>
			<nav aria-label="Tablet navigation" class="flex w-full flex-col items-center gap-2">
				{#each navItems as item}
					{@const active = isActive(item.href, page.url.pathname)}
					{@const Icon = item.icon}
					<a
						href={item.href}
						aria-current={active ? 'page' : undefined}
						class="group flex w-full flex-col items-center gap-1 py-0.5"
					>
						<span
							class={cn(
								'flex h-8 w-14 items-center justify-center rounded-full transition-[background-color,color,transform] duration-200 ease-[var(--ease-out-quart)] group-active:scale-95 motion-reduce:group-active:scale-100',
								active ? 'bg-primary-soft text-primary-text' : 'text-fg-muted hover:bg-elevated hover:text-fg-primary'
							)}
						>
							<Icon class="h-4 w-4" />
						</span>
						<span
							class={cn(
								'font-heading text-[10px]',
								active ? 'font-semibold text-fg-primary' : 'font-medium text-fg-muted'
							)}
						>
							{item.label}
						</span>
					</a>
				{/each}
			</nav>
		</div>

		<div class="flex flex-col items-center gap-2">
			<button
				type="button"
				onclick={() => themeStore.toggle()}
				aria-label="Toggle color theme"
				class="inline-flex h-10 w-10 items-center justify-center rounded-full text-fg-secondary transition-[background-color,color,transform] duration-150 hover:bg-elevated hover:text-fg-primary active:scale-90 motion-reduce:active:scale-100 cursor-pointer"
			>
				{#key themeStore.resolved}
					{#if themeStore.resolved === 'dark'}
						<Sun class="animate-icon-pop h-4 w-4" />
					{:else}
						<Moon class="animate-icon-pop h-4 w-4" />
					{/if}
				{/key}
			</button>
			<button
				type="button"
				onclick={() => preferencesStore.openModal()}
				aria-label="Open user preferences"
				title={authStore.principal?.username ?? 'User preferences'}
				class="inline-flex cursor-pointer items-center justify-center rounded-full ring-2 ring-border transition-transform hover:scale-105 hover:ring-border-strong"
			>
				<ProxiedImage
					src={authStore.principal?.avatarUrl}
					alt={authStore.principal?.username ?? 'Guest'}
					fallbackLabel={authStore.principal?.username ?? 'Guest'}
					variant="avatar"
					class="h-9 w-9 text-xs"
				/>
			</button>
		</div>
	</aside>

	<!-- MAIN CONTENT COLUMN (Mobile Top Bar + Page Body + Mobile M3 Bottom Nav) -->
	<div class="flex min-w-0 flex-1 flex-col">
		<!-- COMPACT MOBILE PWA TOP APP BAR (<768px) -->
		<header
			class="sticky top-0 z-30 flex items-center justify-between border-b border-border bg-surface/95 px-4 py-3 backdrop-blur-md md:hidden"
		>
			<a href="/" class="group flex shrink-0 items-center gap-2">
				<MiruLogo size={26} />
				<span class="font-display text-lg font-bold tracking-wide text-fg-primary">Miru</span>
			</a>
			<div class="flex min-w-0 items-center gap-2">
				{#if serversStore.hasMultipleServers}
					<ServerStatusPopover servers={serversStore.configuredServers} stale={serversStore.stale} />
				{/if}
				<Badge tone={sseTone} dot pulse={streamsStore.status === 'connected'} mono>
					{sseShortLabel}
				</Badge>
				<button
					type="button"
					onclick={() => preferencesStore.openModal()}
					aria-label="Open user preferences"
					class="inline-flex h-8 max-w-[132px] cursor-pointer items-center gap-1.5 rounded-full border border-border bg-elevated py-0.5 pr-2.5 pl-1 font-heading text-xs font-semibold text-fg-primary transition-[border-color,transform] duration-150 hover:border-border-strong active:scale-95 motion-reduce:active:scale-100"
				>
					<ProxiedImage
						src={authStore.principal?.avatarUrl}
						alt={authStore.principal?.username ?? 'Guest'}
						fallbackLabel={authStore.principal?.username ?? 'Guest'}
						variant="avatar"
						class="h-6 w-6 text-[11px]"
					/>
					<span class="truncate">{authStore.principal?.username ?? 'Guest'}</span>
				</button>
			</div>
		</header>

		<main class="mx-auto w-full max-w-[1440px] flex-1 px-4 py-5 pb-24 md:px-6 md:py-6 md:pb-8 lg:px-8 lg:py-7">
			{@render children?.()}
		</main>

		<!-- COMPACT MOBILE PWA M3 BOTTOM NAVIGATION BAR (<768px, >=48px tap targets) -->
		<nav
			aria-label="Mobile navigation"
			class="fixed inset-x-0 bottom-0 z-40 flex items-center justify-around border-t border-border bg-surface/95 px-2 pt-2 backdrop-blur-md md:hidden safe-pb"
		>
			{#each navItems as item}
				{@const active = isActive(item.href, page.url.pathname)}
				{@const Icon = item.icon}
				<a
					href={item.href}
					aria-current={active ? 'page' : undefined}
					class="group flex min-h-[48px] min-w-[56px] flex-col items-center justify-center gap-1"
				>
					<span
						class={cn(
							'relative flex h-7 w-13 items-center justify-center rounded-full transition-[background-color,color,transform] duration-200 ease-[var(--ease-out-quart)] group-active:scale-95 motion-reduce:group-active:scale-100',
							active ? 'bg-primary-soft text-primary-text' : 'text-fg-muted'
						)}
					>
						<Icon class="h-4 w-4" />
						{#if item.badge}
							<span
								class="absolute -top-0.5 right-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-cyan px-1 font-mono text-[9px] font-bold text-cyan-fg"
							>
								{item.badge}
							</span>
						{/if}
					</span>
					<span
						class={cn(
							'font-heading text-[10px]',
							active ? 'font-semibold text-fg-primary' : 'font-medium text-fg-muted'
						)}
					>
						{item.label}
					</span>
				</a>
			{/each}
		</nav>
	</div>
</div>

<UserPreferencesModal />
<MediaDetailsModal />
