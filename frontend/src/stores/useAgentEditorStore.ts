import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  cancelAgentRun,
  cancelAgentSession,
  commitAgentSession,
  confirmAgentRun,
  createAgentSession,
  deleteAgentSelection,
  getAgentRun,
  getAgentSelection,
  getAgentSession,
  invokeAgentTool,
  leaseHeartbeat,
  listPictureVersions,
  prepareAgentSelection,
  redoAgentSession,
  restorePictureVersion,
  retryAgentRun,
  sendAgentMessage,
  setAgentSelection,
  setFinalAgentAsset,
  undoAgentSession,
  type AgentRunEvent,
  type AgentSelection,
  type AgentSession,
  type AgentSessionDetail,
  type AgentTurn,
  type PictureVersion,
} from '@/api/agent'
import { openRunEvents } from '@/utils/agentRunEventSource'
import { isTerminalRun, mergeRunEventInto } from '@/utils/agentStatus'

/** 运行是否终态 */
function isTerminal(status?: string | null) {
  return isTerminalRun(status)
}

const HEARTBEAT_INTERVAL_MS = 20000

/**
 * Agent 智能精修编辑器状态：会话、画布、运行、选区、租约与版本。
 */
export const useAgentEditorStore = defineStore('agentEditor', () => {
  const pictureId = ref<string | number | null>(null)
  const session = ref<AgentSession | null>(null)
  const selection = ref<AgentSelection | null>(null)
  const versions = ref<PictureVersion[]>([])
  const loading = ref(false)
  const submitting = ref(false)
  const sseConnected = ref(false)
  const leaseValid = ref(true)
  const lastError = ref<string | null>(null)
  const activeRunId = ref<string | number | null>(null)
  const activeRunStatus = ref<string | null>(null)
  const activeRunProgress = ref(0)
  const activeRunStage = ref<string | null>(null)

  let heartbeatTimer: number | null = null
  let closeEvents: (() => void) | null = null
  let pollTimer: number | null = null
  /** 已应用的事件 id（同一事件只合并一次） */
  const appliedEventIds = new Set<string>()

  const canvas = computed<AgentSessionDetail | null>(() => session.value?.canvas ?? null)
  const turns = computed<AgentTurn[]>(() => session.value?.turns ?? [])
  const readOnly = computed(() => Boolean(session.value?.readOnly))
  const status = computed(() => session.value?.status ?? '')
  const draftAssetId = computed(() => session.value?.finalAgentAssetId ?? null)
  const expectedEditVersion = computed(() => session.value?.baseEditVersion ?? 0)
  const hasUnsubmittedChanges = computed(() => {
    const current = canvas.value
    if (!current) {
      return false
    }
    return current.revision > 1 || Boolean(draftAssetId.value)
  })
  const running = computed(() => activeRunId.value !== null && !isTerminal(activeRunStatus.value))
  const canEdit = computed(() => !readOnly.value && leaseValid.value && !running.value)
  const canCommit = computed(
    () => canEdit.value && status.value === 'READY_TO_COMMIT' && Boolean(draftAssetId.value),
  )

  function fail(error: unknown) {
    lastError.value = error instanceof Error ? error.message : '操作失败'
    return Promise.reject(error)
  }

  function applySession(next: AgentSession | null) {
    session.value = next
  }

  function applyCanvas(next: AgentSessionDetail | null) {
    if (session.value && next) {
      session.value = { ...session.value, canvas: next }
    }
  }

  function applyTurn(turn: AgentTurn) {
    if (!session.value) {
      return
    }
    const list = [...session.value.turns]
    const index = list.findIndex((item) => String(item.id) === String(turn.id))
    if (index >= 0) {
      list[index] = { ...list[index], ...turn }
    } else {
      list.push(turn)
    }
    session.value = { ...session.value, turns: list }
  }

  function applyRunEvent(event: AgentRunEvent) {
    activeRunStatus.value = event.status
    activeRunProgress.value = event.progress ?? activeRunProgress.value
    activeRunStage.value = event.stage ?? null
    if (session.value) {
      const [next, applied] = mergeRunEventInto(session.value.turns, event, appliedEventIds)
      if (applied) {
        session.value = { ...session.value, turns: next }
      }
    }
  }

  /** 订阅运行事件；异常时降级为快照轮询，不伪造成功状态 */
  function watchRun(runId: string | number, onDone?: (event: AgentRunEvent | null) => void) {
    unwatchRun()
    activeRunId.value = runId
    activeRunStatus.value = null
    activeRunProgress.value = 0
    activeRunStage.value = null
    closeEvents = openRunEvents(runId, {
      onOpen: () => {
        sseConnected.value = true
      },
      onSnapshot: (event) => {
        applyRunEvent(event)
        if (isTerminal(event.status)) {
          finishRun(event, onDone)
        }
      },
      onProgress: (event) => {
        applyRunEvent(event)
        if (isTerminal(event.status)) {
          finishRun(event, onDone)
        }
      },
      onError: () => {
        sseConnected.value = false
        if (isTerminal(activeRunStatus.value)) {
          return
        }
        // SSE 中断：轮询数据库快照直到终态
        if (pollTimer === null) {
          pollTimer = window.setInterval(async () => {
            try {
              const run = await getAgentRun(runId)
              applyTurn(run)
              if (isTerminal(run.status)) {
                finishRun({ ...emptyEvent(runId), status: run.status, progress: run.progress }, onDone)
              }
            } catch {
              // 保持重试，不改变状态
            }
          }, 3000)
        }
      },
    })
  }

  function emptyEvent(runId: string | number): AgentRunEvent {
    return { eventId: '', runId, status: 'running', timestamp: Date.now() }
  }

  function finishRun(event: AgentRunEvent, onDone?: (event: AgentRunEvent | null) => void) {
    unwatchRun()
    onDone?.(event)
    void refresh()
  }

  function unwatchRun() {
    closeEvents?.()
    closeEvents = null
    sseConnected.value = false
    if (pollTimer !== null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = window.setInterval(async () => {
      if (!session.value || readOnly.value) {
        return
      }
      try {
        await leaseHeartbeat(session.value.id)
        leaseValid.value = true
      } catch {
        leaseValid.value = false
      }
    }, HEARTBEAT_INTERVAL_MS)
  }

  function stopHeartbeat() {
    if (heartbeatTimer !== null) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  /** 进入工作台：创建或复用会话（刷新页面后可恢复） */
  async function open(targetPictureId: string | number) {
    loading.value = true
    lastError.value = null
    try {
      pictureId.value = targetPictureId
      const idempotencyKey = `agent-${targetPictureId}-${Date.now()}-${Math.random()
        .toString(36)
        .slice(2, 8)}`
      applySession(await createAgentSession(targetPictureId, idempotencyKey))
      leaseValid.value = Boolean(session.value?.lease)
      startHeartbeat()
      await loadVersions()
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '无法进入 Agent 工作台'
      throw error
    } finally {
      loading.value = false
    }
  }

  /** 重新拉取会话快照（终态以数据库为准） */
  async function refresh() {
    if (!session.value) {
      return
    }
    try {
      applySession(await getAgentSession(session.value.id))
      leaseValid.value = Boolean(session.value?.lease)
    } catch (error) {
      lastError.value = error instanceof Error ? error.message : '刷新会话失败'
    }
  }

  async function loadVersions() {
    if (pictureId.value === null) {
      return
    }
    try {
      versions.value = await listPictureVersions(pictureId.value)
    } catch {
      versions.value = []
    }
  }

  /** 发送自然语言指令；多步计划需确认，单步直接在后台执行 */
  async function sendMessage(text: string) {
    if (!session.value) {
      return null
    }
    submitting.value = true
    try {
      const turn = await sendAgentMessage(session.value.id, text)
      applyTurn(turn)
      if (turn.status === 'waiting') {
        // 待确认：不订阅事件
        return turn
      }
      if (turn.id) {
        watchRun(turn.id)
      }
      return turn
    } catch (error) {
      return fail(error)
    } finally {
      submitting.value = false
    }
  }

  /** 调用单步工具 */
  async function invokeTool(tool: string, params: Record<string, unknown>) {
    if (!session.value) {
      return null
    }
    submitting.value = true
    try {
      const turn = await invokeAgentTool(session.value.id, tool, params)
      applyTurn(turn)
      if (turn.id) {
        watchRun(turn.id)
      }
      return turn
    } catch (error) {
      return fail(error)
    } finally {
      submitting.value = false
    }
  }

  /** 确认多步计划 */
  async function confirmPlan(runId: string | number) {
    try {
      const turn = await confirmAgentRun(runId)
      applyTurn(turn)
      watchRun(runId)
      return turn
    } catch (error) {
      return fail(error)
    }
  }

  /** 取消多步计划 */
  async function cancelPlan(runId: string | number) {
    try {
      const turn = await cancelAgentRun(runId)
      applyTurn(turn)
      return turn
    } catch (error) {
      return fail(error)
    }
  }

  /** 重试失败步骤 */
  async function retryPlan(runId: string | number) {
    try {
      const turn = await retryAgentRun(runId)
      applyTurn(turn)
      watchRun(runId)
      return turn
    } catch (error) {
      return fail(error)
    }
  }

  /** 撤销 / 重做（画布历史） */
  async function undo() {
    if (!session.value) {
      return
    }
    try {
      applyCanvas(await undoAgentSession(session.value.id))
    } catch (error) {
      return fail(error)
    }
  }

  async function redo() {
    if (!session.value) {
      return
    }
    try {
      applyCanvas(await redoAgentSession(session.value.id))
    } catch (error) {
      return fail(error)
    }
  }

  /** 选区：预热、点选/笔刷、查询、清除 */
  async function prepareSelection() {
    if (!session.value) {
      return
    }
    try {
      await prepareAgentSelection(session.value.id)
    } catch (error) {
      return fail(error)
    }
  }

  async function createSelection(payload: {
    revision: number
    points?: { x: number; y: number }[]
    strokes?: { x: number; y: number }[][]
    radius?: number
    append?: boolean
  }) {
    if (!session.value) {
      return null
    }
    try {
      selection.value = await setAgentSelection(session.value.id, payload)
      return selection.value
    } catch (error) {
      return fail(error)
    }
  }

  async function loadSelection() {
    if (!session.value) {
      return
    }
    try {
      selection.value = await getAgentSelection(session.value.id)
    } catch {
      selection.value = null
    }
  }

  async function clearSelection() {
    if (!session.value) {
      return
    }
    try {
      await deleteAgentSelection(session.value.id)
      selection.value = null
    } catch (error) {
      return fail(error)
    }
  }

  /** 选定最终草稿（提交前置） */
  async function selectFinalAsset(assetId: string) {
    if (!session.value) {
      return
    }
    try {
      applySession(await setFinalAgentAsset(session.value.id, assetId))
    } catch (error) {
      return fail(error)
    }
  }

  /** 确认并替换原图（乐观锁；冲突时保留草稿） */
  async function commit() {
    if (!session.value) {
      return null
    }
    submitting.value = true
    try {
      const version = await commitAgentSession(session.value.id, expectedEditVersion.value)
      await refresh()
      await loadVersions()
      return version
    } catch (error) {
      await refresh()
      return fail(error)
    } finally {
      submitting.value = false
    }
  }

  /** 恢复历史版本 */
  async function restore(versionId: string | number) {
    if (pictureId.value === null) {
      return null
    }
    submitting.value = true
    try {
      const version = await restorePictureVersion(
        pictureId.value,
        versionId,
        expectedEditVersion.value,
      )
      await loadVersions()
      await refresh()
      return version
    } catch (error) {
      return fail(error)
    } finally {
      submitting.value = false
    }
  }

  /** 取消会话并释放租约 */
  async function cancelSession() {
    if (!session.value) {
      return
    }
    try {
      await cancelAgentSession(session.value.id)
      stopHeartbeat()
      await refresh()
    } catch (error) {
      return fail(error)
    }
  }

  /** 离开页面：停止心跳与订阅（会话保留以便刷新恢复） */
  function dispose() {
    stopHeartbeat()
    unwatchRun()
  }

  return {
    pictureId,
    session,
    selection,
    versions,
    loading,
    submitting,
    sseConnected,
    leaseValid,
    lastError,
    activeRunId,
    activeRunStatus,
    activeRunProgress,
    activeRunStage,
    canvas,
    turns,
    readOnly,
    status,
    draftAssetId,
    expectedEditVersion,
    hasUnsubmittedChanges,
    running,
    canEdit,
    canCommit,
    open,
    refresh,
    loadVersions,
    sendMessage,
    invokeTool,
    confirmPlan,
    cancelPlan,
    retryPlan,
    undo,
    redo,
    prepareSelection,
    createSelection,
    loadSelection,
    clearSelection,
    selectFinalAsset,
    commit,
    restore,
    cancelSession,
    dispose,
  }
})
