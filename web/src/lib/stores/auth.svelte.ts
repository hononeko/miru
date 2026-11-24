import { api, MiruApiError, unwrap } from '$api/client';
import type { AuthProvidersResponse, PrincipalResponse } from '$api/types';

export class AuthStore {
	loading = $state(true);
	authMode = $state<'local' | 'oidc' | 'none'>('local');
	localEnabled = $state(true);
	oidcEnabled = $state(false);
	oidcDisplayName = $state('SSO');
	oidcLoginUrl = $state<string | null>(null);
	oidcLogoutUrl = $state<string | null>(null);
	setupRequired = $state(false);
	principal = $state<PrincipalResponse | null>(null);
	loggingOut = $state(false);
	error = $state<string | null>(null);

	get authenticated(): boolean {
		return this.principal !== null;
	}

	get isAdmin(): boolean {
		return this.principal?.role === 'ADMIN';
	}

	get isUnlinkedUser(): boolean {
		return (
			this.authMode !== 'none' &&
			this.principal !== null &&
			this.principal.role !== 'ADMIN' &&
			this.principal.linkedMediaUserIds.length === 0
		);
	}

	async init(): Promise<void> {
		this.loading = true;
		this.error = null;
		try {
			const providers: AuthProvidersResponse = unwrap(await api.GET('/api/v1/auth/providers'));
			this.authMode = providers.authMode;
			this.localEnabled = providers.localEnabled;
			this.oidcEnabled = providers.oidcEnabled;
			this.oidcDisplayName = providers.oidcDisplayName;
			this.oidcLoginUrl = providers.oidcLoginUrl ?? null;
			this.oidcLogoutUrl = providers.oidcLogoutUrl ?? null;
			this.setupRequired = providers.setupRequired;

			if (!providers.setupRequired) {
				const meRes = await api.GET('/api/v1/auth/me');
				if (meRes.data) {
					this.principal = meRes.data;
				} else {
					this.principal = null;
				}
			} else {
				this.principal = null;
			}
		} catch (e) {
			this.error = e instanceof MiruApiError ? e.detail : 'Unable to reach Miru backend';
			this.principal = null;
		} finally {
			this.loading = false;
		}
	}

	async login(username: string, password: string): Promise<PrincipalResponse> {
		const principal = unwrap(
			await api.POST('/api/v1/auth/login', {
				body: { username, password }
			})
		);
		this.principal = principal;
		this.setupRequired = false;
		this.error = null;
		return principal;
	}

	async setupAdmin(username: string, password: string, email?: string): Promise<PrincipalResponse> {
		const principal = unwrap(
			await api.POST('/api/v1/auth/setup', {
				body: { username, password, email: email?.trim() || null }
			})
		);
		this.principal = principal;
		this.setupRequired = false;
		this.error = null;
		return principal;
	}

	async logout(): Promise<void> {
		if (this.loggingOut) return;
		if (this.oidcEnabled && this.oidcLogoutUrl) {
			this.principal = null;
			if (typeof window !== 'undefined') {
				window.location.assign(this.oidcLogoutUrl);
			}
			return;
		}
		this.loggingOut = true;
		try {
			await api.POST('/api/v1/auth/logout');
		} finally {
			this.loggingOut = false;
			this.principal = null;
		}
	}
}

export const authStore = new AuthStore();
