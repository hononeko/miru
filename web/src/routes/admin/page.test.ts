import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import type { AccountResponse, GuardrailRuleDto, TerminationAuditEntryDto } from '$api/types';
import { authStore } from '$stores/auth.svelte';
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
