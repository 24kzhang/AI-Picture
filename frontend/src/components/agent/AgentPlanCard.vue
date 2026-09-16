<template>
  <a-card size="small" class="agent-plan-card" :title="`多步计划（${steps.length} 步，确认后执行）`">
    <a-steps direction="vertical" size="small" :current="currentIndex">
      <a-step
        v-for="step in steps"
        :key="step.id"
        :status="stepStatus(step.status)"
        :title="step.label"
        :description="stepDescription(step)"
      />
    </a-steps>

    <div class="agent-plan-card__actions">
      <a-space v-if="status === 'waiting'">
        <a-button type="primary" size="small" :disabled="!canEdit" @click="confirm">
          确认执行
        </a-button>
        <a-button size="small" :disabled="!canEdit" @click="cancel">取消计划</a-button>
      </a-space>
      <a-space v-else-if="status === 'failed'">
        <a-button size="small" :disabled="!canEdit" @click="retry">重试失败步骤</a-button>
      </a-space>
    </div>
  </a-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAgentEditorStore } from '@/stores/useAgentEditorStore'
import type { AgentPlanStep, AgentTurn } from '@/api/agent'

const props = defineProps<{ turn: AgentTurn }>()
const store = useAgentEditorStore()

const steps = computed<AgentPlanStep[]>(() => props.turn.steps ?? [])
const status = computed(() => props.turn.status)
const canEdit = computed(() => store.canEdit)

const currentIndex = computed(() => {
  const running = steps.value.findIndex((step) => step.status === 'running')
  if (running >= 0) {
    return running
  }
  const finished = steps.value.filter((step) => step.status === 'succeeded').length
  return finished >= steps.value.length ? steps.value.length : finished
})

const STEP_STATUS: Record<string, 'wait' | 'process' | 'finish' | 'error'> = {
  pending: 'wait',
  waiting: 'wait',
  queued: 'process',
  running: 'process',
  succeeded: 'finish',
  failed: 'error',
  canceled: 'error',
}

function stepStatus(value: string) {
  return STEP_STATUS[value] ?? 'wait'
}

function stepDescription(step: AgentPlanStep) {
  const label: Record<string, string> = {
    pending: '待执行',
    waiting: '待确认',
    queued: '排队中',
    running: '执行中',
    succeeded: '已完成',
    failed: '失败',
    canceled: '已取消',
  }
  const depends = step.depends_on?.length ? `依赖：${step.depends_on.join('、')}` : ''
  return [label[step.status] ?? step.status, depends].filter(Boolean).join(' · ')
}

async function confirm() {
  await store.confirmPlan(props.turn.id)
}

async function cancel() {
  await store.cancelPlan(props.turn.id)
}

async function retry() {
  await store.retryPlan(props.turn.id)
}
</script>

<style scoped>
.agent-plan-card {
  margin-top: 8px;
}

.agent-plan-card__actions {
  margin-top: 8px;
}
</style>
