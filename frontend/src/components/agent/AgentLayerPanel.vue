<template>
  <a-card size="small" title="图层" class="agent-layer-panel">
    <a-list size="small" :data-source="layers">
      <template #renderItem="{ item }">
        <a-list-item class="agent-layer-panel__item">
          <a-space direction="vertical" style="width: 100%">
            <a-space>
              <a-checkbox
                :checked="item.visible"
                :disabled="!store.canEdit || item.locked"
                @change="(event: any) => toggleVisible(item, event)"
              />
              <span class="agent-layer-panel__name">{{ item.name || item.id }}</span>
              <a-tag v-if="item.locked" color="default">锁定</a-tag>
            </a-space>
            <a-space>
              <span class="agent-layer-panel__label">透明度</span>
              <a-slider
                :value="Math.round((item.opacity ?? 1) * 100)"
                :min="0"
                :max="100"
                :disabled="!store.canEdit"
                :tip-formatter="(value: number) => `${value}%`"
                style="width: 120px"
                @afterChange="(value: number) => setOpacity(item, value)"
              />
              <a-button size="small" :disabled="!store.canEdit" @click="reorder(item, 'up')">
                上移
              </a-button>
              <a-button size="small" :disabled="!store.canEdit" @click="reorder(item, 'down')">
                下移
              </a-button>
            </a-space>
          </a-space>
        </a-list-item>
      </template>
    </a-list>
    <a-empty v-if="layers.length === 0" description="暂无图层" />
  </a-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { message } from 'ant-design-vue'
import { useAgentEditorStore } from '@/stores/useAgentEditorStore'
import type { AgentLayer } from '@/api/agent'

const store = useAgentEditorStore()

const layers = computed<AgentLayer[]>(() => {
  const list = store.canvas?.document?.layers ?? []
  return [...list].reverse()
})

async function toggleVisible(layer: AgentLayer, event: { target: { checked: boolean } }) {
  await call('set_layer_visible', { layer: layer.id, visible: event.target.checked })
}

async function setOpacity(layer: AgentLayer, value: number) {
  await call('set_layer_opacity', { layer: layer.id, opacity: value / 100 })
}

async function reorder(layer: AgentLayer, place: 'up' | 'down') {
  await call('reorder_layer', { layer: layer.id, place })
}

async function call(tool: string, params: Record<string, unknown>) {
  try {
    await store.invokeTool(tool, params)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '图层操作失败')
  }
}
</script>

<style scoped>
.agent-layer-panel__item {
  padding: 8px 0;
}

.agent-layer-panel__name {
  font-weight: 500;
}

.agent-layer-panel__label {
  color: #888;
  font-size: 12px;
}
</style>
