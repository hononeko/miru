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
