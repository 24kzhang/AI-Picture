<template>
  <a-modal
    :open="open"
    title="版本管理"
    width="720px"
    :footer="null"
    @cancel="emit('close')"
  >
    <a-tabs>
      <a-tab-pane key="commit" tab="提交正式版本">
        <a-alert
          type="info"
          show-icon
          message="Agent 结果默认只是草稿，提交后才会覆盖图片正式版本，并记录版本号。"
          style="margin-bottom: 12px"
        />
        <div v-if="finalAsset" class="final-preview">
          <img :src="finalAsset.thumbnailUrl || finalAsset.url" alt="最终稿" />
          <div>
            <p>已选定最终稿：{{ finalAsset.width }}×{{ finalAsset.height }}</p>
            <a-popconfirm
              title="确认提交为正式版本？提交后原图将被替换（版本历史保留）。"
              @confirm="doCommit"
            >
              <a-button type="primary" :loading="committing" :disabled="!canCommit">
                提交正式版本
              </a-button>
            </a-popconfirm>
          </div>
        </div>
        <a-empty v-else description="请先在图片墙选定“最终稿”" />
      </a-tab-pane>

      <a-tab-pane key="versions" tab="版本历史">
        <a-spin :spinning="loading">
          <a-table
            :data-source="versions"
            :columns="columns"
            :pagination="false"
            row-key="id"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'thumb'">
                <img :src="record.thumbnailUrl || record.url" class="version-thumb" alt="" />
              </template>
              <template v-else-if="column.key === 'source'">
                <a-tag>{{ sourceText(record.source) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'action'">
                <a-popconfirm
                  title="恢复该版本会创建一个新版本，确认继续？"
                  @confirm="doRestore(record)"
                >
                  <a-button size="small" :disabled="!canEdit" :loading="restoringId === record.id">
                    恢复
                  </a-button>
                </a-popconfirm>
              </template>
            </template>
          </a-table>
        </a-spin>
      </a-tab-pane>
    </a-tabs>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  commitAgentSession,
  listPictureVersions,
  restorePictureVersion,
  type AgentAssetVO,
  type PictureVersionVO,
} from '@/api/agentController'

const props = defineProps<{
  open: boolean
  pictureId: string | number
  sessionId: string | number
  finalAsset?: AgentAssetVO | null
  expectedEditVersion: string | number
  canEdit: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'committed'): void
  (e: 'restored'): void
}>()

const versions = ref<PictureVersionVO[]>([])
const loading = ref(false)
const committing = ref(false)
const restoringId = ref<string>('')

const canCommit = computed(
  () => !!props.finalAsset && !!props.sessionId && props.expectedEditVersion !== undefined,
)

const columns = [
  { title: '版本', dataIndex: 'versionNo', key: 'versionNo', width: 70 },
  { title: '预览', key: 'thumb', width: 90 },
  { title: '来源', key: 'source', width: 110 },
  { title: '尺寸', key: 'size', width: 120 },
  { title: '时间', dataIndex: 'createTime', key: 'createTime' },
  { title: '操作', key: 'action', width: 90 },
]

const SOURCE_TEXT: Record<string, string> = {
  upload: '上传',
  quick_edit: '快捷编辑',
  agent: 'Agent 精修',
  restore: '恢复版本',
}

function sourceText(source: string) {
  return SOURCE_TEXT[source] ?? source
}

async function loadVersions() {
  loading.value = true
  try {
    const res = await listPictureVersions(props.pictureId)
    if (res.data.code === 0) {
      versions.value = res.data.data ?? []
    }
  } finally {
    loading.value = false
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) loadVersions()
  },
)

async function doCommit() {
  if (!props.finalAsset) return
  committing.value = true
  try {
    const res = await commitAgentSession(
      props.sessionId,
      props.finalAsset.id,
      props.expectedEditVersion,
    )
    if (res.data.code === 0) {
      message.success(`已提交正式版本 v${res.data.data?.versionNo}`)
      emit('committed')
      await loadVersions()
    } else {
      message.error(res.data.message || '提交失败')
    }
  } finally {
    committing.value = false
  }
}

async function doRestore(record: PictureVersionVO) {
  restoringId.value = record.id
  try {
    const res = await restorePictureVersion(props.pictureId, record.id, props.expectedEditVersion)
    if (res.data.code === 0) {
      message.success(`已恢复为新版本 v${res.data.data?.versionNo}`)
      emit('restored')
      await loadVersions()
    } else {
      message.error(res.data.message || '恢复失败')
    }
  } finally {
    restoringId.value = ''
  }
}
</script>

<style scoped>
.final-preview {
  display: flex;
  gap: 16px;
  align-items: center;
}

.final-preview img {
  width: 180px;
  max-height: 180px;
  object-fit: contain;
  background: #f5f5f5;
  border-radius: 8px;
}

.version-thumb {
  width: 56px;
  height: 40px;
  object-fit: cover;
  border-radius: 4px;
}
</style>
