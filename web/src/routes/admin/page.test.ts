import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import type { GuardrailRuleDto, TerminationAuditEntryDto } from '$api/types';
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

describe('Admin console Stream Guardrails & Audit Log', () => {
	beforeEach(() => {
		authStore.principal = {
			accountId: 'acc-1',
			username: 'admin',
			email: null,
			role: 'ADMIN',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			avatarUrl: null
		};

		vi.spyOn(api, 'GET').mockImplementation((async (url: string) => {
			if (url === '/api/v1/admin/accounts') {
				return { data: { accounts: [], emailLinkingEnabled: false }, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/admin/api-keys') {
				return { data: { apiKeys: [] }, response: new Response(null, { status: 200 }) };
			}
			if (url === '/api/v1/users') {
				return { data: { users: [] }, response: new Response(null, { status: 200 }) };
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
	});
});
