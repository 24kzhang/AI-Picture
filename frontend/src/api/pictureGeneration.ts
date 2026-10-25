import { request } from '@/request'
import type { AgentResponse } from './agentController'
export interface ImageGenerationTask {
  id: string; status: 'QUEUED' | 'RUNNING' | 'TRANSFER' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'OUTPUT_PENDING'
  prompt: string; width: number; height: number; spaceId?: string; resultUrl?: string; errorMessage?: string; savedPictureId?: string
}
export interface ImageGenerationInput { prompt: string; width: number; height: number; spaceId?: string; idempotencyKey: string }
export const createImageGeneration = (data: ImageGenerationInput) => request<AgentResponse<ImageGenerationTask>>('/api/picture/generations', { method: 'POST', data })
export const getImageGeneration = (id: string) => request<AgentResponse<ImageGenerationTask>>(`/api/picture/generations/${id}`)
export const listImageGenerations = (spaceId?: string) => request<AgentResponse<ImageGenerationTask[]>>('/api/picture/generations', { params: { spaceId } })
export const retryImageTransfer = (id: string) => request<AgentResponse<ImageGenerationTask>>(`/api/picture/generations/${id}/transfer`, { method: 'POST' })
export const saveGeneratedPicture = (id: string, name: string) => request<AgentResponse<API.PictureVO>>(`/api/picture/generations/${id}/save`, { method: 'POST', data: { name } })
