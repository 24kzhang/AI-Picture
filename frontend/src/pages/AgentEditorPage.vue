<template>
  <div class="agent-editor">
    <a-spin :spinning="store.loading" tip="正在准备 Agent 工作台...">
      <a-result
        v-if="errorMessage"
        status="warning"
        title="无法进入 Agent 工作台"
        :sub-title="errorMessage"
      >
        <template #extra>
          <a-button type="primary" @click="reload">重试</a-button>
          <a-button @click="goBack">返回图片详情</a-button>
        </template>
      </a-result>

      <template v-else-if="store.session">
        <!-- 顶部状态栏 -->
        <div class="agent-header">
          <div class="agent-header__left">
            <a-button type="link" @click="goBack">← 返回图片详情</a-button>
            <span class="agent-header__title">Agent 智能精修</span>
            <a-tag v-if="store.status" :color="statusColor">{{ statusText }}</a-tag>
            <a-tag v-if="store.readOnly" color="orange">只读</a-tag>
            <a-tag v-else-if="!store.leaseValid" color="orange">编辑租约已失效</a-tag>
            <a-tag v-else color="green">编辑租约有效</a-tag>
            <a-tag :color="store.sseConnected ? 'blue' : 'default'">
              {{ store.sseConnected ? '实时连接' : '未连接实时事件' }}
            </a-tag>
          </div>
          <div class="agent-header__right">
            <span class="agent-header__meta">
              基础版本 v{{ store.expectedEditVersion }} · 画布修订 {{ store.canvas?.revision ?? 0 }}
            </span>
            <a-button size="small" :loading="store.loading" @click="store.refresh()">刷新</a-button>
          </div>
        </div>

        <a-alert
          v-if="store.readOnly || !store.leaseValid"
          type="warning"
          show-icon
          class="agent-alert"
          :message="
            store.readOnly
              ? '当前为只读模式：会话由其他成员持有，你可以查看画布但不能修改'
              : '编辑租约已失效：结果仅保留为草稿，禁止提交原图，请重新进入工作台'
          "
        />

        <div class="agent-body">
          <!-- 左侧：图片墙与历史版本 -->
          <div class="agent-body__left">
            <a-card size="small" title="图片墙 / 候选结果">
              <div class="agent-wall">
                <div
                  v-for="asset in wallAssets"
                  :key="asset.id"
                  class="agent-wall__item"
                  :class="{ 'agent-wall__item--active': asset.id === activeAssetId }"
                  @click="store.selectFinalAsset(asset.id)"
                >
                  <img :src="asset.url" alt="候选" />
                  <div class="agent-wall__badge">{{ assetKindText(asset.kind) }}</div>
                </div>
                <a-empty v-if="wallAssets.length === 0" description="暂无候选结果" />
              </div>
            </a-card>
            <a-card size="small" title="历史版本" class="agent-left-card">
              <a-list size="small" :data-source="store.versions">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-list-item-meta
                      :title="`v${item.versionNo} · ${versionSourceText(item.source)}`"
                      :description="formatTime(item.createTime)"
                    />
                    <template #actions>
                      <a @click="previewVersion(item)">查看</a>
                      <a v-if="canEdit" @click="store.restore(item.id)">恢复</a>
                    </template>
                  </a-list-item>
                </template>
              </a-list>
            </a-card>
          </div>

          <!-- 中间：画布 -->
          <div class="agent-body__center">
            <AgentCanvas />
            <AgentLayerPanel class="agent-layer-wrap" />
          </div>

          <!-- 右侧：对话与任务 -->
          <div class="agent-body__right">
            <AgentTaskPanel />
            <AgentChatPanel />
          </div>
        </div>

        <!-- 底部操作 -->
        <div class="agent-footer">
          <a-space>
            <a-button :disabled="!canEdit" @click="store.undo()">撤销</a-button>
            <a-button :disabled="!canEdit" @click="store.redo()">重做</a-button>
            <a-button danger @click="cancelSession">取消草稿</a-button>
          </a-space>
          <a-space>
            <a-button @click="versionDialogRef?.open()">版本记录</a-button>
            <a-button :disabled="!store.session" @click="doExport">导出 ZIP</a-button>
            <a-button
              type="primary"
              :disabled="!store.canCommit"
              :loading="store.submitting"
              @click="commitDialogVisible = true"
            >
              确定并替换原图
            </a-button>
          </a-space>
        </div>

        <AgentVersionDialog ref="versionDialogRef" />
        <a-modal
          v-model:open="commitDialogVisible"
          title="确认替换原图"
          ok-text="确认替换"
          cancel-text="取消"
          :confirm-loading="store.submitting"
          @ok="doCommit"
        >
          <a-alert
            type="warning"
            show-icon
            message="替换后图库将使用新的图片版本，历史版本仍保留可恢复。"
            class="agent-alert"
          />
          <p>
            当前版本：v{{ store.versions[0]?.versionNo ?? '-' }} →
            新版本：v{{ nextVersionNo }}
          </p>
          <div class="agent-compare">
            <div>
              <p>当前原图</p>
              <img :src="store.versions[0]?.url" alt="当前原图" />
            </div>
            <div>
              <p>最终草稿</p>
              <img :src="draftUrl" alt="最终草稿" />
            </div>
          </div>
        </a-modal>

        <a-modal v-model:open="previewVisible" title="版本预览" :footer="null" width="720px">
          <img v-if="previewUrl" :src="previewUrl" style="width: 100%" alt="版本预览" />
        </a-modal>
      </template>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useAgentEditorStore } from '@/stores/useAgentEditorStore'
import { AgentAsset, PictureVersion, createAgentExport } from '@/api/agent'
import AgentCanvas from '@/components/agent/AgentCanvas.vue'
import AgentChatPanel from '@/components/agent/AgentChatPanel.vue'
import AgentLayerPanel from '@/components/agent/AgentLayerPanel.vue'
import AgentTaskPanel from '@/components/agent/AgentTaskPanel.vue'
import AgentVersionDialog from '@/components/agent/AgentVersionDialog.vue'

const route = useRoute()
const router = useRouter()
const store = useAgentEditorStore()

const errorMessage = ref('')
const commitDialogVisible = ref(false)
const previewVisible = ref(false)
const previewUrl = ref('')
const versionDialogRef = ref<InstanceType<typeof AgentVersionDialog> | null>(null)

const pictureId = computed(() => String(route.params.pictureId ?? route.params.id ?? ''))

const wallAssets = computed<AgentAsset[]>(() => {
  const assets = store.canvas?.assets ?? []
  return [...assets].reverse()
})
const activeAssetId = computed(() => store.canvas?.current_asset_id ?? '')
const draftUrl = computed(() => {
  const assets = store.canvas?.assets ?? []
  return assets.find((asset) => asset.id === store.draftAssetId)?.url ?? ''
})
const nextVersionNo = computed(() => {
  const latest = store.versions[0]?.versionNo
  return latest ? Number(latest) + 1 : 2
})
const canEdit = computed(() => store.canEdit)

const statusColor = computed(() => {
  switch (store.status) {
    case 'ACTIVE':
      return 'processing'
    case 'READY_TO_COMMIT':
      return 'blue'
    case 'COMMITTED':
      return 'green'
    case 'CONFLICT':
      return 'red'
    case 'CANCELED':
    case 'EXPIRED':
      return 'default'
    default:
      return 'default'
  }
})
const statusText = computed(() => {
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
  return map[store.status] ?? store.status
})

function assetKindText(kind: string) {
  const map: Record<string, string> = {
    original: '原图',
    generated: '生成',
    subject: '主体',
    background: '背景',
    mask: '选区',
    marketing: '营销图',
    export: '导出',
  }
  return map[kind] ?? kind
}

function versionSourceText(source: string) {
  const map: Record<string, string> = {
    UPLOAD: '上传',
    QUICK_EDIT: '快捷编辑',
    AGENT: 'Agent 精修',
    RESTORE: '版本恢复',
  }
  return map[source] ?? source
}

function formatTime(value?: string) {
  if (!value) {
    return ''
  }
  return new Date(value).toLocaleString()
}

function previewVersion(version: PictureVersion) {
  previewUrl.value = version.url
  previewVisible.value = true
}

async function reload() {
  errorMessage.value = ''
  if (!pictureId.value) {
    errorMessage.value = '缺少图片 ID'
    return
  }
  try {
    await store.open(pictureId.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '进入工作台失败'
  }
}

function goBack() {
  router.back()
}

async function doCommit() {
  try {
    const version = await store.commit()
    commitDialogVisible.value = false
    if (version) {
      message.success(`已替换原图，新版本 v${version.versionNo}`)
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '提交失败')
  }
}

async function cancelSession() {
  try {
    await store.cancelSession()
    message.info('草稿已取消，编辑租约已释放')
  } catch {
    message.error('取消失败')
  }
}

async function doExport() {
  if (!store.session) {
    return
  }
  try {
    const exportInfo = await createAgentExport({ sessionId: store.session.id })
    window.open(exportInfo.downloadUrl, '_blank')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '导出失败')
  }
}

onMounted(reload)
onBeforeUnmount(() => store.dispose())
</script>

<style scoped>
.agent-editor {
  padding: 16px;
  min-height: calc(100vh - 64px);
  background: #f5f6f8;
}

.agent-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.agent-header__left,
.agent-header__right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.agent-header__title {
  font-size: 18px;
  font-weight: 600;
}

.agent-header__meta {
  color: #888;
  font-size: 12px;
}

.agent-alert {
  margin-bottom: 12px;
}

.agent-body {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr) 380px;
  gap: 12px;
  align-items: start;
}

.agent-left-card {
  margin-top: 12px;
}

.agent-layer-wrap {
  margin-top: 12px;
}

.agent-wall {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  max-height: 320px;
  overflow: auto;
}

.agent-wall__item {
  position: relative;
  border: 2px solid transparent;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  background: #fff;
}

.agent-wall__item img {
  width: 100%;
  height: 84px;
  object-fit: cover;
  display: block;
}

.agent-wall__item--active {
  border-color: #1677ff;
}

.agent-wall__badge {
  position: absolute;
  left: 0;
  bottom: 0;
  padding: 0 6px;
  font-size: 11px;
  color: #fff;
  background: rgba(0, 0, 0, 0.5);
}

.agent-footer {
  display: flex;
  justify-content: space-between;
  margin-top: 12px;
  padding: 12px;
  background: #fff;
  border-radius: 8px;
}

.agent-compare {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 8px;
}

.agent-compare img {
  width: 100%;
  border: 1px solid #eee;
  border-radius: 4px;
}
</style>
