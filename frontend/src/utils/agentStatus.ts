import type { AgentRunEvent, AgentTurn } from '@/api/agent'

/**
 * Agent 状态与事件的纯函数工具：便于单元测试，避免逻辑散落在组件里。
 */

/** 运行是否终态 */
export function isTerminalRun(status?: string | null): boolean {
  return status === 'succeeded' || status === 'failed' || status === 'canceled'
}

/** 运行状态中文文案 */
export function runStatusText(status?: string | null): string {
  const map: Record<string, string> = {
    pending: '准备中',
    waiting: '等待确认计划',
    queued: '排队中',
    running: '执行中',
    succeeded: '已完成',
    failed: '执行失败',
    canceled: '已取消',
  }
  return status ? map[status] ?? status : '-'
}

/** 会话状态中文文案 */
export function sessionStatusText(status?: string | null): string {
  const map: Record<string, string> = {
    DRAFT: '准备中',
    ACTIVE: '制作中',
    READY_TO_COMMIT: '待提交',
    COMMITTING: '提交中',
    COMMITTED: '已提交',
    CANCELED: '已取消',
    EXPIRED: '已过期',
    CONFLICT: '版本冲突',
  }
  return status ? map[status] ?? status : '-'
}

/** 会话状态标签颜色 */
export function sessionStatusColor(status?: string | null): string {
  switch (status) {
    case 'ACTIVE':
      return 'processing'
    case 'READY_TO_COMMIT':
      return 'blue'
    case 'COMMITTED':
      return 'green'
    case 'CONFLICT':
      return 'red'
    default:
      return 'default'
  }
}

/** 素材类型文案 */
export function assetKindText(kind?: string | null): string {
  const map: Record<string, string> = {
    original: '原图',
    generated: '生成',
    subject: '主体',
    background: '背景',
    mask: '选区',
    marketing: '营销图',
    export: '导出',
  }
  return kind ? map[kind] ?? kind : '-'
}

/** 版本来源文案 */
export function versionSourceText(source?: string | null): string {
  const map: Record<string, string> = {
    UPLOAD: '上传',
    QUICK_EDIT: '快捷编辑',
    AGENT: 'Agent 精修',
    RESTORE: '版本恢复',
  }
  return source ? map[source] ?? source : '-'
}

/** 批量逐项状态文案与颜色 */
export function batchItemStatusText(status: string): string {
  const map: Record<string, string> = {
    pending: '待处理',
    waiting: '待处理',
    queued: '排队中',
    running: '处理中',
    succeeded: '已提交',
    failed: '失败',
    conflict: '版本冲突',
    canceled: '已取消',
  }
  return map[status] ?? status
}

export function batchItemStatusColor(status: string): string {
  if (status === 'succeeded') {
    return 'green'
  }
  if (status === 'conflict') {
    return 'orange'
  }
  if (status === 'failed') {
    return 'red'
  }
  if (status === 'running' || status === 'queued') {
    return 'processing'
  }
  return 'default'
}

/**
 * 将运行事件合并进对话轮次列表：按 runId 更新，事件去重（同 eventId 只应用一次）。
 * 返回 [新的轮次列表, 是否应用了事件]。
 */
export function mergeRunEventInto(
  turns: AgentTurn[],
  event: AgentRunEvent,
  appliedEventIds: Set<string>,
): [AgentTurn[], boolean] {
  if (event.eventId && appliedEventIds.has(event.eventId)) {
    return [turns, false]
  }
  if (event.eventId) {
    appliedEventIds.add(event.eventId)
  }
  let applied = false
  const next = turns.map((turn) => {
    if (String(turn.id) !== String(event.runId)) {
      return turn
    }
    applied = true
    return {
      ...turn,
      status: event.status ?? turn.status,
      progress: event.progress ?? turn.progress,
      stage: event.stage ?? turn.stage,
      steps: event.steps ?? turn.steps,
    }
  })
  return [next, applied]
}
