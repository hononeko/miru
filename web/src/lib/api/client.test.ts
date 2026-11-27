import { afterEach, describe, expect, it, vi } from 'vitest';
import { AVATAR_MAX_BYTES, MiruApiError, uploadBinaryAvatar, validateAvatarFile } from './client';

const file = (size: number, type: string) => new File([new Uint8Array(size)], 'avatar', { type });

describe('avatar uploads', () => {
	afterEach(() => {
		vi.unstubAllGlobals();
	});

	it('accepts PNG, JPEG, and WebP up to 512 KiB and explains everything else', () => {
		expect(validateAvatarFile(file(10, 'image/png'))).toBeNull();
		expect(validateAvatarFile(file(10, 'IMAGE/JPEG'))).toBeNull();
		expect(validateAvatarFile(file(AVATAR_MAX_BYTES, 'image/webp'))).toBeNull();
		expect(validateAvatarFile(file(AVATAR_MAX_BYTES + 1, 'image/png'))).toContain('512 KiB');
		expect(validateAvatarFile(file(0, 'image/png'))).toContain('empty');
		expect(validateAvatarFile(file(10, 'image/gif'))).toContain('PNG, JPEG, or WebP');
		expect(validateAvatarFile(file(10, ''))).toContain('PNG, JPEG, or WebP');
	});

	it('refuses an invalid file without sending it', async () => {
		const fetchMock = vi.fn();
		vi.stubGlobal('fetch', fetchMock);
		await expect(uploadBinaryAvatar('/api/v1/auth/me/avatar', file(10, 'image/gif'))).rejects.toMatchObject({
			status: 400,
			detail: 'Avatar must be a PNG, JPEG, or WebP image.'
		});
		expect(fetchMock).not.toHaveBeenCalled();
	});

	it('turns a Problem Details response into a MiruApiError, and copes with a body that is not JSON', async () => {
		vi.stubGlobal(
			'fetch',
			vi.fn(
				async () =>
					new Response(
						JSON.stringify({ title: 'Bad Request', detail: 'Unsupported or invalid avatar image format', errors: { avatar: 'bad' } }),
						{ status: 400, headers: { 'Content-Type': 'application/problem+json' } }
					)
			)
		);
		const error = await uploadBinaryAvatar('/api/v1/auth/me/avatar', file(10, 'image/png')).catch((e: unknown) => e);
		expect(error).toBeInstanceOf(MiruApiError);
		expect(error).toMatchObject({ status: 400, detail: 'Unsupported or invalid avatar image format', fieldErrors: { avatar: 'bad' } });

		vi.stubGlobal('fetch', vi.fn(async () => new Response('gateway down', { status: 502 })));
		await expect(uploadBinaryAvatar('/api/v1/auth/me/avatar', file(10, 'image/png'))).rejects.toMatchObject({
			status: 502,
			detail: 'Request failed (502)'
		});
	});
});
