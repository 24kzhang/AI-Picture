import { request } from '@/request'
import type { AgentResponse, AgentSessionVO, PictureCollaborationContext } from './agentController'

const path = (id: string | number) => `/api/picture/${id}/collaboration`
export const getPictureCollaboration = (id: string | number) => request<AgentResponse<AgentSessionVO>>(path(id))
export const changePictureLease = (id: string | number, action: string, context: PictureCollaborationContext, targetId?: string) =>
  request<AgentResponse<AgentSessionVO>>(`${path(id)}/lease`, { method: 'POST', data: { ...context, action, targetId } })
export const prepareManualCanvas = (id: string | number, context: PictureCollaborationContext) =>
  request<AgentResponse<AgentSessionVO>>(`${path(id)}/manual`, { method: 'POST', headers: { 'X-Picture-Collaboration': JSON.stringify(context) } })
export function savePictureCanvas(id: string | number, context: PictureCollaborationContext, state: unknown, blob: Blob, idempotencyKey: string) {
  const form = new FormData()
  form.append('image', blob, 'canvas.png')
  form.append('state', JSON.stringify(state))
  form.append('context', JSON.stringify({ ...context, idempotencyKey }))
  return request<AgentResponse<AgentSessionVO>>(`${path(id)}/canvas`, { method: 'POST', data: form })
}
