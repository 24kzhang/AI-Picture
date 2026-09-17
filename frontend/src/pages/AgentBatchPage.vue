<template>
  <div class="agent-batch">
    <div class="agent-batch__header">
      <div>
        <h2>Agent 批量精修</h2>
        <p class="agent-batch__sub">最多选择 20 张图片，支持多步处理、投放尺寸与 ZIP 导出；确认时逐张提交并单独报告冲突。</p>
      </div>
      <a-space>
        <a-button @click="router.back()">返回</a-button>
        <a-button :disabled="!batch" @click="doExport">导出 ZIP</a-button>
      </a-space>
    </div>

    <a-card size="small" title="1. 选择空间与图片" class="agent-batch__card">
      <a-space wrap>
        <span>空间：</span>
        <a-select
          v-model:value="spaceId"
          style="width: 220px"
          placeholder="选择空间"
          :options="spaceOptions"
          @change="loadPictures"
        />
        <a-button :loading="pictureLoading" @click="loadPictures">刷新图片</a-button>
        <span class="agent-batch__count">已选 {{ selectedIds.length }} / 20</span>
      </a-space>

      <a-spin :spinning="pictureLoading">
        <div class="agent-batch__grid">
          <div
            v-for="picture in pictures"
            :key="String(picture.id)"
            class="agent-batch__item"
            :class="{ 'agent-batch__item--active': selectedIds.includes(String(picture.id)) }"
            @click="togglePicture(picture)"
          >
            <img :src="picture.thumbnailUrl || picture.url" :alt="picture.name" />
            <div class="agent-batch__name">{{ picture.name }}</div>
          </div>
        </div>
        <a-empty v-if="!pictureLoading && pictures.length === 0" description="该空间暂无图片" />
      </a-spin>
    </a-card>

    <a-card size="small" title="2. 处理操作与格式" class="agent-batch__card">
      <a-checkbox-group v-model:value="operations" :options="operationOptions" />
      <a-divider style="margin: 12px 0" />
      <a-radio-group v-model:value="format">
        <a-radio value="png">PNG</a-radio>
        <a-radio value="jpg">JPG</a-radio>
      </a-radio-group>
      <div class="agent-batch__actions">
        <a-button
          type="primary"
          :loading="submitting"
          :disabled="selectedIds.length === 0 || operations.length === 0"
          @click="doSubmit"
        >
          开始批量处理
        </a-button>
        <a-button v-if="batch && !isTerminal(batch.status)" danger @click="doCancel">取消任务</a-button>
      </div>
    </a-card>

    <a-card v-if="batch" size="small" title="3. 任务进度与结果" class="agent-batch__card">
      <a-descriptions :column="2" size="small">
        <a-descriptions-item label="状态">{{ batch.status }}</a-descriptions-item>
        <a-descriptions-item label="阶段">{{ batch.stage || '-' }}</a-descriptions-item>
      </a-descriptions>
      <a-progress
        :percent="batch.progress || 0"
        :status="progressStatus"
        size="small"
        style="margin: 8px 0"
      />

      <a-table
        :data-source="batch.items"
        :columns="itemColumns"
        row-key="pictureId"
        size="small"
        :pagination="false"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-tag :color="itemStatusColor(record.status)">{{ itemStatusText(record.status) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'result'">
            <span v-if="record.versionNo">已生成 v{{ record.versionNo }}</span>
            <span v-else-if="record.error" class="agent-batch__error">{{ record.error }}</span>
            <span v-else>-</span>
          </template>
        </template>
      </a-table>

      <div class="agent-batch__actions">
        <a-button
          type="primary"
          :disabled="!isTerminal(batch.status) || batch.status !== 'succeeded' || batch.canceled"
          :loading="confirming"
          @click="doConfirm"
        >
          确认替换原图（逐张）
        </a-button>
        <span v-if="confirmSummary" class="agent-batch__summary">{{ confirmSummary }}</span>
      </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AGENT_BATCH_OPERATIONS,
  cancelAgentBatch,
  confirmAgentBatch,
  createAgentBatch,
  createAgentExport,
  getAgentBatch,
  type AgentBatch,
} from '@/api/agent'
import { listPictureVoByPageUsingPost } from '@/api/pictureController'
import { listSpaceVoByPageUsingPost } from '@/api/spaceController'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { batchItemStatusColor, batchItemStatusText, isTerminalRun } from '@/utils/agentStatus'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()

const spaceId = ref<string | number | undefined>(
  route.query.spaceId ? String(route.query.spaceId) : undefined,
)
const spaces = ref<API.SpaceVO[]>([])
const pictures = ref<API.PictureVO[]>([])
const selectedIds = ref<string[]>([])
const operations = ref<string[]>(['remove_background'])
const format = ref('png')
const pictureLoading = ref(false)
const submitting = ref(false)
const confirming = ref(false)
const batch = ref<AgentBatch | null>(null)
const confirmSummary = ref('')

let pollTimer: number | null = null

const spaceOptions = computed(() =>
  spaces.value.map((space) => ({ label: space.spaceName ?? `空间 ${space.id}`, value: String(space.id) })),
)
const operationOptions = AGENT_BATCH_OPERATIONS.map((item) => ({
  label: item.label,
  value: item.value,
}))
const itemColumns = [
  { title: '图片', dataIndex: 'pictureName', key: 'pictureName' },
  { title: '处理', dataIndex: 'status', key: 'status', width: 110 },
  { title: '结果', key: 'result' },
]
const progressStatus = computed(() => {
  if (!batch.value) {
    return 'normal'
  }
  if (batch.value.status === 'failed') {
    return 'exception'
  }
  if (batch.value.status === 'succeeded') {
    return 'success'
  }
  return 'active'
})

function isTerminal(status?: string) {
  return isTerminalRun(status)
}

function itemStatusText(status: string) {
  return batchItemStatusText(status)
}

function itemStatusColor(status: string) {
  return batchItemStatusColor(status)
}

async function loadSpaces() {
  if (!loginUserStore.loginUser?.id) {
    await loginUserStore.fetchLoginUser()
  }
  const res = await listSpaceVoByPageUsingPost({ current: 1, pageSize: 20 })
  if (res.data.code === 0 && res.data.data?.records) {
    spaces.value = res.data.data.records.filter(
      (space) => String(space.userId) === String(loginUserStore.loginUser?.id),
    )
    if (!spaceId.value && spaces.value.length > 0) {
      spaceId.value = String(spaces.value[0].id)
    }
  }
}

async function loadPictures() {
  if (!spaceId.value) {
    return
  }
  pictureLoading.value = true
  try {
    const res = await listPictureVoByPageUsingPost({
      current: 1,
      pageSize: 20,
      spaceId: String(spaceId.value),
    })
    pictures.value = res.data.code === 0 ? (res.data.data?.records ?? []) : []
    selectedIds.value = []
  } finally {
    pictureLoading.value = false
  }
}

function togglePicture(picture: API.PictureVO) {
  const id = String(picture.id)
  const index = selectedIds.value.indexOf(id)
  if (index >= 0) {
    selectedIds.value.splice(index, 1)
    return
  }
  if (selectedIds.value.length >= 20) {
    message.warning('批量最多选择 20 张图片')
    return
  }
  selectedIds.value.push(id)
}

async function doSubmit() {
  submitting.value = true
  confirmSummary.value = ''
  try {
    batch.value = await createAgentBatch({
      pictureIds: selectedIds.value,
      operations: operations.value,
      formats: [format.value],
    })
    message.success('批量任务已提交，正在处理')
    startPolling()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '批量任务提交失败')
  } finally {
    submitting.value = false
  }
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    if (!batch.value) {
      return
    }
    try {
      batch.value = await getAgentBatch(batch.value.batchId)
      if (isTerminal(batch.value.status)) {
        stopPolling()
      }
    } catch {
      // 保持轮询，不改变状态
    }
  }, 2000)
}

function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function doConfirm() {
  if (!batch.value) {
    return
  }
  confirming.value = true
  try {
    const items = await confirmAgentBatch(batch.value.batchId)
    batch.value = { ...batch.value, items }
    const succeeded = items.filter((item) => item.status === 'succeeded').length
    const conflicts = items.filter((item) => item.status === 'conflict').length
    const failed = items.filter((item) => item.status === 'failed').length
    confirmSummary.value = `成功 ${succeeded} 张，冲突 ${conflicts} 张，失败 ${failed} 张`
    if (conflicts > 0 || failed > 0) {
      message.warning('部分图片未替换，请查看逐项结果')
    } else {
      message.success('全部图片已替换为新版本')
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '确认失败')
  } finally {
    confirming.value = false
  }
}

async function doCancel() {
  if (!batch.value) {
    return
  }
  try {
    await cancelAgentBatch(batch.value.batchId)
    message.info('已取消任务（已完成的结果保留为草稿）')
    batch.value = await getAgentBatch(batch.value.batchId)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '取消失败')
  }
}

async function doExport() {
  if (!batch.value) {
    return
  }
  try {
    const exportInfo = await createAgentExport({ batchId: batch.value.batchId })
    window.open(exportInfo.downloadUrl, '_blank')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '导出失败')
  }
}

onMounted(async () => {
  await loadSpaces()
  await loadPictures()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.agent-batch {
  padding: 16px;
  background: #f5f6f8;
  min-height: calc(100vh - 64px);
}

.agent-batch__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.agent-batch__header h2 {
  margin: 0;
}

.agent-batch__sub {
  margin: 4px 0 0;
  color: #888;
  font-size: 12px;
}

.agent-batch__card {
  margin-bottom: 12px;
}

.agent-batch__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.agent-batch__item {
  border: 2px solid transparent;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  background: #fff;
}

.agent-batch__item img {
  width: 100%;
  height: 90px;
  object-fit: cover;
  display: block;
}

.agent-batch__item--active {
  border-color: #1677ff;
}

.agent-batch__name {
  padding: 2px 6px;
  font-size: 12px;
  color: #666;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-batch__count {
  color: #1677ff;
}

.agent-batch__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

.agent-batch__summary {
  color: #666;
  font-size: 12px;
}

.agent-batch__error {
  color: #ff4d4f;
  font-size: 12px;
}
</style>
