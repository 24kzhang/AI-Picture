import { request } from '@/request'

/** 后端 BaseResponse 泛型（避免 any） */
export interface AgentResponse<T> {
  code: number
  data: T
  message?: string
}

/**
 * Java 原生修图 Agent 接口封装（手写，与后端 agent 模块路由一一对应）
 */

// region 类型定义

export interface PlanStep {
  id: string
  tool: string
  params: Record<string, unknown>
  dependsOn: string[]
  toolRunId?: string
  status: string
  needsApproval?: boolean
}

export interface AgentToolRunVO {
  id: string
  agentRunId: string
  stepId: string
  tool: string
  toolLabel: string
  status: string
  progress: number
  stage?: string
  params?: Record<string, unknown>
  result?: Record<string, unknown>
  errorMessage?: string
  retries: number
  startedAt?: string
  finishedAt?: string
}

export interface AgentRunVO {
  id: string
  editSessionId: string
  revision: number
  goal?: string
  reply?: string
  status: string
  errorMessage?: string
  plan: PlanStep[]
  toolRuns: AgentToolRunVO[]
  createTime?: string
  updateTime?: string
}

export interface AgentAssetVO {
  id: string
  editSessionId: string
  kind: string
  source?: string
  url: string
  thumbnailUrl?: string
  width?: number
  height?: number
  sizeBytes?: number
  position: number
  createTime?: string
}

export interface ParamField {
  name: string
  type: string
  required: boolean
  defaultValue?: unknown
  min?: number
  max?: number
  minLength?: number
  maxLength?: number
  choices?: (string | number)[]
  pattern?: string
  itemType?: string
  itemChoices?: (string | number)[]
  description?: string
  agentHidden: boolean
}

export interface AgentToolVO {
  name: string
  label: string
  description: string
  queued: boolean
  needsApproval: boolean
  sessionRequired: boolean
  params: ParamField[]
}

export interface AgentSessionVO {
  id: string
  pictureId: string
  spaceId?: string
  userId: string
  baseEditVersion: string
  status: string
  revision: number
  historySeq: number
  finalAssetId?: string
  currentAssetId?: string
  committedVersionId?: string
  expireTime?: string
  document?: Record<string, unknown>
  assets: AgentAssetVO[]
  runs: AgentRunVO[]
  selection?: Record<string, unknown>
  lease?: Record<string, unknown>
  tools: AgentToolVO[]
  canUndo: boolean
  canRedo: boolean
}

export interface PictureVersionVO {
  id: string
  pictureId: string
  versionNo: number
  url: string
  thumbnailUrl?: string
  picSize?: number
  picWidth?: number
  picHeight?: number
  picFormat?: string
  source: string
  sourceSessionId?: string
  operatorId?: string
  createTime?: string
}

// endregion

/** prepare 选区返回结构 */
export interface PrepareSelectionResult {
  maskAssetId: string
  maskUrl: string
  width: number
  height: number
  revision: number
}

// region 会话

/** 创建或复用 Agent 编辑会话 POST /api/picture/{pictureId}/agent-sessions */
export async function createAgentSession(pictureId: string | number) {
  return request<AgentResponse<AgentSessionVO>>(`/api/picture/${pictureId}/agent-sessions`, { method: 'POST' })
}

/** 会话快照 GET /api/agent-sessions/{sessionId} */
export async function getAgentSession(sessionId: string | number) {
  return request<AgentResponse<AgentSessionVO>>(`/api/agent-sessions/${sessionId}`, { method: 'GET' })
}

/** 发送自然语言指令 POST /api/agent-sessions/{sessionId}/messages */
export async function sendAgentMessage(sessionId: string | number, message: string) {
  return request<AgentResponse<AgentRunVO>>(`/api/agent-sessions/${sessionId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { message },
  })
}

/** 直接调用工具 POST /api/agent-sessions/{sessionId}/tools */
export async function submitAgentTool(
  sessionId: string | number,
  tool: string,
  params: Record<string, unknown>,
) {
  return request<AgentResponse<AgentRunVO>>(`/api/agent-sessions/${sessionId}/tools`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { tool, params },
  })
}

// endregion

// region 运行

export async function confirmAgentRun(runId: string | number) {
  return request<AgentResponse<AgentRunVO>>(`/api/agent-runs/${runId}/confirm`, { method: 'POST' })
}

export async function cancelAgentRun(runId: string | number) {
  return request<AgentResponse<AgentRunVO>>(`/api/agent-runs/${runId}/cancel`, { method: 'POST' })
}

export async function retryAgentRun(runId: string | number) {
  return request<AgentResponse<AgentRunVO>>(`/api/agent-runs/${runId}/retry`, { method: 'POST' })
}

export async function getAgentRun(runId: string | number) {
  return request<AgentResponse<AgentRunVO>>(`/api/agent-runs/${runId}`, { method: 'GET' })
}

/** SSE 事件流地址（EventSource 使用） */
export function agentRunEventsUrl(runId: string | number) {
  const base = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
  return `${base}/api/agent-runs/${runId}/events`
}

// endregion

// region 选区 / 撤销重做 / 资产

export async function prepareAgentSelection(sessionId: string | number, maskBlob: Blob) {
  const formData = new FormData()
  formData.append('mask', maskBlob, 'mask.png')
  return request<AgentResponse<PrepareSelectionResult>>(`/api/agent-sessions/${sessionId}/selection/prepare`, {
    method: 'POST',
    data: formData,
  })
}

export async function saveAgentSelection(
  sessionId: string | number,
  data: { maskAssetId?: string; markers?: unknown[]; revision?: number },
) {
  return request<AgentResponse<boolean>>(`/api/agent-sessions/${sessionId}/selection`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data,
  })
}

export async function getAgentSelection(sessionId: string | number) {
  return request<AgentResponse<Record<string, unknown> | null>>(`/api/agent-sessions/${sessionId}/selection`, { method: 'GET' })
}

export async function clearAgentSelection(sessionId: string | number) {
  return request<AgentResponse<boolean>>(`/api/agent-sessions/${sessionId}/selection`, { method: 'DELETE' })
}

export async function undoAgentSession(sessionId: string | number) {
  return request<AgentResponse<AgentSessionVO>>(`/api/agent-sessions/${sessionId}/undo`, { method: 'POST' })
}

export async function redoAgentSession(sessionId: string | number) {
  return request<AgentResponse<AgentSessionVO>>(`/api/agent-sessions/${sessionId}/redo`, { method: 'POST' })
}

export async function adoptAgentAsset(sessionId: string | number, assetId: string | number) {
  return request<AgentResponse<AgentSessionVO>>(`/api/agent-sessions/${sessionId}/assets/${assetId}/adopt`, {
    method: 'POST',
  })
}

export async function setAgentFinalAsset(sessionId: string | number, assetId: string | number) {
  return request<AgentResponse<AgentSessionVO>>(`/api/agent-sessions/${sessionId}/final-asset`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { assetId },
  })
}

// endregion

// region 租约

export async function renewAgentLease(sessionId: string | number) {
  return request<AgentResponse<Record<string, unknown>>>(`/api/agent-sessions/${sessionId}/lease/renew`, { method: 'POST' })
}

export async function releaseAgentLease(sessionId: string | number) {
  return request<AgentResponse<boolean>>(`/api/agent-sessions/${sessionId}/lease/release`, { method: 'POST' })
}

// endregion

// region 版本 / 导出

export async function commitAgentSession(
  sessionId: string | number,
  finalAssetId: string | number,
  expectedEditVersion: string | number,
) {
  return request<AgentResponse<PictureVersionVO>>(`/api/agent-sessions/${sessionId}/commit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { finalAssetId, expectedEditVersion },
  })
}

export async function listPictureVersions(pictureId: string | number) {
  return request<AgentResponse<PictureVersionVO[]>>(`/api/picture/${pictureId}/versions`, { method: 'GET' })
}

export async function restorePictureVersion(
  pictureId: string | number,
  versionId: string | number,
  expectedEditVersion: string | number,
) {
  return request<AgentResponse<PictureVersionVO>>(`/api/picture/${pictureId}/versions/${versionId}/restore`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { expectedEditVersion },
  })
}

/** 导出资产 ZIP（直接触发浏览器下载） */
export async function exportAgentZip(sessionId: string | number, kind = 'delivery') {
  const base = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
  const response = await fetch(
    `${base}/api/agent-sessions/${sessionId}/export/zip?kind=${encodeURIComponent(kind)}`,
    { method: 'POST', credentials: 'include' },
  )
  if (!response.ok) {
    throw new Error(`导出失败：HTTP ${response.status}`)
  }
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `agent-assets-${sessionId}.zip`
  link.click()
  URL.revokeObjectURL(url)
}

export async function getAgentTools() {
  return request<AgentResponse<AgentToolVO[]>>(`/api/agent/tools`, { method: 'GET' })
}

// endregion
