<template>
  <div class="plan-card">
    <div class="plan-head">
      <span class="plan-title">
        {{ statusIcon }} {{ run.goal || '工具计划' }}
      </span>
      <a-tag :color="statusColor">{{ statusText }}</a-tag>
    </div>

    <ol class="plan-steps">
      <li v-for="step in run.plan || []" :key="step.id" class="plan-step">
        <div class="step-head">
          <span class="step-tool">{{ toolLabel(step) }}</span>
          <a-tag size="small" :color="stepColor(step.status)">{{ stepText(step.status) }}</a-tag>
        </div>
        <a-progress
          v-if="toolRunOf(step)"
          :percent="toolRunOf(step)?.progress ?? 0"
          size="small"
          :status="progressStatus(step.status)"
        />
        <div v-if="toolRunOf(step)?.stage" class="step-stage">
          {{ toolRunOf(step)?.stage }}
        </div>
        <a-alert
          v-if="toolRunOf(step)?.errorMessage"
          type="error"
          show-icon
          :message="toolRunOf(step)?.errorMessage"
          class="step-error"
        />
      </li>
    </ol>

    <div v-if="run.errorMessage" class="run-error">
      <a-alert type="error" show-icon :message="run.errorMessage" />
    </div>

    <div class="plan-actions">
      <template v-if="run.status === 'waiting'">
        <a-button type="primary" size="small" :loading="acting" @click="emit('confirm', run.id)">
          确认执行
        </a-button>
        <a-button size="small" danger :disabled="acting" @click="emit('cancel', run.id)">
          取消
        </a-button>
      </template>
      <template v-else-if="run.status === 'running' || run.status === 'planning'">
        <a-button size="small" danger :disabled="acting" @click="emit('cancel', run.id)">
          取消执行
        </a-button>
      </template>
      <template v-else-if="run.status === 'failed' || run.status === 'canceled'">
        <a-button size="small" :loading="acting" @click="emit('retry', run.id)">重试</a-button>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { AgentRunVO, AgentToolRunVO, PlanStep } from '@/api/agentController'

const props = defineProps<{
  run: AgentRunVO
  acting?: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm', runId: string): void
  (e: 'cancel', runId: string): void
  (e: 'retry', runId: string): void
}>()

const STATUS_TEXT: Record<string, string> = {
  planning: '规划中',
  waiting: '等待确认',
  running: '执行中',
  succeeded: '已完成',
  failed: '失败',
  canceled: '已取消',
}

const STATUS_COLOR: Record<string, string> = {
  planning: 'processing',
  waiting: 'orange',
  running: 'processing',
  succeeded: 'success',
  failed: 'error',
  canceled: 'default',
}

const statusText = computed(() => STATUS_TEXT[props.run.status] ?? props.run.status)
const statusColor = computed(() => STATUS_COLOR[props.run.status] ?? 'default')
const statusIcon = computed(() => {
  switch (props.run.status) {
    case 'succeeded':
      return '✅'
    case 'failed':
      return '❌'
    case 'canceled':
      return '⛔'
    case 'waiting':
      return '📋'
    default:
      return '⚙️'
  }
})

function toolRunOf(step: PlanStep): AgentToolRunVO | undefined {
  return (props.run.toolRuns ?? []).find(
    (item) => String(item.id) === String(step.toolRunId ?? ''),
  )
}

function toolLabel(step: PlanStep): string {
  return toolRunOf(step)?.toolLabel ?? step.tool
}

function stepText(status: string) {
  return STATUS_TEXT[status] ?? status
}

function stepColor(status: string) {
  return STATUS_COLOR[status] ?? 'default'
}

function progressStatus(status: string): 'normal' | 'success' | 'exception' | 'active' {
  if (status === 'succeeded') return 'success'
  if (status === 'failed' || status === 'canceled') return 'exception'
  if (status === 'running') return 'active'
  return 'normal'
}
</script>

<style scoped>
.plan-card {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fafafa;
  margin: 8px 0;
}

.plan-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.plan-title {
  font-weight: 600;
  font-size: 13px;
}

.plan-steps {
  margin: 0;
  padding-left: 18px;
}

.plan-step {
  margin-bottom: 8px;
}

.step-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.step-tool {
  font-weight: 500;
}

.step-stage {
  font-size: 12px;
  color: #999;
}

.step-error {
  margin-top: 4px;
}

.run-error {
  margin-top: 6px;
}

.plan-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
</style>
