<template>
  <div class="layer-panel">
    <div class="panel-title">图层（{{ layers.length }}）</div>
    <div class="layer-list">
      <div
        v-for="(layer, index) in layersTopDown"
        :key="layer.id ?? index"
        class="layer-item"
      >
        <img
          v-if="layer.kind !== 'text'"
          :src="layer.assetUrl"
          class="layer-thumb"
          alt=""
        />
        <div v-else class="layer-thumb text-thumb">T</div>
        <div class="layer-info">
          <div class="layer-name">{{ layer.name || layer.id }}</div>
          <a-slider
            :value="Math.round((layer.opacity ?? 1) * 100)"
            :min="0"
            :max="100"
            :disabled="!editable"
            style="margin: 0"
            @afterChange="(value: number) => onOpacity(layer, value)"
          />
        </div>
        <div class="layer-actions">
          <a-button size="small" type="text" :disabled="!editable" @click="emit('visible', layer, !(layer.visible !== false))">
            {{ layer.visible !== false ? '👁' : '🚫' }}
          </a-button>
          <div class="layer-move">
            <a-button size="small" type="text" :disabled="!editable || isTop(index)" @click="emit('reorder', layer, 'up')">↑</a-button>
            <a-button size="small" type="text" :disabled="!editable || isBottom(index)" @click="emit('reorder', layer, 'down')">↓</a-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CanvasDocument, CanvasLayer } from './AgentCanvas.vue'

const props = defineProps<{
  document?: CanvasDocument | null
  editable: boolean
}>()

const emit = defineEmits<{
  (e: 'visible', layer: CanvasLayer, visible: boolean): void
  (e: 'opacity', layer: CanvasLayer, opacity: number): void
  (e: 'reorder', layer: CanvasLayer, place: 'up' | 'down'): void
}>()

const layers = computed<CanvasLayer[]>(() => props.document?.layers ?? [])
// 展示顺序：顶层在上
const layersTopDown = computed(() => [...layers.value].reverse())

function isTop(displayIndex: number) {
  return displayIndex === 0
}

function isBottom(displayIndex: number) {
  return displayIndex === layersTopDown.value.length - 1
}

function onOpacity(layer: CanvasLayer, value: number) {
  emit('opacity', layer, Math.max(0, Math.min(1, value / 100)))
}
</script>

<style scoped>
.layer-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.panel-title {
  font-weight: 600;
  font-size: 13px;
  padding: 10px 12px;
  border-bottom: 1px solid #f0f0f0;
}

.layer-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.layer-item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 6px;
  border-radius: 6px;
  border: 1px solid #f0f0f0;
  margin-bottom: 8px;
  background: #fff;
}

.layer-thumb {
  width: 40px;
  height: 40px;
  object-fit: cover;
  border-radius: 4px;
  background: #fafafa;
}

.text-thumb {
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  color: #999;
}

.layer-info {
  flex: 1;
  min-width: 0;
}

.layer-name {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.layer-actions {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.layer-move {
  display: flex;
}
</style>
