export class ApiError extends Error {
  constructor(public statusCode: number, public code: string, message: string, public detail?: unknown) { super(message) }
}
export function invariant(condition: unknown, message: string, code = 'INVALID_OPERATION'): asserts condition {
  if (!condition) throw new ApiError(400, code, message)
}
