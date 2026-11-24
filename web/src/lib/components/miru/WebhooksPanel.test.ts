import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import type { WebhookDeliveryDto, WebhookTargetDto } from '$api/types';
import WebhooksPanel from './WebhooksPanel.svelte';

function makeTargets(): WebhookTargetDto[] {
	return [
		{
			id: 'wh-1',
			name: 'Media Notifier',
			url: 'http://media-webhook-notifier.media.svc.cluster.local:8080/events?***',
			enabled: true,
			subscribedEvents: ['transcode.started', 'stream.terminated'],
			hasSigningSecret: true,
			authHeaderName: 'Authorization',
			hasAuthHeader: true,
			cooldownSeconds: 60,
			concurrentStreamThreshold: 3,
			bufferingSpikeThreshold: 3,
			bufferingWindowSeconds: 120,
			transcode4kOnly: true,
			createdAtMs: 1_700_000_000_000,
			updatedAtMs: 1_700_000_000_000
		}
	];
}

function makeDeliveries(): WebhookDeliveryDto[] {
	return [
		{
			id: 'del-1',
			targetId: 'wh-1',
			targetName: 'Media Notifier',
			eventId: 'evt-1',
			eventType: 'transcode.started',
			status: 'DELIVERED',
			attemptCount: 1,
			httpStatus: 200,
			durationMs: 28,
			errorMessage: null,
			createdAtMs: 1_700_000_100_000,
			completedAtMs: 1_700_000_100_028
		},
		{
			id: 'del-2',
			targetId: 'wh-1',
			targetName: 'Media Notifier',
			eventId: 'evt-2',
			eventType: 'server.unreachable',
			status: 'FAILED',
			attemptCount: 3,
			httpStatus: 502,
			durationMs: 140,
			errorMessage: 'HTTP 502: bad gateway from upstream receiver',
			createdAtMs: 1_700_000_050_000,
			completedAtMs: 1_700_000_058_000
		}
	];
}

describe('WebhooksPanel', () => {
	beforeEach(() => {
		vi.spyOn(api, 'GET').mockImplementation((async (url: string) => {
			if (url === '/api/v1/admin/webhooks') {
				return {
					data: { targets: makeTargets(), encryptionKeyConfigured: true },
					response: new Response(null, { status: 200 })
				};
			}
			if (url === '/api/v1/admin/webhooks/deliveries') {
				return {
					data: { items: makeDeliveries(), totalCount: 2, limit: 20, offset: 0 },
					response: new Response(null, { status: 200 })
				};
			}
			return { data: {}, response: new Response(null, { status: 200 }) };
		}) as never);
	});

	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
	});

	it('renders configured targets with badges, redacted URLs, and expandable failed delivery errors', async () => {
		render(WebhooksPanel);

		await waitFor(() => {
			expect(screen.getAllByText('Media Notifier').length).toBeGreaterThanOrEqual(1);
		});

		expect(
			screen.getByText('http://media-webhook-notifier.media.svc.cluster.local:8080/events?***')
		).toBeTruthy();
		expect(screen.getByText('ENABLED')).toBeTruthy();
		expect(screen.getByText('Signed')).toBeTruthy();
		expect(screen.getByText('Auth header: Authorization')).toBeTruthy();
		expect(screen.getByText('4K ONLY')).toBeTruthy();
		expect(screen.getByText('DELIVERED')).toBeTruthy();
		expect(screen.getByText('FAILED')).toBeTruthy();
		expect(screen.getByText('HTTP 200')).toBeTruthy();
		expect(screen.getByText('28ms')).toBeTruthy();

		// Error details are hidden until expanded
		expect(screen.queryByText('HTTP 502: bad gateway from upstream receiver')).toBeNull();
		await fireEvent.click(screen.getByRole('button', { name: /Error details/i }));
		expect(screen.getByText('HTTP 502: bad gateway from upstream receiver')).toBeTruthy();
	});

	it('creates a new webhook target through the sheet', async () => {
		const postSpy = vi.spyOn(api, 'POST').mockResolvedValueOnce({
			data: {
				id: 'wh-2',
				name: 'Alert Receiver',
				url: 'http://192.168.1.50:9000/hook',
				enabled: true,
				subscribedEvents: ['transcode.started', 'stream.terminated'],
				hasSigningSecret: true,
				authHeaderName: null,
				hasAuthHeader: false,
				cooldownSeconds: 60,
				concurrentStreamThreshold: 3,
				bufferingSpikeThreshold: 3,
				bufferingWindowSeconds: 120,
				transcode4kOnly: false,
				createdAtMs: Date.now(),
				updatedAtMs: Date.now()
			},
			response: new Response(null, { status: 201 })
		} as never);

		render(WebhooksPanel);
		await waitFor(() => expect(screen.getByRole('button', { name: /Add Webhook/i })).toBeTruthy());

		await fireEvent.click(screen.getByRole('button', { name: /Add Webhook/i }));
		await waitFor(() =>
			expect(screen.getByRole('heading', { name: 'Add Outbound Webhook' })).toBeTruthy()
		);

		await fireEvent.input(screen.getByLabelText('Target Name'), {
			target: { value: 'Alert Receiver' }
		});
		await fireEvent.input(screen.getByLabelText('Destination URL'), {
			target: { value: 'http://192.168.1.50:9000/hook' }
		});
		await fireEvent.input(screen.getByLabelText(/HMAC Signing Secret/i), {
			target: { value: 'my-hmac-secret' }
		});

		await fireEvent.click(screen.getByRole('button', { name: 'Create Webhook' }));

		await waitFor(() => {
			expect(postSpy).toHaveBeenCalledWith('/api/v1/admin/webhooks', {
				body: expect.objectContaining({
					name: 'Alert Receiver',
					url: 'http://192.168.1.50:9000/hook',
					signingSecret: 'my-hmac-secret',
					enabled: true
				})
			});
			expect(screen.getByRole('heading', { name: 'Alert Receiver' })).toBeTruthy();
		});
	});

	it('sends a test delivery and displays inline outcome feedback', async () => {
		const postSpy = vi.spyOn(api, 'POST').mockResolvedValueOnce({
			data: {
				id: 'del-test',
				targetId: 'wh-1',
				targetName: 'Media Notifier',
				eventId: 'evt-test',
				eventType: 'webhook.test',
				status: 'DELIVERED',
				attemptCount: 1,
				httpStatus: 204,
				durationMs: 19,
				errorMessage: null,
				createdAtMs: Date.now(),
				completedAtMs: Date.now()
			},
			response: new Response(null, { status: 200 })
		} as never);

		render(WebhooksPanel);
		await waitFor(() => expect(screen.getByRole('button', { name: /Send Test/i })).toBeTruthy());

		await fireEvent.click(screen.getByRole('button', { name: /Send Test/i }));

		await waitFor(() => {
			expect(postSpy).toHaveBeenCalledWith('/api/v1/admin/webhooks/{id}/test', {
				params: { path: { id: 'wh-1' } }
			});
			expect(screen.getByText('Test Delivered')).toBeTruthy();
			expect(screen.getByText('HTTP 204')).toBeTruthy();
		});
	});

	it('edits an existing target without clobbering redacted URL and deletes with confirmation', async () => {
		const patchSpy = vi.spyOn(api, 'PATCH').mockResolvedValueOnce({
			data: {
				...makeTargets()[0],
				name: 'Media Notifier Updated',
				hasSigningSecret: false
			},
			response: new Response(null, { status: 200 })
		} as never);
		const deleteSpy = vi.spyOn(api, 'DELETE').mockResolvedValueOnce({
			response: new Response(null, { status: 204 })
		} as never);

		render(WebhooksPanel);
		await waitFor(() => expect(screen.getByRole('button', { name: /Edit/i })).toBeTruthy());

		await fireEvent.click(screen.getByRole('button', { name: /Edit/i }));
		await waitFor(() =>
			expect(screen.getByRole('heading', { name: /Edit Webhook/i })).toBeTruthy()
		);

		await fireEvent.input(screen.getByLabelText('Target Name'), {
			target: { value: 'Media Notifier Updated' }
		});
		await fireEvent.click(screen.getByLabelText('Clear existing signing secret'));
		await fireEvent.click(screen.getByRole('button', { name: 'Save Webhook Changes' }));

		await waitFor(() => {
			expect(patchSpy).toHaveBeenCalledWith('/api/v1/admin/webhooks/{id}', {
				params: { path: { id: 'wh-1' } },
				body: expect.objectContaining({
					name: 'Media Notifier Updated',
					url: null,
					clearSigningSecret: true
				})
			});
			expect(screen.getByRole('heading', { name: 'Media Notifier Updated' })).toBeTruthy();
		});

		await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
		await fireEvent.click(screen.getByRole('button', { name: 'Confirm Delete' }));

		await waitFor(() => {
			expect(deleteSpy).toHaveBeenCalledWith('/api/v1/admin/webhooks/{id}', {
				params: { path: { id: 'wh-1' } }
			});
			expect(screen.getByText('No outbound webhooks configured')).toBeTruthy();
		});
	});
});
