<template>
  <div id="agentWorkbenchPage">
    <a-spin :spinning="initializing" tip="正在打开 Agent 工作台...">
      <template v-if="session">
        <div class="workbench-header">
          <a-space>
            <a-button size="small" @click="goBack">返回详情</a-button>
            <span class="session-title">Agent 精修 · 图片 #{{ pictureId }}</span>
            <a-tag :color="statusColor">{{ statusText }}</a-tag>
            <a-tag v-if="session.lease" color="blue">
              编辑租约：{{ session.lease.mode === 'AGENT' ? 'Agent 精修' : '快捷编辑' }}
            </a-tag>
            <a-tag>revision {{ session.revision }}</a-tag>
            <a-button v-if="session.selection" size="small" @click="clearSelection">
              清除选区
            </a-button>
          </a-space>
          <a-space>
            <a-button size="small" :disabled="!editable || !session.canUndo" @click="doUndo">
              撤销
            </a-button>
            <a-button size="small" :disabled="!editable || !session.canRedo" @click="doRedo">
              重做
            </a-button>
            <a-button size="small" :disabled="!editable" @click="versionOpen = true">
              版本管理
            </a-button>
            <a-button size="small" type="primary" :disabled="!editable" @click="versionOpen = true">
              提交正式版本
            </a-button>
          </a-space>
        </div>

        <div class="workbench-body">
          <div class="side-panel">
            <AgentLayerPanel
              :document="session.document"
              :editable="editable"
              @visible="onLayerVisible"
              @opacity="onLayerOpacity"
              @reorder="onLayerReorder"
            />
            <div class="tool-panel">
              <div class="panel-title">工具</div>
              <div class="tool-grid">
                <a-button
                  v-for="tool in session.tools"
                  :key="tool.name"
                  size="small"
                  :disabled="!editable || running"
                  @click="openToolModal(tool)"
                >
                  {{ tool.label }}
                </a-button>
              </div>
            </div>
          </div>

          <div class="center-panel">
            <AgentCanvas
              :document="session.document"
              :editable="editable"
              :selection-revision="selectionRevision"
              @selection="onSelection"
            />
            <div class="wall-panel">
              <AgentAssetWall
                :assets="session.assets"
                :current-asset-id="session.currentAssetId"
                :final-asset-id="session.finalAssetId"
                :editable="editable"
                @adopt="onAdopt"
                @set-final="onSetFinal"
                @preview="onPreview"
                @export="onExportZip"
              />
            </div>
          </div>

          <div class="chat-panel-wrap">
            <AgentChatPanel
              :messages="messages"
              :sending="sending"
              :acting-run-id="actingRunId"
              :editable="editable"
              @send="onSendMessage"
              @confirm="onConfirmRun"
              @cancel="onCancelRun"
              @retry="onRetryRun"
            />
          </div>
        </div>

        <!-- 工具参数弹窗 -->
        <a-modal
          v-model:open="toolModalOpen"
          :title="activeTool ? `${activeTool.label}（${activeTool.name}）` : ''"
          :confirm-loading="sending"
          @ok="submitActiveTool"
        >
          <p v-if="activeTool?.description" class="tool-desc">{{ activeTool.description }}</p>
          <a-form layout="vertical">
            <a-form-item
              v-for="field in visibleParams"
              :key="field.name"
              :label="field.name"
              :required="field.required"
            >
              <template v-if="field.choices">
                <a-select v-model:value="toolParams[field.name]" :placeholder="field.description">
                  <a-select-option v-for="choice in field.choices" :key="choice" :value="choice">
                    {{ choice }}
                  </a-select-option>
                </a-select>
              </template>
              <template v-else-if="field.type === 'boolean'">
                <a-switch v-model:checked="toolParams[field.name]" />
              </template>
              <template v-else-if="field.type === 'number' || field.type === 'integer'">
                <a-slider
                  v-if="field.min !== undefined && field.max !== undefined && field.max <= 4"
                  v-model:value="toolParams[field.name]"
                  :min="field.min"
                  :max="field.max"
                  :step="field.type === 'integer' ? 1 : 0.05"
                />
                <a-input-number
                  v-else
                  v-model:value="toolParams[field.name]"
                  :min="field.min"
                  :max="field.max"
                  style="width: 100%"
                />
                <div class="field-desc">{{ field.description }}</div>
              </template>
              <template v-else-if="field.type === 'array' && field.itemChoices">
                <a-select
                  v-model:value="toolParams[field.name]"
                  mode="multiple"
                  :placeholder="field.description"
                >
                  <a-select-option v-for="choice in field.itemChoices" :key="choice" :value="choice">
                    {{ choice }}
                  </a-select-option>
                </a-select>
              </template>
              <template v-else-if="field.type === 'array' || field.type === 'object'">
                <a-textarea
                  v-model:value="toolParams[field.name]"
                  :rows="3"
                  :placeholder="`${field.description ?? ''}（JSON）`"
                />
              </template>
              <template v-else>
                <a-input
                  v-model:value="toolParams[field.name]"
                  :placeholder="field.description"
                />
              </template>
            </a-form-item>
          </a-form>
        </a-modal>

        <AgentVersionDialog
          :open="versionOpen"
          :picture-id="pictureId"
          :session-id="session.id"
          :final-asset="finalAsset"
          :expected-edit-version="session.baseEditVersion"
          :can-edit="editable"
          @close="versionOpen = false"
          @committed="onCommitted"
          @restored="onRestored"
        />

        <a-image
          :src="previewUrl"
          :preview="{ visible: previewVisible, onVisibleChange: (v: boolean) => (previewVisible = v) }"
          style="display: none"
        />
      </template>

      <a-result v-else-if="!initializing" status="error" title="无法打开 Agent 工作台">
        <template #extra>
          <a-button type="primary" @click="goBack">返回图片详情</a-button>
        </template>
      </a-result>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import AgentCanvas, {
  type CanvasLayer,
  type SelectionMarker,
} from '@/components/agent/AgentCanvas.vue'
import AgentChatPanel, { type ChatMessage } from '@/components/agent/AgentChatPanel.vue'
import AgentLayerPanel from '@/components/agent/AgentLayerPanel.vue'
import AgentAssetWall from '@/components/agent/AgentAssetWall.vue'
import AgentVersionDialog from '@/components/agent/AgentVersionDialog.vue'
import {
  adoptAgentAsset,
  agentRunEventsUrl,
  cancelAgentRun,
  clearAgentSelection,
  confirmAgentRun,
  createAgentSession,
  exportAgentZip,
  getAgentRun,
  getAgentSession,
  prepareAgentSelection,
  redoAgentSession,
  releaseAgentLease,
  renewAgentLease,
  retryAgentRun,
  saveAgentSelection,
  sendAgentMessage,
  setAgentFinalAsset,
  submitAgentTool,
  undoAgentSession,
  type AgentAssetVO,
  type AgentRunVO,
  type AgentSessionVO,
  type AgentToolVO,
  type ParamField,
} from '@/api/agentController'

const props = defineProps<{ pictureId: string }>()

const router = useRouter()

const initializing = ref(true)
const session = ref<AgentSessionVO | null>(null)
const sending = ref(false)
const actingRunId = ref<string>('')
const versionOpen = ref(false)
const previewVisible = ref(false)
const previewUrl = ref('')

const messages = ref<ChatMessage[]>([])
const eventSources = new Map<string, EventSource>()
let leaseTimer: number | undefined

const editable = computed(() => session.value?.status === 'active')
const running = computed(() =>
  messages.value.some(
    (msg) => msg.run && ['planning', 'waiting', 'running'].includes(msg.run.status),
  ),
)
const selectionRevision = computed<number | undefined>(() => {
  const value = session.value?.selection?.revision
  return value === undefined || value === null ? undefined : Number(value)
})
const finalAsset = computed(() =>
  (session.value?.assets ?? []).find((item) => String(item.id) === String(session.value?.finalAssetId ?? '')) ?? null,
)

const statusText = computed(() => {
  switch (session.value?.status) {
    case 'active':
      return '进行中'
    case 'committed':
      return '已提交'
    case 'conflict':
      return '版本冲突（草稿保留）'
    case 'expired':
      return '已过期'
    case 'closed':
      return '已关闭'
    default:
      return session.value?.status ?? ''
  }
})
const statusColor = computed(() =>
  session.value?.status === 'active' ? 'processing' : session.value?.status === 'conflict' ? 'error' : 'default',
)

// region 初始化与快照

async function init() {
  initializing.value = true
  try {
    const res = await createAgentSession(props.pictureId)
    if (res.data.code !== 0) {
      message.error(res.data.message || '创建 Agent 会话失败')
      return
    }
    applySession(res.data.data)
    leaseTimer = window.setInterval(doRenewLease, 20000)
    // 恢复仍在进行中的运行的 SSE 订阅
    for (const run of session.value?.runs ?? []) {
      if (['planning', 'waiting', 'running'].includes(run.status)) {
        subscribeRun(run.id)
      }
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '打开 Agent 工作台失败')
  } finally {
    initializing.value = false
  }
}

function applySession(data: AgentSessionVO) {
  session.value = data
  rebuildMessages()
}

function rebuildMessages() {
  const list: ChatMessage[] = []
  for (const run of session.value?.runs ?? []) {
    list.push({ key: `${run.id}-user`, role: 'user', text: run.goal })
    list.push({ key: `${run.id}-agent`, role: 'agent', text: run.reply, run })
  }
  messages.value = list
}

async function refreshSession() {
  if (!session.value) return
  const res = await getAgentSession(session.value.id)
  if (res.data.code === 0) {
    applySession(res.data.data)
  }
}

async function doRenewLease() {
  if (!session.value || !editable.value) return
  try {
    const res = await renewAgentLease(session.value.id)
    if (res.data.code !== 0) {
      message.warning(res.data.message || '编辑租约续租失败')
      await refreshSession()
    } else if (session.value) {
      session.value.lease = res.data.data
    }
  } catch {
    // 网络抖动忽略，下一轮再续
  }
}

// endregion

// region 消息与运行

async function onSendMessage(text: string) {
  if (!session.value) return
  messages.value.push({ key: `local-${Date.now()}`, role: 'user', text })
  sending.value = true
  try {
    const res = await sendAgentMessage(session.value.id, text)
    if (res.data.code === 0) {
      const run: AgentRunVO = res.data.data
      messages.value.push({ key: `${run.id}-agent`, role: 'agent', text: run.reply, run })
      await refreshSessionQuiet()
      if (['planning', 'waiting', 'running'].includes(run.status)) {
        subscribeRun(run.id)
      }
    } else {
      message.error(res.data.message || '指令处理失败')
    }
  } finally {
    sending.value = false
  }
}

async function refreshSessionQuiet() {
  if (!session.value) return
  const res = await getAgentSession(session.value.id)
  if (res.data.code === 0) {
    session.value = res.data.data
  }
}

async function onConfirmRun(runId: string) {
  await actRun(runId, () => confirmAgentRun(runId))
}

async function onCancelRun(runId: string) {
  await actRun(runId, () => cancelAgentRun(runId))
}

async function onRetryRun(runId: string) {
  await actRun(runId, () => retryAgentRun(runId))
}

async function actRun(
  runId: string,
  action: () => Promise<{ data: { code: number; data: AgentRunVO; message?: string } }>,
) {
  actingRunId.value = runId
  try {
    const res = await action()
    if (res.data.code === 0) {
      updateRunMessage(res.data.data)
      subscribeRun(runId)
    } else {
      message.error(res.data.message || '操作失败')
    }
  } finally {
    actingRunId.value = ''
  }
}

function updateRunMessage(run: AgentRunVO) {
  const target = messages.value.find((msg) => msg.run && String(msg.run.id) === String(run.id))
  if (target) {
    target.run = run
    if (run.reply) target.text = run.reply
  } else {
    messages.value.push({ key: `${run.id}-agent`, role: 'agent', text: run.reply, run })
  }
}

function subscribeRun(runId: string) {
  const key = String(runId)
  if (eventSources.has(key)) return
  const source = new EventSource(agentRunEventsUrl(key), { withCredentials: true })
  eventSources.set(key, source)
  const refresh = async () => {
    const res = await getAgentRun(key)
    if (res.data.code === 0) {
      updateRunMessage(res.data.data)
      if (['succeeded', 'failed', 'canceled'].includes(res.data.data.status)) {
        closeRunStream(key)
        await refreshSessionQuiet()
      }
    }
  }
  source.onmessage = () => {
    void refresh()
  }
  source.onerror = () => {
    // 断线：关闭流，用户可通过刷新快照恢复
    closeRunStream(key)
    void refreshSessionQuiet()
  }
}

function closeRunStream(key: string) {
  eventSources.get(key)?.close()
  eventSources.delete(key)
}

// endregion

// region 工具直调

const toolModalOpen = ref(false)
const activeTool = ref<AgentToolVO | null>(null)
const toolParams = ref<Record<string, unknown>>({})

const visibleParams = computed<ParamField[]>(
  () => (activeTool.value?.params ?? []).filter((field) => !field.agentHidden),
)

function openToolModal(tool: AgentToolVO) {
  activeTool.value = tool
  const params: Record<string, unknown> = {}
  for (const field of tool.params) {
    if (field.agentHidden) continue
    if (field.defaultValue !== undefined && field.defaultValue !== null) {
      params[field.name] = field.defaultValue
    } else if (field.type === 'boolean') {
      params[field.name] = undefined
    } else if (field.type === 'array' && field.itemChoices) {
      params[field.name] = []
    }
  }
  toolParams.value = params
  toolModalOpen.value = true
}

function normalizeParams(tool: AgentToolVO): Record<string, unknown> | null {
  const result: Record<string, unknown> = {}
  for (const field of tool.params) {
    if (field.agentHidden) continue
    let value = toolParams.value[field.name]
    if (value === undefined || value === null || value === '') {
      if (field.required) {
        message.warning(`请填写参数 ${field.name}`)
        return null
      }
      continue
    }
    if (field.type === 'array' || field.type === 'object') {
      if (typeof value === 'string') {
        try {
          value = JSON.parse(value) as unknown
        } catch {
          message.warning(`参数 ${field.name} 不是合法 JSON`)
          return null
        }
      }
    }
    result[field.name] = value
  }
  return result
}

async function submitActiveTool() {
  if (!activeTool.value || !session.value) return
  const params = normalizeParams(activeTool.value)
  if (params === null) return
  sending.value = true
  try {
    const res = await submitAgentTool(session.value.id, activeTool.value.name, params)
    if (res.data.code === 0) {
      toolModalOpen.value = false
      const run: AgentRunVO = res.data.data
      updateRunMessage(run)
      if (['planning', 'waiting', 'running'].includes(run.status)) {
        subscribeRun(run.id)
      } else {
        await refreshSessionQuiet()
      }
    } else {
      message.error(res.data.message || '工具执行失败')
    }
  } finally {
    sending.value = false
  }
}

// endregion

// region 画布 / 图层 / 选区 / 资产

async function onLayerVisible(layer: CanvasLayer, visible: boolean) {
  await runDocumentTool('set_layer_visible', { layerId: layer.id, visible })
}

async function onLayerOpacity(layer: CanvasLayer, opacity: number) {
  await runDocumentTool('set_layer_opacity', { layerId: layer.id, opacity })
}

async function onLayerReorder(layer: CanvasLayer, place: 'up' | 'down') {
  await runDocumentTool('reorder_layer', { layerId: layer.id, place })
}

async function runDocumentTool(tool: string, params: Record<string, unknown>) {
  if (!session.value) return
  const res = await submitAgentTool(session.value.id, tool, params)
  if (res.data.code === 0) {
    await refreshSessionQuiet()
    const run: AgentRunVO = res.data.data
    if (['planning', 'waiting', 'running'].includes(run.status)) {
      subscribeRun(run.id)
    }
  } else {
    message.error(res.data.message || '操作失败')
  }
}

async function onSelection(payload: { maskBlob: Blob; markers: SelectionMarker[] }) {
  if (!session.value) return
  try {
    const prepareRes = await prepareAgentSelection(session.value.id, payload.maskBlob)
    if (prepareRes.data.code !== 0) {
      message.error(prepareRes.data.message || '选区上传失败')
      return
    }
    const saveRes = await saveAgentSelection(session.value.id, {
      maskAssetId: prepareRes.data.data.maskAssetId,
      markers: payload.markers,
      revision: prepareRes.data.data.revision,
    })
    if (saveRes.data.code === 0) {
      message.success('选区已生效，局部工具将只修改选区内')
      await refreshSessionQuiet()
    } else {
      message.error(saveRes.data.message || '选区保存失败')
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '选区处理失败')
  }
}

async function clearSelection() {
  if (!session.value) return
  await clearAgentSelection(session.value.id)
  await refreshSessionQuiet()
}

async function onAdopt(asset: AgentAssetVO) {
  if (!session.value) return
  const res = await adoptAgentAsset(session.value.id, asset.id)
  if (res.data.code === 0) {
    applySession(res.data.data)
    message.success('已设为当前画布')
  } else {
    message.error(res.data.message || '采用失败')
  }
}

async function onSetFinal(asset: AgentAssetVO) {
  if (!session.value) return
  const res = await setAgentFinalAsset(session.value.id, asset.id)
  if (res.data.code === 0) {
    applySession(res.data.data)
    message.success('已选定为最终稿，可在“版本管理”中提交')
  } else {
    message.error(res.data.message || '选定失败')
  }
}

function onPreview(asset: AgentAssetVO) {
  previewUrl.value = asset.url
  previewVisible.value = true
}

async function onExportZip() {
  if (!session.value) return
  try {
    await exportAgentZip(session.value.id, 'all')
  } catch (e) {
    message.error(e instanceof Error ? e.message : 'ZIP 导出失败')
  }
}

// endregion

// region 撤销重做 / 版本 / 导航

async function doUndo() {
  if (!session.value) return
  const res = await undoAgentSession(session.value.id)
  if (res.data.code === 0) applySession(res.data.data)
  else message.error(res.data.message || '撤销失败')
}

async function doRedo() {
  if (!session.value) return
  const res = await redoAgentSession(session.value.id)
  if (res.data.code === 0) applySession(res.data.data)
  else message.error(res.data.message || '重做失败')
}

async function onCommitted() {
  await refreshSession()
}

async function onRestored() {
  message.success('版本已恢复，返回详情页查看新图')
}

function goBack() {
  router.push(`/picture/${props.pictureId}`)
}

// endregion

onMounted(init)

onBeforeUnmount(() => {
  window.clearInterval(leaseTimer)
  for (const key of Array.from(eventSources.keys())) {
    closeRunStream(key)
  }
  if (session.value && editable.value) {
    releaseAgentLease(session.value.id).catch(() => undefined)
  }
})
</script>

<style scoped>
#agentWorkbenchPage {
  height: calc(100vh - 112px);
  display: flex;
  flex-direction: column;
}

#agentWorkbenchPage :deep(.ant-spin-nested-loading),
#agentWorkbenchPage :deep(.ant-spin-container) {
  height: 100%;
}

.workbench-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #fff;
  border-radius: 8px 8px 0 0;
  border-bottom: 1px solid #f0f0f0;
}

.session-title {
  font-weight: 600;
}

.workbench-body {
  flex: 1;
  display: flex;
  gap: 12px;
  min-height: 0;
  padding-top: 12px;
}

.side-panel {
  width: 240px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}

.side-panel > :first-child {
  flex: 1;
  min-height: 0;
}

.tool-panel {
  border-top: 1px solid #f0f0f0;
}

.panel-title {
  font-weight: 600;
  font-size: 13px;
  padding: 8px 12px;
}

.tool-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  padding: 0 10px 10px;
  max-height: 260px;
  overflow-y: auto;
}

.center-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}

.center-panel > :first-child {
  flex: 1;
  min-height: 0;
}

.wall-panel {
  height: 190px;
  border-top: 1px solid #f0f0f0;
}

.chat-panel-wrap {
  width: 380px;
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}

.tool-desc {
  color: #666;
  font-size: 12px;
}

.field-desc {
  color: #999;
  font-size: 12px;
}
</style>
