import createClient from 'openapi-fetch';
import type { paths } from './schema';
import type { ProblemDetails } from './types';

export class MiruApiError extends Error {
	readonly status: number;
	readonly title: string;
	readonly detail: string;
	readonly fieldErrors?: Record<string, string>;

	constructor(status: number, problem?: Partial<ProblemDetails>) {
		const detail = problem?.detail || problem?.title || `Request failed (${status})`;
		super(detail);
		this.name = 'MiruApiError';
		this.status = status;
		this.title = problem?.title || 'Error';
		this.detail = detail;
		this.fieldErrors = problem?.errors;
	}
}

export const api = createClient<paths>({
	baseUrl: '',
	credentials: 'include'
});

export function unwrap<T>(result: {
	data?: T;
	error?: unknown;
	response: Response;
}): T {
	if (result.error !== undefined || result.data === undefined) {
		const problem =
			result.error && typeof result.error === 'object'
				? (result.error as Partial<ProblemDetails>)
				: undefined;
		throw new MiruApiError(result.response.status, problem);
	}
	return result.data;
}

export const AVATAR_MAX_BYTES = 512 * 1024;
export const ALLOWED_AVATAR_MIME_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);

export function validateAvatarFile(file: File): string | null {
	const mime = file.type.trim().toLowerCase();
	if (!ALLOWED_AVATAR_MIME_TYPES.has(mime)) {
		return 'Avatar must be a PNG, JPEG, or WebP image.';
	}
	if (file.size <= 0) {
		return 'Avatar image must not be empty.';
	}
	if (file.size > AVATAR_MAX_BYTES) {
		return 'Avatar image must be 512 KiB or smaller.';
	}
	return null;
}

export async function uploadBinaryAvatar<T>(path: string, file: File): Promise<T> {
	const validationError = validateAvatarFile(file);
	if (validationError) {
		throw new MiruApiError(400, { title: 'Invalid avatar image', detail: validationError });
	}
	const res = await fetch(path, {
		method: 'PUT',
		credentials: 'include',
		headers: {
			'Content-Type': file.type.trim().toLowerCase()
		},
		body: file
	});
	if (!res.ok) {
		let problem: Partial<ProblemDetails> | undefined;
		try {
			problem = (await res.json()) as Partial<ProblemDetails>;
		} catch {
			problem = undefined;
		}
		throw new MiruApiError(res.status, problem);
	}
	return (await res.json()) as T;
}
