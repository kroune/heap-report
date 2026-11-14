import type { Result } from './types';

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status = 0,
    public readonly code = 'network',
    public readonly data?: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export async function request<T>(path: string, init?: RequestInit): Promise<Result<T>> {
  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (error) {
    return { ok: false, status: 0, code: 'network', error: error instanceof Error ? error.message : String(error) };
  }
  if (response.status === 204) return { ok: true, data: null as T };

  let body: unknown = null;
  try { body = await response.json(); } catch { /* Empty or non-JSON response. */ }
  if (response.ok) return { ok: true, data: body as T };

  const errorBody = body && typeof body === 'object' ? body as Record<string, unknown> : {};
  return {
    ok: false,
    status: response.status,
    code: typeof errorBody.code === 'string' ? errorBody.code : 'http',
    error: typeof errorBody.error === 'string' ? errorBody.error : `HTTP ${response.status}`,
    data: body,
  };
}

export const api = <T>(path: string) => request<T>(path);
export const apiPost = <T>(path: string, body: unknown = {}) => request<T>(path, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});
export const apiDelete = <T>(path: string) => request<T>(path, { method: 'DELETE' });

export function unwrap<T>(result: Result<T>): T {
  if (result.ok) return result.data;
  throw new ApiError(result.error, result.status, result.code, result.data);
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export function isNotAnalyzed(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}
