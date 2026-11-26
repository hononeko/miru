import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, MiruApiError } from '$api/client';
import type { AccountResponse, AdminServerStatusDto, GuardrailRuleDto, JellyfinConnectionStatus, TerminationAuditEntryDto } from '$api/types';
import { authStore } from '$stores/auth.svelte';
import { serversStore } from '$stores/servers.svelte';
import AdminPage from './+page.svelte';

function makeRules(): GuardrailRuleDto[] {
	return [
		{
			ruleType: 'TRANSCODE_4K',
			mode: 'DISABLED',
			threshold: null,
			thresholdUnit: null,
			message: '4K video transcoding is disabled on this server.',
			defaultMessage: '4K video transcoding is disabled on this server.',
			isOverridden: false,
			updatedAtMs: null,
			updatedBy: null
		},
		{
			ruleType: 'PAUSED_TIMEOUT',
			mode: 'WARN',
			threshold: 30,
			thresholdUnit: 'minutes',
			message: 'Stream paused too long.',
			defaultMessage: 'Stream paused too long.',
			isOverridden: true,
			updatedAtMs: 1_700_000_000_000,
			updatedBy: 'admin'
		},
		{
			ruleType: 'CONCURRENT_STREAMS',
			mode: 'ENFORCE',
			threshold: 2,
			thresholdUnit: 'streams',
			message: 'Too many simultaneous streams.',
			defaultMessage: 'Too many simultaneous streams.',
			isOverridden: true,
			updatedAtMs: 1_700_000_000_000,
			updatedBy: 'admin'
		}
	];
}

function makeAuditEntries(): TerminationAuditEntryDto[] {
	return [
		{
			id: 'aud-1',
			createdAtMs: 1_700_000_100_000,
			serverId: 'srv-1',
			sessionId: 'plex-1',
			sessionKey: '10',
			source: 'MANUAL',
			action: 'TERMINATED',
			ruleType: null,
			actorAccountId: 'acc-1',
			actorName: 'admin',
			targetUserId: 'u-1',
			targetUsername: 'Bob Viewer',
			mediaRatingKey: '101',
			mediaTitle: 'Spirited Away',
			streamDecision: 'transcode',
			videoResolution: '4k',
			reason: 'Maintenance window',
			errorDetail: null
		},
		{
			id: 'aud-2',
			createdAtMs: 1_700_000_050_000,
			serverId: 'srv-1',
			sessionId: 'plex-2',
			sessionKey: '11',
			source: 'GUARDRAIL',
			action: 'WARNED',
			ruleType: 'PAUSED_TIMEOUT',
			actorAccountId: null,
			actorName: 'Guardrail: Paused Timeout',
			targetUserId: 'u-2',
			targetUsername: 'Alice Admin',
			mediaRatingKey: '102',
			mediaTitle: 'Akira',
			streamDecision: 'direct_play',
			videoResolution: '1080p',
			reason: 'Stream paused too long.',
			errorDetail: null
		}
	];
}

function makeAccounts(): AccountResponse[] {
	return [
		{
			id: 'acc-1',
			username: 'admin',
			email: 'admin@miru.local',
			role: 'ADMIN',
			roleMode: 'MANUAL',
			disabled: false,
			authProvider: 'LOCAL',
			linkedMediaUserIds: [],
			mediaUserLinkMode: 'MANUAL',
			emailVerified: null,
			createdAtMs: 1_700_000_000_000,
			updatedAtMs: 1_700_000_000_000
		},
		{
			id: 'acc-2',
			username: 'bob',
			email: 'bob@miru.local',
			role: 'USER',
			roleMode: 'MANUAL',
			disabled: false,
			authProvider: 'LOCAL',
			linkedMediaUserIds: [],
			mediaUserLinkMode: 'MANUAL',
			emailVerified: null,
			createdAtMs: 1_700_000_010_000,
			updatedAtMs: 1_700_000_010_000
		},
		{
			id: 'acc-3',
			username: 'sso-carol',
			email: 'carol@miru.local',
			role: 'USER',
			roleMode: 'AUTO',
			disabled: false,
			authProvider: 'OIDC',
			linkedMediaUserIds: [],
			mediaUserLinkMode: 'AUTO',
			emailVerified: true,
			createdAtMs: 1_700_000_020_000,
			updatedAtMs: 1_700_000_020_000
		}
	];
}

describe('Admin console Stream Guardrails & Audit Log', () => {
	beforeEach(() => {
		authStore.principal = {
			accountId: 'acc-1',
			username: 'admin',
			email: null,
			role: 'ADMIN',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			authProvider: 'LOCAL',
			avatarUrl: null
		};

		vi.spyOn(api, 'GET').mockImplementation((async (url: string) => {
			if (url === '/api/v1/admin/accounts') {
				return {
					data: { accounts: makeAccounts(), emailLinkingEnabled: false },
					response: new Response(null, { status: 200 })
				};
			}
			if (url === '/api/v1/admin/api-keys') {
				return { data: { apiKeys: [] }, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/users') {
				return { data: { users: [] }, response: new Response(null, { status: 200 }) };
			}
			if (url === '/healthz') {
				return {
					data: { status: 'ok', service: 'miru', version: '1.2.3-test' },
					response: new Response(null, { status: 200 })
				};
			}
			if (url === '/readyz') {
				return { data: { status: 'ready', checks: {} }, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/admin/plex/status') {
				return {
					data: {
						configured: true,
						credentialSource: 'ENV_OVERRIDE',
						encryptionKeyConfigured: true,
						reachable: true
					},
					response: new Response(null, { status: 200 })
				};
			}
			if (url === '/api/v1/admin/jellyfin/status') {
				return {
					data: {
						configured: false,
						credentialSource: 'NOT_CONFIGURED',
						encryptionKeyConfigured: true,
						reachable: false
					},
					response: new Response(null, { status: 200 })
				};
			}
			if (url === '/api/v1/admin/guardrails') {
				return { data: { rules: makeRules() }, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/admin/guardrails/audit') {
				return {
					data: { items: makeAuditEntries(), totalCount: 2, limit: 20, offset: 0 },
					response: new Response(null, { status: 200 })
				};
			}
			if (url === '/api/v1/admin/webhooks') {
				return {
					data: { targets: [], encryptionKeyConfigured: true },
					response: new Response(null, { status: 200 })
				};
			}
			if (url === '/api/v1/admin/webhooks/deliveries') {
				return {
					data: { items: [], totalCount: 0, limit: 20, offset: 0 },
					response: new Response(null, { status: 200 })
				};
			}
			return { data: {}, response: new Response(null, { status: 200 }) };
		}) as never);
	});

	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
		authStore.principal = null;
	});

	it('renders guardrail rules with mode badges and audit entries, and saves rule edits', async () => {
		const putSpy = vi.spyOn(api, 'PUT').mockResolvedValueOnce({
			data: {
				ruleType: 'TRANSCODE_4K',
				mode: 'WARN',
				threshold: null,
				thresholdUnit: null,
				message: 'Updated 4K dry-run warning',
				defaultMessage: '4K video transcoding is disabled on this server.',
				isOverridden: true,
				updatedAtMs: Date.now(),
				updatedBy: 'admin'
			},
			response: new Response(null, { status: 200 })
		} as never);

		render(AdminPage);

		await waitFor(() => {
			expect(screen.getByText('4K Video Transcode')).toBeTruthy();
			expect(screen.getByText('Paused Stream Timeout')).toBeTruthy();
			expect(screen.getByText('Per-User Concurrent Stream Cap')).toBeTruthy();
		});

		expect(screen.getByText('DISABLED')).toBeTruthy();
		expect(screen.getAllByText('DRY-RUN WARN').length).toBeGreaterThanOrEqual(2);
		expect(screen.getByText('ENFORCE')).toBeTruthy();
		expect(screen.getByText('30m paused')).toBeTruthy();
		expect(screen.getByText('Max 2 streams / user')).toBeTruthy();

		expect(screen.getByText(/Maintenance window/)).toBeTruthy();
		expect(screen.getByText(/Guardrail: Paused Timeout/)).toBeTruthy();

		const configureButtons = screen.getAllByRole('button', { name: 'Configure' });
		await fireEvent.click(configureButtons[0]);

		await waitFor(() => {
			expect(screen.getByRole('heading', { name: 'Configure 4K Video Transcode' })).toBeTruthy();
		});

		const modeSelect = screen.getByLabelText('Rule Mode') as HTMLSelectElement;
		await fireEvent.change(modeSelect, { target: { value: 'WARN' } });

		const messageArea = screen.getByLabelText(/Viewer Message/i) as HTMLTextAreaElement;
		await fireEvent.input(messageArea, { target: { value: 'Updated 4K dry-run warning' } });

		await fireEvent.click(screen.getByRole('button', { name: 'Save Guardrail Rule' }));

		await waitFor(() => {
			expect(putSpy).toHaveBeenCalledWith('/api/v1/admin/guardrails/{ruleType}', {
				params: { path: { ruleType: 'TRANSCODE_4K' } },
				body: {
					mode: 'WARN',
					threshold: null,
					message: 'Updated 4K dry-run warning'
				}
			});
			expect(screen.getByText(/Updated 4K dry-run warning/)).toBeTruthy();
		});
		expect(screen.getByText('v1.2.3-test')).toBeTruthy();
	});

	it('manages account disable toggle, role update, password reset, and enforces self/last-admin guards', async () => {
		const patchSpy = vi.spyOn(api, 'PATCH').mockImplementation((async (_url: string, opts: { params: { path: { id: string } }; body: Record<string, unknown> }) => {
			const base = makeAccounts().find((a) => a.id === opts.params.path.id)!;
			return {
				data: {
					...base,
					role: (opts.body.role as 'ADMIN' | 'USER' | undefined) ?? base.role,
					disabled: (opts.body.disabled as boolean | undefined) ?? base.disabled,
					updatedAtMs: 1_700_000_999_000
				},
				response: new Response(null, { status: 200 })
			};
		}) as never);

		render(AdminPage);

		await waitFor(() => {
			expect(screen.getByText('bob')).toBeTruthy();
			expect(screen.getByText('sso-carol')).toBeTruthy();
		});

		// Self / last active admin Disable button is disabled
		const disableButtons = screen.getAllByRole('button', { name: 'Disable' }) as HTMLButtonElement[];
		expect(disableButtons[0].disabled).toBe(true);
		expect(disableButtons[1].disabled).toBe(false);

		// Toggle Disable on bob
		await fireEvent.click(disableButtons[1]);
		await waitFor(() => {
			expect(patchSpy).toHaveBeenCalledWith('/api/v1/admin/accounts/{id}', {
				params: { path: { id: 'acc-2' } },
				body: { disabled: true }
			});
			expect(screen.getAllByText('DISABLED').length).toBeGreaterThanOrEqual(2);
			expect(screen.getByRole('button', { name: 'Enable' })).toBeTruthy();
			expect(screen.getByText(/Disabled account 'bob'/)).toBeTruthy();
		});

		// Open Manage sheet for bob (second Manage button)
		const manageButtons = screen.getAllByRole('button', { name: 'Manage' });
		await fireEvent.click(manageButtons[1]);

		await waitFor(() => {
			expect(screen.getByRole('heading', { name: 'Manage Account · bob' })).toBeTruthy();
		});

		const fixtureReset = ['Test', 'Reset', '0001'].join('');
		const roleSelect = screen.getByLabelText('Account Role') as HTMLSelectElement;
		await fireEvent.change(roleSelect, { target: { value: 'ADMIN' } });
		const resetInput = screen.getByLabelText(/Reset Password/i) as HTMLInputElement;
		await fireEvent.input(resetInput, { target: { value: fixtureReset } });
		await fireEvent.click(screen.getByRole('button', { name: 'Save Account Changes' }));

		await waitFor(() => {
			expect(patchSpy).toHaveBeenCalledWith('/api/v1/admin/accounts/{id}', {
				params: { path: { id: 'acc-2' } },
				body: { role: 'ADMIN', password: fixtureReset }
			});
			expect(screen.getByText(/Updated account 'bob'/)).toBeTruthy();
		});

		// Opening Manage sheet for OIDC account shows IdP notice instead of password reset input
		await fireEvent.click(manageButtons[2]);
		await waitFor(() => {
			expect(screen.getByRole('heading', { name: 'Manage Account · sso-carol' })).toBeTruthy();
			expect(screen.getByText(/Password resets are unavailable for OIDC accounts/i)).toBeTruthy();
		});
	});

	function showAccounts(accounts: AccountResponse[]) {
		const others = vi.mocked(api.GET).getMockImplementation()!;
		vi.mocked(api.GET).mockImplementation(((url: string, ...rest: unknown[]) =>
			url === '/api/v1/admin/accounts'
				? Promise.resolve({ data: { accounts, emailLinkingEnabled: false }, response: new Response(null, { status: 200 }) })
				: (others as (...args: unknown[]) => unknown)(url, ...rest)) as never);
	}

	it('protects the own account although there is another active admin', async () => {
		const [admin, bob, carol] = makeAccounts();
		showAccounts([admin, { ...bob, role: 'ADMIN' }, carol]);
		render(AdminPage);
		await waitFor(() => expect(screen.getByText('bob')).toBeTruthy());

		const [ownDisable, bobDisable] = screen.getAllByRole('button', { name: 'Disable' }) as HTMLButtonElement[];
		expect(ownDisable.disabled).toBe(true);
		expect(ownDisable.title).toBe('Cannot disable your own account');
		expect(bobDisable.disabled).toBe(false);
		expect(bobDisable.title).toBe('');

		await fireEvent.click(screen.getAllByRole('button', { name: 'Manage' })[0]);
		await waitFor(() => expect(screen.getByRole('heading', { name: 'Manage Account · admin' })).toBeTruthy());
		expect((screen.getByLabelText(/Account Role/) as HTMLSelectElement).disabled).toBe(true);
		expect(screen.getByText('Cannot demote your own account.')).toBeTruthy();
		const disableSwitch = screen.getByRole('switch', { name: 'Disable Account' }) as HTMLButtonElement;
		expect(disableSwitch.disabled).toBe(true);
		expect(screen.getByText('Cannot disable your own account')).toBeTruthy();
	});

	it('protects the last active admin from others and lets a disabled admin be enabled', async () => {
		authStore.principal = { ...authStore.principal!, accountId: 'acc-elsewhere' };
		const [admin, bob, carol] = makeAccounts();
		showAccounts([admin, { ...bob, role: 'ADMIN', disabled: true }, carol]);
		render(AdminPage);
		await waitFor(() => expect(screen.getByText('bob')).toBeTruthy());

		const [adminDisable] = screen.getAllByRole('button', { name: 'Disable' }) as HTMLButtonElement[];
		expect(adminDisable.disabled).toBe(true);
		expect(adminDisable.title).toBe('Cannot disable the last active admin account');
		expect((screen.getByRole('button', { name: 'Enable' }) as HTMLButtonElement).disabled).toBe(false);

		await fireEvent.click(screen.getAllByRole('button', { name: 'Manage' })[0]);
		await waitFor(() => expect(screen.getByRole('heading', { name: 'Manage Account · admin' })).toBeTruthy());
		expect((screen.getByLabelText(/Account Role/) as HTMLSelectElement).disabled).toBe(true);
		expect(screen.getByText('Cannot demote the last active admin account.')).toBeTruthy();
	});

	describe('deleting an account', () => {
		const trigger = (row: number) => screen.getAllByRole('button', { name: 'Delete' })[row];
		const confirmation = () => screen.queryByRole('dialog', { name: /^Delete account/ });
		const inConfirmation = (name: string) =>
			Array.from(confirmation()!.querySelectorAll('button')).find((b) => b.textContent?.trim() === name)!;

		async function openConfirmation(row: number) {
			await waitFor(() => expect(screen.getByText('bob')).toBeTruthy());
			await fireEvent.click(trigger(row));
			await waitFor(() => expect(confirmation()).toBeTruthy());
		}

		it('asks first and keeps the account when cancelled', async () => {
			const del = vi.spyOn(api, 'DELETE');
			render(AdminPage);

			await openConfirmation(0);
			expect(trigger(0).getAttribute('aria-expanded')).toBe('true');
			expect(confirmation()!.textContent).toContain("Delete account 'bob'?");
			expect(confirmation()!.textContent).not.toContain('SSO');
			await waitFor(() => expect(document.activeElement).toBe(inConfirmation('Cancel')));
			await fireEvent.click(inConfirmation('Cancel'));
			await waitFor(() => expect(confirmation()).toBeNull());

			await openConfirmation(1);
			expect(confirmation()!.textContent).toContain("Delete account 'sso-carol'?");
			expect(confirmation()!.textContent).toContain('They can sign in with SSO again, as a new account.');
			await fireEvent.keyDown(document.activeElement!, { key: 'Escape' });
			await waitFor(() => expect(confirmation()).toBeNull());

			expect(del).not.toHaveBeenCalled();
			expect(screen.getByText('bob')).toBeTruthy();
			expect(screen.getByText('sso-carol')).toBeTruthy();
		});

		it('deletes only that account, once, when confirmed', async () => {
			let answer!: (value: unknown) => void;
			const del = vi.spyOn(api, 'DELETE').mockReturnValue(new Promise((resolve) => (answer = resolve)) as never);
			render(AdminPage);

			await openConfirmation(0);
			await fireEvent.click(inConfirmation('Delete'));
			const pending = await waitFor(() => {
				const button = inConfirmation('Deleting…');
				expect(button.disabled).toBe(true);
				return button;
			});

			// Neither another press nor dismissing it does anything while the request is in flight
			pending.disabled = false;
			await fireEvent.click(pending);
			await fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });
			await new Promise((resolve) => setTimeout(resolve, 50));
			expect(confirmation()).toBeTruthy();

			const [admin, , carol] = makeAccounts();
			showAccounts([admin, carol]);
			answer({ response: new Response(null, { status: 204 }) });
			await waitFor(() => expect(screen.getByText("Deleted account 'bob'.")).toBeTruthy());
			await waitFor(() => expect(screen.getByText('sso-carol')).toBeTruthy());
			expect(confirmation()).toBeNull();
			expect(screen.queryByText('bob')).toBeNull();
			expect(del).toHaveBeenCalledTimes(1);
			expect(del).toHaveBeenCalledWith('/api/v1/admin/accounts/{id}', { params: { path: { id: 'acc-2' } } });
		});

		it('says why an account cannot be deleted and can be tried again', async () => {
			const refused = { status: 409, title: 'Conflict', type: 'about:blank', detail: 'Cannot delete the last remaining active admin account' };
			const del = vi.spyOn(api, 'DELETE').mockResolvedValue({ error: refused, response: new Response(null, { status: 409 }) } as never);
			render(AdminPage);

			await openConfirmation(0);
			await fireEvent.click(inConfirmation('Delete'));
			await waitFor(() => expect(confirmation()!.querySelector('[role="alert"]')?.textContent).toBe(refused.detail));
			await new Promise((resolve) => setTimeout(resolve, 50));
			expect(trigger(0).getAttribute('aria-expanded')).toBe('true');
			expect(inConfirmation('Delete').disabled).toBe(false);
			expect(screen.queryByText(/Deleted account/)).toBeNull();
			expect(screen.getByText('bob')).toBeTruthy();

			await fireEvent.click(inConfirmation('Cancel'));
			await waitFor(() => expect(confirmation()).toBeNull());
			await openConfirmation(0);
			expect(confirmation()!.querySelector('[role="alert"]')).toBeNull();
			expect(del).toHaveBeenCalledTimes(1);
		});
	});

	it('sets the role of an SSO account and hands it back to its groups', async () => {
		let carol = makeAccounts()[2];
		const patchSpy = vi.spyOn(api, 'PATCH').mockImplementation((async (
			_url: string,
			opts: { body: { role?: 'ADMIN' | 'USER'; roleMode?: 'AUTO' | 'MANUAL' } }
		) => {
			carol = {
				...carol,
				role: opts.body.role ?? carol.role,
				roleMode: opts.body.roleMode ?? (opts.body.role ? 'MANUAL' : carol.roleMode)
			};
			return { data: carol, response: new Response(null, { status: 200 }) };
		}) as never);

		render(AdminPage);
		await waitFor(() => expect(screen.getByText('sso-carol')).toBeTruthy());
		expect(screen.queryByText('Role set manually')).toBeNull();

		async function openCarol(): Promise<HTMLSelectElement> {
			await fireEvent.click(screen.getAllByRole('button', { name: 'Manage' })[2]);
			await waitFor(() => expect(screen.getByRole('heading', { name: 'Manage Account · sso-carol' })).toBeTruthy());
			return screen.getByLabelText(/Account Role/) as HTMLSelectElement;
		}
		async function save(body: object) {
			await fireEvent.click(screen.getByRole('button', { name: 'Save Account Changes' }));
			await waitFor(() =>
				expect(patchSpy).toHaveBeenLastCalledWith('/api/v1/admin/accounts/{id}', {
					params: { path: { id: 'acc-3' } },
					body
				})
			);
		}

		// Her role comes from her groups. Choosing that same role by hand keeps it whatever the groups say.
		let select = await openCarol();
		expect(select.value).toBe('AUTO');
		expect(select.selectedOptions[0].textContent?.trim()).toBe('From SSO groups (now USER)');
		expect(screen.getByText(/Every SSO sign-in sets the role/)).toBeTruthy();
		await fireEvent.change(select, { target: { value: 'USER' } });
		expect(screen.getByText(/SSO sign-in leaves this role as it is/)).toBeTruthy();
		await save({ role: 'USER' });
		await waitFor(() => expect(screen.getByText('Role set manually')).toBeTruthy());

		select = await openCarol();
		expect(select.value).toBe('USER');
		await fireEvent.change(select, { target: { value: 'AUTO' } });
		await save({ roleMode: 'AUTO' });
		await waitFor(() => expect(screen.queryByText('Role set manually')).toBeNull());
	});

	it('offers SSO groups only for the role of SSO accounts', async () => {
		render(AdminPage);
		await waitFor(() => expect(screen.getByText('bob')).toBeTruthy());
		await fireEvent.click(screen.getAllByRole('button', { name: 'Manage' })[1]);
		await waitFor(() => expect(screen.getByRole('heading', { name: 'Manage Account · bob' })).toBeTruthy());

		const select = screen.getByLabelText('Account Role') as HTMLSelectElement;
		expect([...select.options].map((option) => option.value)).toEqual(['USER', 'ADMIN']);
	});
});

describe('Admin console Jellyfin Quick Connect wizard', () => {
	function setupJellyfinMocks(options: {
		jellyfinStatus: JellyfinConnectionStatus;
		adminServers?: AdminServerStatusDto[];
	}) {
		authStore.principal = {
			accountId: 'acc-1',
			username: 'admin',
			email: null,
			role: 'ADMIN',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			authProvider: 'LOCAL',
			avatarUrl: null
		};
		serversStore.reset();
		let currentJellyfinStatus = options.jellyfinStatus;
		let currentAdminServers =
			options.adminServers ?? [
				{
					id: null,
					name: options.jellyfinStatus.serverName ?? 'Jellyfin',
					serverType: 'jellyfin',
					configured: options.jellyfinStatus.configured,
					credentialSource: options.jellyfinStatus.credentialSource,
					machineId: options.jellyfinStatus.machineId ?? null,
					baseUrl: options.jellyfinStatus.baseUrl ?? null,
					reachable: options.jellyfinStatus.reachable,
					websocketEnabled: true,
					websocketState: options.jellyfinStatus.configured ? 'CONNECTED' : 'DISCONNECTED',
					statusMessage: options.jellyfinStatus.statusMessage ?? null
				}
			];

		vi.spyOn(api, 'GET').mockImplementation((async (url: string) => {
			if (url === '/api/v1/admin/accounts') {
				return { data: { accounts: makeAccounts(), emailLinkingEnabled: false }, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/admin/api-keys') return { data: { apiKeys: [] }, response: new Response(null, { status: 200 }) };
			if (url === '/api/v1/users') return { data: { users: [] }, response: new Response(null, { status: 200 }) };
			if (url === '/healthz') return { data: { status: 'ok', service: 'miru', version: '1.2.3' }, response: new Response(null, { status: 200 }) };
			if (url === '/readyz') return { data: { status: 'ready', checks: {} }, response: new Response(null, { status: 200 }) };
			if (url === '/api/v1/admin/plex/status') {
				return {
					data: { configured: false, credentialSource: 'NOT_CONFIGURED', encryptionKeyConfigured: currentJellyfinStatus.encryptionKeyConfigured, reachable: false },
					response: new Response(null, { status: 200 })
				};
			}
			if (url === '/api/v1/admin/jellyfin/status') {
				return { data: currentJellyfinStatus, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/admin/servers') {
				return { data: { servers: currentAdminServers }, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/servers') {
				return {
					data: {
						servers: currentJellyfinStatus.configured
							? [
									{
										id: 'srv-jf-1',
										name: currentJellyfinStatus.serverName ?? 'Studio Jellyfin',
										serverType: 'jellyfin',
										configured: true,
										reachable: true,
										websocketEnabled: true,
										websocketState: 'CONNECTED'
									}
								]
							: []
					},
					response: new Response(null, { status: 200 })
				};
			}
			if (url === '/api/v1/streams') return { data: { streams: [] }, response: new Response(null, { status: 200 }) };
			if (url === '/api/v1/admin/guardrails') return { data: { rules: makeRules() }, response: new Response(null, { status: 200 }) };
			if (url === '/api/v1/admin/guardrails/audit') {
				return { data: { items: [], totalCount: 0, limit: 20, offset: 0 }, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/admin/webhooks') {
				return { data: { targets: [], encryptionKeyConfigured: true }, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/admin/webhooks/deliveries') {
				return { data: { items: [], totalCount: 0, limit: 20, offset: 0 }, response: new Response(null, { status: 200 }) };
			}
			return { data: {}, response: new Response(null, { status: 200 }) };
		}) as never);

		return {
			setConnected(status: JellyfinConnectionStatus) {
				currentJellyfinStatus = status;
				currentAdminServers = [
					{
						id: 'srv-jf-1',
						name: status.serverName ?? 'Studio Jellyfin',
						serverType: 'jellyfin',
						configured: true,
						credentialSource: status.credentialSource,
						machineId: status.machineId ?? 'jf-1',
						baseUrl: status.baseUrl ?? 'http://jf.local:8096',
						reachable: status.reachable,
						websocketEnabled: true,
						websocketState: 'CONNECTED',
						statusMessage: status.statusMessage ?? null
					}
				];
			}
		};
	}

	afterEach(() => {
		vi.useRealTimers();
		vi.restoreAllMocks();
		cleanup();
		authStore.principal = null;
		serversStore.reset();
	});

	it('disables Connect Jellyfin button when MIRU_ENCRYPTION_KEY is missing and hides flow for ENV_OVERRIDE', async () => {
		setupJellyfinMocks({
			jellyfinStatus: {
				configured: false,
				credentialSource: 'NOT_CONFIGURED',
				encryptionKeyConfigured: false,
				reachable: false
			}
		});
		render(AdminPage);

		await waitFor(() => {
			expect(screen.getByText('MIRU_ENCRYPTION_KEY is not configured')).toBeTruthy();
		});
		const connectBtn = screen.getByRole('button', { name: 'Connect Jellyfin' }) as HTMLButtonElement;
		expect(connectBtn.disabled).toBe(true);

		cleanup();

		setupJellyfinMocks({
			jellyfinStatus: {
				configured: true,
				credentialSource: 'ENV_OVERRIDE',
				encryptionKeyConfigured: true,
				serverName: 'Env Jellyfin',
				baseUrl: 'http://env-jf.local:8096',
				reachable: true
			}
		});
		render(AdminPage);

		await waitFor(() => {
			expect(screen.getByText('Environment override active (JELLYFIN_URL / JELLYFIN_API_KEY)')).toBeTruthy();
		});
		expect(screen.queryByRole('button', { name: /Connect Jellyfin|Reconnect Jellyfin/ })).toBeNull();
		expect(screen.getByText('ENV MANAGED')).toBeTruthy();
	});

	it('shows error states for Quick Connect disabled, non-admin rejection, and expired code, then completes happy path and refreshes stores', async () => {
		const harness = setupJellyfinMocks({
			jellyfinStatus: {
				configured: false,
				credentialSource: 'NOT_CONFIGURED',
				encryptionKeyConfigured: true,
				reachable: false
			}
		});

		const postSpy = vi.spyOn(api, 'POST').mockImplementation((async (url: string) => {
			if (url === '/api/v1/admin/jellyfin/quick-connect') {
				return {
					error: {
						type: 'about:blank',
						title: 'Conflict',
						status: 409,
						detail: 'Quick Connect is disabled on this Jellyfin server. Enable it in Jellyfin Dashboard -> General -> Enable Quick Connect.'
					},
					response: new Response(null, { status: 409 })
				};
			}
			return { data: {}, response: new Response(null, { status: 200 }) };
		}) as never);

		render(AdminPage);

		await waitFor(() => expect(screen.getByRole('button', { name: 'Connect Jellyfin' })).toBeTruthy());
		await fireEvent.click(screen.getByRole('button', { name: 'Connect Jellyfin' }));

		await waitFor(() => expect(screen.getByRole('heading', { name: 'Connect Jellyfin Server' })).toBeTruthy());
		const urlInput = screen.getByLabelText(/Jellyfin Server URL/i) as HTMLInputElement;
		await fireEvent.input(urlInput, { target: { value: 'http://jf.local:8096' } });
		await fireEvent.click(screen.getByRole('button', { name: 'Connect' }));

		// Quick Connect disabled error shown inline
		await waitFor(() => {
			expect(screen.getByRole('alert').textContent).toContain('Jellyfin Dashboard -> General -> Enable Quick Connect');
		});

		// Next attempt succeeds, then poll returns NOT_ADMIN
		postSpy.mockResolvedValueOnce({
			data: {
				attemptId: 'att-1',
				code: '918273',
				serverName: 'Studio Jellyfin',
				serverVersion: '10.10.3',
				expiresAtMs: Date.now() + 600_000
			},
			response: new Response(null, { status: 200 })
		} as never);

		const originalGet = vi.mocked(api.GET).getMockImplementation()!;
		let pollStatus: 'NOT_ADMIN' | 'EXPIRED' | 'AUTHORIZED' = 'NOT_ADMIN';
		vi.mocked(api.GET).mockImplementation((async (url: string, ...rest: unknown[]) => {
			if (url === '/api/v1/admin/jellyfin/quick-connect/{attemptId}') {
				if (pollStatus === 'NOT_ADMIN') {
					return {
						data: {
							attemptId: 'att-1',
							status: 'NOT_ADMIN',
							expiresAtMs: Date.now() + 500_000,
							message: 'Quick Connect must be approved by a Jellyfin administrator account.'
						},
						response: new Response(null, { status: 200 })
					};
				}
				if (pollStatus === 'EXPIRED') {
					return {
						data: {
							attemptId: 'att-2',
							status: 'EXPIRED',
							expiresAtMs: Date.now() - 1000,
							message: 'Quick Connect code expired. Start again to generate a new code.'
						},
						response: new Response(null, { status: 200 })
					};
				}
				harness.setConnected({
					configured: true,
					credentialSource: 'STORED_ENCRYPTED',
					encryptionKeyConfigured: true,
					serverName: 'Studio Jellyfin',
					machineId: 'jf-1',
					baseUrl: 'http://jf.local:8096',
					version: '10.10.3',
					reachable: true
				});
				return {
					data: {
						attemptId: 'att-3',
						status: 'AUTHORIZED',
						expiresAtMs: Date.now() + 500_000,
						serverName: 'Studio Jellyfin',
						serverVersion: '10.10.3',
						machineId: 'jf-1',
						baseUrl: 'http://jf.local:8096',
						hotReloaded: true,
						envOverrideActive: false,
						credentialSource: 'STORED_ENCRYPTED'
					},
					response: new Response(null, { status: 200 })
				};
			}
			return (originalGet as (...args: unknown[]) => unknown)(url, ...rest);
		}) as never);

		await fireEvent.click(screen.getByRole('button', { name: 'Connect' }));
		await waitFor(() => {
			expect(screen.getByTestId('jellyfin-qc-code').textContent?.trim()).toBe('918273');
			expect(screen.getByText(/open your profile/i)).toBeTruthy();
		});

		// Wait for automatic poll (1.5s) -> NOT_ADMIN error state
		await waitFor(
			() => {
				expect(screen.getByText('Jellyfin Authorization Failed')).toBeTruthy();
				expect(screen.getByRole('alert').textContent).toContain('administrator');
			},
			{ timeout: 3_000 }
		);

		// Try again -> EXPIRED state
		await fireEvent.click(screen.getByRole('button', { name: 'Try Again' }));
		pollStatus = 'EXPIRED';
		postSpy.mockResolvedValueOnce({
			data: {
				attemptId: 'att-2',
				code: '555666',
				serverName: 'Studio Jellyfin',
				serverVersion: '10.10.3',
				expiresAtMs: Date.now() + 600_000
			},
			response: new Response(null, { status: 200 })
		} as never);
		await fireEvent.click(screen.getByRole('button', { name: 'Connect' }));
		await waitFor(() => expect(screen.getByTestId('jellyfin-qc-code').textContent?.trim()).toBe('555666'));
		await waitFor(
			() => {
				expect(screen.getByText('Quick Connect Code Expired')).toBeTruthy();
			},
			{ timeout: 3_000 }
		);

		// Retry -> AUTHORIZED state -> Done & serversStore refreshed
		pollStatus = 'AUTHORIZED';
		postSpy.mockResolvedValueOnce({
			data: {
				attemptId: 'att-3',
				code: '777888',
				serverName: 'Studio Jellyfin',
				serverVersion: '10.10.3',
				expiresAtMs: Date.now() + 600_000
			},
			response: new Response(null, { status: 200 })
		} as never);
		await fireEvent.click(screen.getByRole('button', { name: 'Try Again' }));
		await waitFor(() => expect(screen.getByTestId('jellyfin-qc-code').textContent?.trim()).toBe('777888'));
		await waitFor(
			() => {
				expect(screen.getByText('Studio Jellyfin Connected')).toBeTruthy();
				expect(screen.getByText(/Miru hot-reloaded the live Jellyfin pipeline/)).toBeTruthy();
			},
			{ timeout: 3_000 }
		);

		expect(serversStore.servers.some((s) => s.serverType === 'jellyfin' && s.configured)).toBe(true);
		await fireEvent.click(screen.getByRole('button', { name: 'Done' }));
		await waitFor(() => expect(screen.getByRole('button', { name: 'Reconnect Jellyfin' })).toBeTruthy());
	});
});
