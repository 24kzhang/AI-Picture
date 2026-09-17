<template>
  <div class="chat-panel">
    <div ref="listRef" class="chat-list">
      <div v-if="messages.length === 0" class="chat-empty">
        发送自然语言指令开始精修，例如：
        <div class="chat-suggestions">
          <a-tag class="suggestion" @click="emit('send', '把背景换成白色')">把背景换成白色</a-tag>
          <a-tag class="suggestion" @click="emit('send', '把背景换成白色并整体调亮')">
            把背景换成白色并整体调亮
          </a-tag>
          <a-tag class="suggestion" @click="emit('send', '出一套 1:1、4:5、9:16 的投放尺寸')">
            出投放尺寸
          </a-tag>
        </div>
      </div>

      <template v-for="msg in messages" :key="msg.key">
        <div v-if="msg.role === 'user'" class="chat-row user">
          <div class="bubble user-bubble">{{ msg.text }}</div>
        </div>
        <div v-else class="chat-row agent">
          <div class="bubble agent-bubble">
            <div v-if="msg.text" class="agent-reply">{{ msg.text }}</div>
            <AgentPlanCard
              v-if="msg.run"
              :run="msg.run"
              :acting="actingRunId === msg.run.id"
              @confirm="(id) => emit('confirm', id)"
              @cancel="(id) => emit('cancel', id)"
              @retry="(id) => emit('retry', id)"
            />
          </div>
        </div>
      </template>
    </div>

    <div class="chat-input">
      <a-textarea
        v-model:value="draft"
        :rows="2"
        :disabled="!editable"
        placeholder="描述你想要的修改，Ctrl+Enter 发送"
        @keydown.ctrl.enter="send"
      />
      <a-button
        type="primary"
        :loading="sending"
        :disabled="!editable || !draft.trim()"
        @click="send"
      >
        发送
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import AgentPlanCard from './AgentPlanCard.vue'
import type { AgentRunVO } from '@/api/agentController'

export interface ChatMessage {
  key: string
  role: 'user' | 'agent'
  text?: string
  run?: AgentRunVO
}

const props = defineProps<{
  messages: ChatMessage[]
  sending?: boolean
  actingRunId?: string
  editable: boolean
}>()

const emit = defineEmits<{
  (e: 'send', text: string): void
  (e: 'confirm', runId: string): void
  (e: 'cancel', runId: string): void
  (e: 'retry', runId: string): void
}>()

const draft = ref('')
const listRef = ref<HTMLDivElement>()

function send() {
  const text = draft.value.trim()
  if (!text || props.sending || !props.editable) return
  draft.value = ''
  emit('send', text)
}

watch(
  () => props.messages.length,
  async () => {
    await nextTick()
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  },
)
</script>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.chat-empty {
  color: #999;
  font-size: 13px;
  text-align: center;
  margin-top: 40px;
}

.chat-suggestions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
  align-items: center;
}

.suggestion {
  cursor: pointer;
}

.chat-row {
  display: flex;
  margin-bottom: 10px;
}

.chat-row.user {
  justify-content: flex-end;
}

.chat-row.agent {
  justify-content: flex-start;
}

.bubble {
  max-width: 92%;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
}

.user-bubble {
  background: #1677ff;
  color: #fff;
}

.agent-bubble {
  background: #fff;
  border: 1px solid #f0f0f0;
}

.agent-reply {
  white-space: pre-wrap;
}

.chat-input {
  border-top: 1px solid #f0f0f0;
  padding: 10px;
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
</style>
