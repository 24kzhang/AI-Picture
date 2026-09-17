import type { AxiosResponse } from 'axios'
import { request } from '@/request'

/**
 * Agent 智能精修 API 客户端（手写维护，不走 openapi 生成物）
 */

export interface AgentAsset {
  id: string
  kind: string
  source: string
  image_format: string
  width: number
  height: number
  size_bytes: number
  has_alpha: boolean
  created_at: string
  url: string
}

export interface AgentLayerTransform {
  x: number
  y: number
  scale_x: number
  scale_y: number
  rotation: number
}

export interface AgentLayer {
  id: string
  kind: string
  name: string
  width: number
  height: number
  asset_id: string
  transform: AgentLayerTransform
  opacity: number
  visible: boolean
  locked: boolean
  text?: string | null
  font_size?: number
  fill?: string
  source_hash?: string | null
}

export interface AgentDocument {
  width: number
  height: number
  layers: AgentLayer[]
}

export interface AgentSessionDetail {
  id: string
  title: string
  revision: number
  history_seq: number
  original_asset_id: string
  current_asset_id: string
  created_at: string
  updated_at: string
  document: AgentDocument
  previous_document?: AgentDocument | null
  assets: AgentAsset[]
  can_undo: boolean
  can_redo: boolean
}

export interface AgentPlanStep {
  id: string
  tool: string
  label: string
  depends_on: string[]
  run_id?: string | null
  status: string
}

export interface AgentTurn {
  id: string | number
  agentRunId: string
  runType: string
  status: string
  progress?: number
  stage?: string | null
  goal?: string | null
  reply?: string | null
  error?: string | null
  steps: AgentPlanStep[]
  createTime?: string
}

export interface AgentLease {
  mode: string
  userId: string | number
  sessionId: string
  acquiredAt: string | number
}

export interface AgentSession {
  id: string | number
  agentSessionId: string
  pictureId: string | number
  spaceId?: string | number | null
  baseEditVersion: string | number
  status: string
  finalAgentAssetId?: string | null
  committedVersionId?: string | number | null
  createTime?: string
  updateTime?: string
  expireTime?: string
  readOnly: boolean
  lease?: AgentLease | null
  canvas?: AgentSessionDetail | null
  turns: AgentTurn[]
}

export interface AgentSelection {
  revision: number
  mask: AgentAsset
  markers?: { index: number; x: number; y: number }[]
}

export interface PictureVersion {
  id: string | number
  pictureId: string | number
  versionNo: string | number
  url: string
  thumbnailUrl?: string
  picSize?: string | number
  picWidth?: number
  picHeight?: number
  picFormat?: string
  picColor?: string
  source: string
  operatorId?: string | number
  createTime?: string
}

export interface AgentRunEvent {
  eventId: string
  runId: string | number
  agentRunId?: string
  status: string
  stage?: string | null
  progress?: number
  message?: string | null
  timestamp: number
  goal?: string | null
  reply?: string | null
  steps?: AgentPlanStep[]
}

interface BaseResponse<T> {
  code: number
  data: T
  message?: string
}

/** 统一解包 BaseResponse；业务错误抛出带中文消息的异常 */
async function unwrap<T>(promise: Promise<AxiosResponse<BaseResponse<T>>>): Promise<T> {
  const response = await promise
  const body = response.data
  if (body.code !== 0) {
    throw new Error(body.message || 'Agent 服务请求失败')
  }
  return body.data as T
}

/** 创建（或复用）编辑会话，支持幂等键 */
export function createAgentSession(pictureId: string | number, idempotencyKey?: string) {
  return unwrap<AgentSession>(
    request.post(
      `/api/picture/${pictureId}/agent-sessions`,
      {},
      idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined,
    ),
  )
}

/** 查询会话详情（画布、最近对话、租约） */
export function getAgentSession(sessionId: string | number) {
  return unwrap<AgentSession>(request.get(`/api/agent-sessions/${sessionId}`))
}

/** 取消会话 */
export function cancelAgentSession(sessionId: string | number) {
  return unwrap<boolean>(request.post(`/api/agent-sessions/${sessionId}/cancel`))
}

/** 编辑租约心跳续租 */
export function leaseHeartbeat(sessionId: string | number) {
  return unwrap<boolean>(request.post(`/api/agent-sessions/${sessionId}/lease/heartbeat`))
}

/** 发送自然语言指令（可能返回多步计划） */
export function sendAgentMessage(sessionId: string | number, text: string) {
  return unwrap<AgentTurn>(request.post(`/api/agent-sessions/${sessionId}/messages`, { text }))
}

/** 调用单步工具 */
export function invokeAgentTool(sessionId: string | number, tool: string, params: Record<string, unknown>) {
  return unwrap<AgentTurn>(
    request.post(`/api/agent-sessions/${sessionId}/tools`, { tool, params }),
  )
}

/** 查询运行快照 */
export function getAgentRun(runId: string | number) {
  return unwrap<AgentTurn>(request.get(`/api/agent-runs/${runId}`))
}

/** 确认多步计划 */
export function confirmAgentRun(runId: string | number) {
  return unwrap<AgentTurn>(request.post(`/api/agent-runs/${runId}/confirm`))
}

/** 取消多步计划 */
export function cancelAgentRun(runId: string | number) {
  return unwrap<AgentTurn>(request.post(`/api/agent-runs/${runId}/cancel`))
}

/** 重试失败步骤 */
export function retryAgentRun(runId: string | number) {
  return unwrap<AgentTurn>(request.post(`/api/agent-runs/${runId}/retry`))
}

/** 预热选区模型 */
export function prepareAgentSelection(sessionId: string | number) {
  return unwrap<boolean>(request.post(`/api/agent-sessions/${sessionId}/selection/prepare`))
}

/** 创建选区（点选 / 笔刷） */
export function setAgentSelection(
  sessionId: string | number,
  payload: {
    revision: number
    points?: { x: number; y: number }[]
    strokes?: { x: number; y: number }[][]
    radius?: number
    append?: boolean
  },
) {
  return unwrap<AgentSelection>(request.post(`/api/agent-sessions/${sessionId}/selection`, payload))
}

/** 查询当前选区（无选区返回 null） */
export function getAgentSelection(sessionId: string | number) {
  return unwrap<AgentSelection | null>(request.get(`/api/agent-sessions/${sessionId}/selection`))
}

/** 清除选区 */
export function deleteAgentSelection(sessionId: string | number) {
  return unwrap<boolean>(request.delete(`/api/agent-sessions/${sessionId}/selection`))
}

/** 撤销 */
export function undoAgentSession(sessionId: string | number) {
  return unwrap<AgentSessionDetail>(request.post(`/api/agent-sessions/${sessionId}/undo`))
}

/** 重做 */
export function redoAgentSession(sessionId: string | number) {
  return unwrap<AgentSessionDetail>(request.post(`/api/agent-sessions/${sessionId}/redo`))
}

/** 选定最终草稿，进入待提交 */
export function setFinalAgentAsset(sessionId: string | number, assetId: string) {
  return unwrap<AgentSession>(
    request.post(`/api/agent-sessions/${sessionId}/final-asset`, { assetId }),
  )
}

/** 确认并替换原图（乐观锁） */
export function commitAgentSession(sessionId: string | number, expectedEditVersion: string | number) {
  return unwrap<PictureVersion>(
    request.post(`/api/agent-sessions/${sessionId}/commit`, { expectedEditVersion }),
  )
}

/** 图片版本历史 */
export function listPictureVersions(pictureId: string | number) {
  return unwrap<PictureVersion[]>(request.get(`/api/picture/${pictureId}/versions`))
}

/** 恢复历史版本（创建新版本，不回退版本号） */
export function restorePictureVersion(
  pictureId: string | number,
  versionId: string | number,
  expectedEditVersion: string | number,
) {
  return unwrap<PictureVersion>(
    request.post(`/api/picture/${pictureId}/versions/${versionId}/restore`, { expectedEditVersion }),
  )
}

// ---------------------------------------------------------------------------
// 批量处理与导出
// ---------------------------------------------------------------------------

export interface AgentBatchItem {
  pictureId: string | number
  pictureName?: string
  sourceAssetId?: string
  baseEditVersion?: string | number
  status: string
  error?: string | null
  outputAssetIds?: string[]
  outputUrls?: string[]
  versionId?: string | number | null
  versionNo?: string | number | null
}

export interface AgentBatch {
  batchId: string
  status: string
  progress?: number
  stage?: string | null
  error?: string | null
  canceled?: boolean
  items: AgentBatchItem[]
}

export interface AgentExport {
  exportId: string
  filename: string
  downloadUrl: string
}

/** 批量操作的可用工具（与 Agent 批量服务保持一致） */
export const AGENT_BATCH_OPERATIONS: { value: string; label: string }[] = [
  { value: 'remove_background', label: '去背景' },
  { value: 'replace_background', label: '换背景' },
  { value: 'adjust_image', label: '调色' },
  { value: 'upscale_image', label: '超分' },
  { value: 'expand_canvas', label: '扩图' },
  { value: 'prepare_delivery_sizes', label: '投放尺寸' },
]

/** 创建批量任务（最多 20 张） */
export function createAgentBatch(payload: {
  pictureIds: (string | number)[]
  operations: string[]
  formats: string[]
}) {
  return unwrap<AgentBatch>(request.post('/api/agent-batches', payload))
}

/** 查询批量任务 */
export function getAgentBatch(batchId: string) {
  return unwrap<AgentBatch>(request.get(`/api/agent-batches/${batchId}`))
}

/** 逐项确认批量结果 */
export function confirmAgentBatch(batchId: string) {
  return unwrap<AgentBatchItem[]>(request.post(`/api/agent-batches/${batchId}/confirm`))
}

/** 取消批量任务 */
export function cancelAgentBatch(batchId: string) {
  return unwrap<boolean>(request.post(`/api/agent-batches/${batchId}/cancel`))
}

/** 创建导出凭证（会话或批量） */
export function createAgentExport(payload: {
  sessionId?: string | number
  batchId?: string
  assetIds?: string[]
}) {
  return unwrap<AgentExport>(request.post('/api/agent-exports', payload))
}
