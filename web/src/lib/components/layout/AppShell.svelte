<script lang="ts">
	import type { Snippet } from 'svelte';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import {
		Radio,
		History,
		Users,
		Library,
		ShieldCheck,
		Sun,
		Moon,
		LogOut
	} from 'lucide-svelte';
	import MiruLogo from './MiruLogo.svelte';
	import Badge from '$components/ui/Badge.svelte';
	import { authStore } from '$stores/auth.svelte';
	import { streamsStore } from '$stores/streams.svelte';
	import { themeStore } from '$stores/theme.svelte';
	import { cn, safeInitial } from '$lib/utils';

	type Props = {
		children?: Snippet;
	};

	let { children }: Props = $props();

	let mobileUserMenuOpen = $state(false);

	$effect(() => {
		void page.url.pathname;
		mobileUserMenuOpen = false;
	});

	function handleWindowKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape' && mobileUserMenuOpen) {
			mobileUserMenuOpen = false;
		}
	}

	const navItems = $derived.by(() => {
		const items = [
			{ href: '/', label: 'Live', icon: Radio, badge: streamsStore.totalCount > 0 ? String(streamsStore.totalCount) : null },
			{ href: '/history', label: 'History', icon: History, badge: null },
			{ href: '/users', label: 'Users', icon: Users, badge: null },
			{ href: '/libraries', label: 'Libraries', icon: Library, badge: null }
		];
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
			return 'hw-transcode' as const;
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

	async function handleLogout() {
		const redirectsToOidc = authStore.oidcEnabled && Boolean(authStore.oidcLogoutUrl);
		await authStore.logout();
		if (!redirectsToOidc) {
			await goto('/login');
		}
	}
</script>

<svelte:window onclick={() => (mobileUserMenuOpen = false)} onkeydown={handleWindowKeydown} />

<div class="flex min-h-dvh w-full bg-canvas text-fg-primary">
	<!-- EXPANDED DESKTOP SIDEBAR (lg: >=1024px) -->
	<aside
		class="hidden lg:flex lg:w-60 lg:shrink-0 lg:flex-col lg:justify-between border-r border-border bg-surface p-4 sticky top-0 h-dvh"
	>
		<div class="flex flex-col gap-4">
			<div class="flex items-center justify-between px-1">
				<a href="/" class="flex items-center gap-2.5">
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
							'flex items-center justify-between rounded-lg px-3 py-2 font-heading text-[13px] transition-colors',
							active
								? 'bg-primary-soft font-semibold text-fg-primary'
								: 'font-medium text-fg-secondary hover:bg-elevated hover:text-fg-primary'
						)}
					>
						<span class="flex items-center gap-2.5">
							<Icon class={cn('h-4 w-4', active ? 'text-primary-hover' : 'text-fg-muted')} />
							{item.label}
						</span>
						{#if item.badge}
							<span class="rounded-full bg-cyan px-2 py-0.5 font-mono text-[10px] font-semibold text-white">
								{item.badge}
							</span>
						{/if}
					</a>
				{/each}
			</nav>
		</div>

		<div class="flex flex-col gap-2.5">
			<div class="flex items-center justify-between rounded-lg border border-border bg-canvas px-3 py-2">
				<span class="flex items-center gap-2 font-heading text-[11px] font-semibold text-fg-primary">
					<span
						class={cn(
							'h-2 w-2 rounded-full',
							streamsStore.status === 'connected'
								? 'bg-direct-play'
								: streamsStore.status === 'offline'
									? 'bg-danger'
									: 'bg-hw-transcode animate-pulse'
						)}
					></span>
					Telemetry
				</span>
				<span class="font-mono text-[10px] font-semibold uppercase text-fg-secondary">
					{streamsStore.status === 'connected' ? 'SSE' : streamsStore.status}
				</span>
			</div>

			<div class="flex items-center justify-between rounded-lg border border-border bg-elevated px-2.5 py-2">
				<div class="flex min-w-0 items-center gap-2">
					<div
						class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary font-heading text-xs font-bold text-white"
					>
						{safeInitial(authStore.principal?.username)}
					</div>
					<div class="min-w-0">
						<p class="truncate font-heading text-xs font-semibold text-fg-primary">
							{authStore.principal?.username ?? 'Guest'}
						</p>
						<p class="text-[10px] text-fg-muted">
							{authStore.principal?.role === 'ADMIN' ? 'Admin' : 'User'}
						</p>
					</div>
				</div>
				<div class="flex items-center gap-1">
					<button
						type="button"
						onclick={() => themeStore.toggle()}
						aria-label="Toggle color theme"
						class="inline-flex h-7 w-7 items-center justify-center rounded-md text-fg-secondary hover:bg-surface hover:text-fg-primary cursor-pointer"
					>
						{#if themeStore.mode === 'dark'}
							<Sun class="h-3.5 w-3.5" />
						{:else}
							<Moon class="h-3.5 w-3.5" />
						{/if}
					</button>
					{#if authStore.authMode !== 'none' && authStore.authenticated}
						<button
							type="button"
							onclick={handleLogout}
							aria-label="Sign out"
							class="inline-flex h-7 w-7 items-center justify-center rounded-md text-fg-secondary hover:bg-danger-soft hover:text-danger cursor-pointer"
						>
							<LogOut class="h-3.5 w-3.5" />
						</button>
					{/if}
				</div>
			</div>
		</div>
	</aside>

	<!-- MEDIUM TABLET NAVIGATION RAIL (md: 768px..1023px) -->
	<aside
		class="hidden md:flex lg:hidden w-20 shrink-0 flex-col items-center justify-between border-r border-border bg-surface py-4 px-2 sticky top-0 h-dvh"
	>
		<div class="flex w-full flex-col items-center gap-4">
			<a href="/" aria-label="Miru Home">
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
						class="flex w-full flex-col items-center gap-1 py-0.5"
					>
						<span
							class={cn(
								'flex h-8 w-14 items-center justify-center rounded-full transition-colors',
								active ? 'bg-primary-soft text-primary-hover' : 'text-fg-muted hover:bg-elevated hover:text-fg-primary'
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

		<button
			type="button"
			onclick={() => themeStore.toggle()}
			aria-label="Toggle color theme"
			class="inline-flex h-10 w-10 items-center justify-center rounded-full text-fg-secondary hover:bg-elevated hover:text-fg-primary cursor-pointer"
		>
			{#if themeStore.mode === 'dark'}
				<Sun class="h-4 w-4" />
			{:else}
				<Moon class="h-4 w-4" />
			{/if}
		</button>
	</aside>

	<!-- MAIN CONTENT COLUMN (Mobile Top Bar + Page Body + Mobile M3 Bottom Nav) -->
	<div class="flex min-w-0 flex-1 flex-col">
		<!-- COMPACT MOBILE PWA TOP APP BAR (<768px) -->
		<header
			class="sticky top-0 z-30 flex items-center justify-between border-b border-border bg-surface/95 px-4 py-3 backdrop-blur-md md:hidden"
		>
			<a href="/" class="flex items-center gap-2">
				<MiruLogo size={26} />
				<span class="font-display text-lg font-bold tracking-wide text-fg-primary">Miru</span>
			</a>
			<div class="flex items-center gap-2">
				<Badge tone={sseTone} dot pulse={streamsStore.status === 'connected'} mono>
					{sseShortLabel}
				</Badge>
				<div class="relative">
					<button
						type="button"
						onclick={(e) => {
							e.stopPropagation();
							mobileUserMenuOpen = !mobileUserMenuOpen;
						}}
						aria-label="Account menu"
						aria-haspopup="menu"
						aria-expanded={mobileUserMenuOpen}
						class="inline-flex h-8 max-w-[132px] cursor-pointer items-center gap-1.5 rounded-full border border-border bg-elevated py-0.5 pr-2.5 pl-1 font-heading text-xs font-semibold text-fg-primary transition-colors hover:border-border-strong"
					>
						<span
							class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-[11px] font-bold text-white"
						>
							{safeInitial(authStore.principal?.username)}
						</span>
						<span class="truncate">{authStore.principal?.username ?? 'Guest'}</span>
					</button>

					{#if mobileUserMenuOpen}
						<div
							role="menu"
							tabindex="-1"
							onclick={(e) => e.stopPropagation()}
							onkeydown={(e) => {
								if (e.key === 'Escape') mobileUserMenuOpen = false;
							}}
							class="absolute right-0 z-50 mt-2 w-52 rounded-xl border border-border bg-surface p-2 shadow-lg"
						>
							<div class="border-b border-border px-2.5 py-2">
								<p class="truncate font-heading text-xs font-semibold text-fg-primary">
									{authStore.principal?.username ?? 'Guest'}
								</p>
								<p class="text-[10px] text-fg-muted">
									{authStore.principal?.role === 'ADMIN' ? 'Admin' : 'User'}
								</p>
							</div>
							<div class="mt-1 flex flex-col gap-0.5">
								<button
									type="button"
									role="menuitem"
									onclick={() => {
										themeStore.toggle();
										mobileUserMenuOpen = false;
									}}
									class="flex w-full cursor-pointer items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs font-medium text-fg-secondary hover:bg-elevated hover:text-fg-primary"
								>
									{#if themeStore.mode === 'dark'}
										<Sun class="h-3.5 w-3.5" />
										Light theme
									{:else}
										<Moon class="h-3.5 w-3.5" />
										Dark theme
									{/if}
								</button>
								{#if authStore.authMode !== 'none' && authStore.authenticated}
									<button
										type="button"
										role="menuitem"
										onclick={() => {
											mobileUserMenuOpen = false;
											void handleLogout();
										}}
										class="flex w-full cursor-pointer items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs font-medium text-danger hover:bg-danger-soft"
									>
										<LogOut class="h-3.5 w-3.5" />
										Sign out
									</button>
								{/if}
							</div>
						</div>
					{/if}
				</div>
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
					class="flex min-h-[48px] min-w-[56px] flex-col items-center justify-center gap-1"
				>
					<span
						class={cn(
							'relative flex h-7 w-13 items-center justify-center rounded-full transition-colors',
							active ? 'bg-primary-soft text-primary-hover' : 'text-fg-muted'
						)}
					>
						<Icon class="h-4 w-4" />
						{#if item.badge}
							<span
								class="absolute -top-0.5 right-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-cyan px-1 font-mono text-[9px] font-bold text-white"
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
