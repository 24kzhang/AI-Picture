import type { FastifyRequest } from 'fastify'
import { config, serviceKey } from './config.js'
import { ApiError } from './errors.js'
export type Scope = { space_id?: string | null; owner_id?: string }
export type Actor = { userId: string; userName: string; admin: boolean; scope: string; cookie: string }
export async function bridge<T>(cookie: string, path: string, body: unknown): Promise<T> {
  const response = await fetch(`${config.javaUrl}/internal/video${path}`, { method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Video-Service-Key': serviceKey, Cookie: cookie },
    body: JSON.stringify(body), signal: AbortSignal.timeout(15_000) })
  const envelope = await response.json() as { code: number; data: T; message: string }
  if (!response.ok || envelope.code !== 0) throw new ApiError(envelope.code === 40100 ? 401 : 403, 'AUTHORIZATION_FAILED', envelope.message || '图库授权失败')
  return envelope.data
}
export async function authorize(request: FastifyRequest, scope: Scope = {}, action = 'view'): Promise<Actor> {
  const cookie = request.headers.cookie || ''
  const actor = await bridge<Omit<Actor, 'cookie'>>(cookie, '/authorize', { spaceId: scope.space_id, ownerId: scope.owner_id, action })
  return { ...actor, cookie }
}
export async function ticket(actor: Actor, scope: Scope, key: string, method: string) {
  return bridge<{ url: string; key: string }>(actor.cookie, '/storage-ticket', { spaceId: scope.space_id, ownerId: scope.owner_id, key, method })
}
