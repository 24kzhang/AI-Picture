<template>
  <a-card size="small" title="Agent 对话" class="agent-chat">
    <div ref="listRef" class="agent-chat__list">
      <div v-for="turn in store.turns" :key="String(turn.id)" class="agent-chat__turn">
        <div class="agent-chat__bubble agent-chat__bubble--user">
          {{ turn.goal || '（工具调用）' }}
        </div>
        <div class="agent-chat__bubble agent-chat__bubble--agent">
          <p>{{ turn.reply || turn.stage || statusText(turn.status) }}</p>
          <a-tag v-if="turn.error" color="red">{{ turn.error }}</a-tag>
          <AgentPlanCard v-if="turn.steps && turn.steps.length" :turn="turn" />
        </div>
      </div>
      <a-empty v-if="store.turns.length === 0" description="用自然语言描述你的修图需求" />
    </div>

    <a-divider style="margin: 8px 0" />

    <a-textarea
      v-model:value="text"
      :rows="3"
      :maxlength="1000"
      show-count
      placeholder="例如：把背景换成纯白，然后整体提亮一点"
      :disabled="!store.canEdit"
      @press-enter.exact.prevent="send"
    />
    <div class="agent-chat__tools">
      <a-space wrap>
        <a-button size="small" :disabled="!store.canEdit" @click="quickTool('adjust_image', { brightness: 0.12, saturation: 0.08 })">
          一键提亮
        </a-button>
        <a-button size="small" :disabled="!store.canEdit" @click="quickTool('remove_background', {})">
          去背景
        </a-button>
        <a-button size="small" :disabled="!store.canEdit" @click="quickTool('upscale_image', { scale: 2 })">
          超分 2x
        </a-button>
        <a-button size="small" :disabled="!store.canEdit" @click="quickTool('expand_canvas', { ratio: '1:1' })">
          扩图 1:1
        </a-button>
        <a-button size="small" :disabled="!store.canEdit" @click="quickTool('split_layers', { with_text: false })">
          拆分图层
        </a-button>
        <a-button
          size="small"
          :disabled="!store.canEdit"
          @click="quickTool('generate_marketing', { kind: 'poster' })"
        >
          营销图
        </a-button>
        <a-button
          size="small"
          :disabled="!store.canEdit"
          @click="quickTool('prepare_delivery_sizes', {})"
        >
          投放尺寸
        </a-button>
      </a-space>
    </div>
    <a-button
      type="primary"
      block
      style="margin-top: 8px"
      :loading="store.submitting"
      :disabled="!store.canEdit || !text.trim()"
      @click="send"
    >
      发送指令
    </a-button>
  </a-card>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useAgentEditorStore } from '@/stores/useAgentEditorStore'
import AgentPlanCard from '@/components/agent/AgentPlanCard.vue'

const store = useAgentEditorStore()
const text = ref('')
const listRef = ref<HTMLElement | null>(null)

function statusText(status: string) {
  const map: Record<string, string> = {
    pending: '准备中',
    waiting: '等待你确认计划',
    queued: '排队中',
    running: '执行中',
    succeeded: '已完成',
    failed: '执行失败',
    canceled: '已取消',
  }
  return map[status] ?? status
}

async function send() {
  const value = text.value.trim()
  if (!value) {
    return
  }
  try {
    await store.sendMessage(value)
    text.value = ''
  } catch (error) {
    message.error(error instanceof Error ? error.message : '指令发送失败')
  }
}

async function quickTool(tool: string, params: Record<string, unknown>) {
  try {
    await store.invokeTool(tool, params)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '工具调用失败')
  }
}

watch(
  () => store.turns.length,
  async () => {
    await nextTick()
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  },
)
</script>

<style scoped>
.agent-chat {
  display: flex;
  flex-direction: column;
}

.agent-chat__list {
  max-height: 46vh;
  overflow: auto;
  padding-right: 4px;
}

.agent-chat__turn {
  margin-bottom: 12px;
}

.agent-chat__bubble {
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.agent-chat__bubble--user {
  background: #e6f4ff;
  margin-left: 32px;
}

.agent-chat__bubble--agent {
  background: #f5f5f5;
  margin-right: 32px;
  margin-top: 6px;
}

.agent-chat__tools {
  margin-top: 8px;
}
</style>
