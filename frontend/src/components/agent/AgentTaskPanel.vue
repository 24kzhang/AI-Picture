<template>
  <a-card size="small" title="Agent 任务进度" class="agent-task-panel">
    <a-descriptions :column="1" size="small">
      <a-descriptions-item label="会话状态">
        <a-tag>{{ store.status || '-' }}</a-tag>
      </a-descriptions-item>
      <a-descriptions-item label="编辑租约">
        <a-tag :color="store.leaseValid ? 'green' : 'orange'">
          {{ store.leaseValid ? '有效' : '已失效' }}
        </a-tag>
      </a-descriptions-item>
      <a-descriptions-item label="实时事件">
        <a-tag :color="store.sseConnected ? 'blue' : 'default'">
          {{ store.sseConnected ? '已连接' : '未连接' }}
        </a-tag>
      </a-descriptions-item>
    </a-descriptions>

    <template v-if="store.activeRunId">
      <a-divider style="margin: 8px 0" />
      <p class="agent-task-panel__stage">
        {{ store.activeRunStage || '正在执行...' }}
      </p>
      <a-progress
        :percent="store.activeRunProgress"
        :status="progressStatus"
        size="small"
        :stroke-color="progressColor"
      />
      <p class="agent-task-panel__meta">
        状态：{{ store.activeRunStatus || 'running' }}
      </p>
    </template>
  </a-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAgentEditorStore } from '@/stores/useAgentEditorStore'

const store = useAgentEditorStore()

const progressStatus = computed(() => {
  if (store.activeRunStatus === 'failed') {
    return 'exception'
  }
  if (store.activeRunStatus === 'succeeded') {
    return 'success'
  }
  return 'active'
})

const progressColor = computed(() => {
  if (store.activeRunStatus === 'failed') {
    return '#ff4d4f'
  }
  if (store.activeRunStatus === 'canceled') {
    return '#faad14'
  }
  return undefined
})
</script>

<style scoped>
.agent-task-panel {
  margin-bottom: 12px;
}

.agent-task-panel__stage {
  margin: 0 0 4px;
  color: #333;
}

.agent-task-panel__meta {
  margin: 4px 0 0;
  color: #999;
  font-size: 12px;
}
</style>
