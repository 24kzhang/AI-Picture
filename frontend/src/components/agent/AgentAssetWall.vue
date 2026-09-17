<template>
  <div class="asset-wall">
    <div class="wall-head">
      <span class="wall-title">图片墙（{{ visibleAssets.length }}）</span>
      <a-space>
        <a-select v-model:value="kindFilter" size="small" style="width: 110px">
          <a-select-option value="">全部</a-select-option>
          <a-select-option value="candidate">候选草稿</a-select-option>
          <a-select-option value="marketing">营销图</a-select-option>
          <a-select-option value="delivery">交付尺寸</a-select-option>
          <a-select-option value="original">原图</a-select-option>
        </a-select>
        <a-button size="small" :disabled="!hasDelivery" @click="emit('export')">
          ZIP 导出
        </a-button>
      </a-space>
    </div>
    <div class="wall-list">
      <div
        v-for="asset in visibleAssets"
        :key="asset.id"
        class="asset-item"
        :class="{
          current: String(asset.id) === String(currentAssetId ?? ''),
          final: String(asset.id) === String(finalAssetId ?? ''),
        }"
      >
        <img :src="asset.thumbnailUrl || asset.url" :alt="asset.kind" @click="emit('preview', asset)" />
        <div class="asset-meta">
          <a-tag size="small">{{ kindText(asset.kind) }}</a-tag>
          <span v-if="asset.width && asset.height" class="asset-size">
            {{ asset.width }}×{{ asset.height }}
          </span>
        </div>
        <div v-if="editable && asset.kind !== 'mask'" class="asset-actions">
          <a-button size="small" @click="emit('adopt', asset)">设为画布</a-button>
          <a-button
            size="small"
            :type="String(asset.id) === String(finalAssetId ?? '') ? 'primary' : 'default'"
            @click="emit('set-final', asset)"
          >
            最终稿
          </a-button>
        </div>
      </div>
      <a-empty v-if="visibleAssets.length === 0" :image="undefined" description="暂无资产" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AgentAssetVO } from '@/api/agentController'

const props = defineProps<{
  assets: AgentAssetVO[]
  currentAssetId?: string
  finalAssetId?: string
  editable: boolean
}>()

const emit = defineEmits<{
  (e: 'adopt', asset: AgentAssetVO): void
  (e: 'set-final', asset: AgentAssetVO): void
  (e: 'preview', asset: AgentAssetVO): void
  (e: 'export'): void
}>()

const kindFilter = ref('')

const visibleAssets = computed(() =>
  (props.assets ?? []).filter(
    (asset) => asset.kind !== 'mask' && (!kindFilter.value || asset.kind === kindFilter.value),
  ),
)

const hasDelivery = computed(() =>
  (props.assets ?? []).some((asset) => ['delivery', 'candidate', 'marketing'].includes(asset.kind)),
)

const KIND_TEXT: Record<string, string> = {
  original: '原图',
  candidate: '候选',
  marketing: '营销',
  mask: '蒙版',
  delivery: '交付',
}

function kindText(kind: string) {
  return KIND_TEXT[kind] ?? kind
}
</script>

<style scoped>
.asset-wall {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.wall-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
}

.wall-title {
  font-weight: 600;
  font-size: 13px;
}

.wall-list {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  padding: 10px 12px;
  align-items: flex-start;
}

.asset-item {
  flex: 0 0 auto;
  width: 128px;
  border: 2px solid transparent;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.asset-item.current {
  border-color: #1677ff;
}

.asset-item.final {
  border-color: #52c41a;
}

.asset-item img {
  width: 124px;
  height: 92px;
  object-fit: contain;
  background: #f5f5f5;
  cursor: zoom-in;
}

.asset-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 2px 6px;
  font-size: 11px;
  color: #999;
}

.asset-size {
  font-size: 11px;
}

.asset-actions {
  display: flex;
  gap: 4px;
  padding: 4px 6px 8px;
}

.asset-actions :deep(.ant-btn) {
  flex: 1;
  font-size: 12px;
  padding: 0 4px;
}
</style>
