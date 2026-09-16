<template>
  <a-modal v-model:open="visible" title="版本记录" :footer="null" width="820px">
    <a-table
      :data-source="store.versions"
      :columns="columns"
      row-key="id"
      size="small"
      :pagination="false"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'preview'">
          <img :src="record.thumbnailUrl || record.url" class="version-thumb" alt="版本缩略图" />
        </template>
        <template v-else-if="column.key === 'source'">
          <a-tag>{{ sourceText(record.source) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'size'">
          {{ record.picWidth ?? '-' }} × {{ record.picHeight ?? '-' }}
        </template>
        <template v-else-if="column.key === 'createTime'">
          {{ formatTime(record.createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="preview(record)">查看</a>
            <a-popconfirm
              title="恢复该版本将创建新版本，不会删除历史记录。确认恢复？"
              ok-text="恢复"
              cancel-text="取消"
              @confirm="store.restore(record.id)"
            >
              <a :class="{ 'ant-btn-link-disabled': !store.canEdit }">恢复</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal v-model:open="previewVisible" title="版本预览" :footer="null" width="720px">
      <img v-if="previewUrl" :src="previewUrl" style="width: 100%" alt="版本预览" />
    </a-modal>
  </a-modal>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAgentEditorStore } from '@/stores/useAgentEditorStore'
import type { PictureVersion } from '@/api/agent'

const store = useAgentEditorStore()
const visible = ref(false)
const previewVisible = ref(false)
const previewUrl = ref('')

const columns = [
  { title: '版本', dataIndex: 'versionNo', key: 'versionNo', width: 70 },
  { title: '预览', key: 'preview', width: 110 },
  { title: '来源', key: 'source', width: 110 },
  { title: '尺寸', key: 'size', width: 130 },
  { title: '时间', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 130 },
]

function sourceText(source: string) {
  const map: Record<string, string> = {
    UPLOAD: '上传',
    QUICK_EDIT: '快捷编辑',
    AGENT: 'Agent 精修',
    RESTORE: '版本恢复',
  }
  return map[source] ?? source
}

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString() : '-'
}

function preview(version: PictureVersion) {
  previewUrl.value = version.url
  previewVisible.value = true
}

function open() {
  void store.loadVersions()
  visible.value = true
}

defineExpose({ open })
</script>

<style scoped>
.version-thumb {
  width: 84px;
  height: 56px;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid #eee;
}
</style>
