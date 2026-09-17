<template>
  <a-card size="small" class="agent-canvas">
    <template #title>
      <span>画布</span>
      <span class="agent-canvas__size">
        {{ document?.width ?? 0 }} × {{ document?.height ?? 0 }}
      </span>
    </template>
    <template #extra>
      <a-space wrap>
        <a-radio-group v-model:value="mode" size="small" button-style="solid">
          <a-radio-button value="view">查看</a-radio-button>
          <a-radio-button value="point" :disabled="!store.canEdit">点选</a-radio-button>
          <a-radio-button value="brush" :disabled="!store.canEdit">笔刷</a-radio-button>
        </a-radio-group>
        <a-button size="small" :disabled="!store.canEdit" @click="store.prepareSelection()">
          预热选区
        </a-button>
        <a-button size="small" :disabled="!store.selection" @click="store.clearSelection()">
          清除选区
        </a-button>
      </a-space>
    </template>

    <div class="agent-canvas__wrap">
      <canvas
        ref="canvasRef"
        class="agent-canvas__stage"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointerleave="onPointerUp"
      />
      <div v-if="!document" class="agent-canvas__empty">
        <a-empty description="画布尚未就绪" />
      </div>
    </div>

    <div class="agent-canvas__footer">
      <span v-if="store.selection">
        已选择区域（修订 {{ store.selection.revision }}），标记 {{ store.selection.markers?.length ?? 0 }} 处
      </span>
      <span v-else class="agent-canvas__hint">
        提示：点选或涂抹需要修改的区域后，输入“消除这里的物体”等指令
      </span>
      <a-space v-if="mode === 'brush'">
        <span>笔刷大小</span>
        <a-slider v-model:value="radius" :min="0.005" :max="0.12" :step="0.005" style="width: 120px" />
      </a-space>
    </div>
  </a-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useAgentEditorStore } from '@/stores/useAgentEditorStore'

const store = useAgentEditorStore()
const canvasRef = ref<HTMLCanvasElement | null>(null)
const mode = ref<'view' | 'point' | 'brush'>('view')
const radius = ref(0.03)

const document = computed(() => store.canvas?.document ?? null)

/** 素材 id -> 已加载图片 */
const imageCache = new Map<string, HTMLImageElement>()
/** 笔刷当前笔画（归一化坐标） */
let stroke: { x: number; y: number }[] = []
let painting = false

function assetUrl(assetId?: string | null) {
  if (!assetId) {
    return ''
  }
  const asset = store.canvas?.assets?.find((item) => item.id === assetId)
  return asset?.url ?? ''
}

async function loadImage(assetId: string): Promise<HTMLImageElement | null> {
  const url = assetUrl(assetId)
  if (!url) {
    return null
  }
  const cached = imageCache.get(assetId)
  if (cached) {
    return cached
  }
  return new Promise((resolve) => {
    const image = new Image()
    image.crossOrigin = 'anonymous'
    image.onload = () => {
      imageCache.set(assetId, image)
      resolve(image)
    }
    image.onerror = () => resolve(null)
    image.src = url
  })
}

/** 将文档按比例绘制到画布（居中留白） */
async function render() {
  const canvas = canvasRef.value
  const doc = document.value
  if (!canvas || !doc) {
    return
  }
  const containerWidth = canvas.parentElement?.clientWidth ?? 640
  const maxHeight = Math.max(360, window.innerHeight - 320)
  const scale = Math.min(containerWidth / doc.width, maxHeight / doc.height, 1)
  const width = Math.round(doc.width * scale)
  const height = Math.round(doc.height * scale)
  canvas.width = width
  canvas.height = height
  canvas.style.width = `${width}px`
  canvas.style.height = `${height}px`

  const ctx = canvas.getContext('2d')
  if (!ctx) {
    return
  }
  ctx.clearRect(0, 0, width, height)
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, width, height)

  for (const layer of doc.layers) {
    if (!layer.visible) {
      continue
    }
    const image = await loadImage(layer.asset_id)
    if (!image) {
      continue
    }
    ctx.save()
    ctx.globalAlpha = layer.opacity ?? 1
    const transform = layer.transform
    ctx.translate(transform.x * width, transform.y * height)
    ctx.scale(transform.scale_x ?? 1, transform.scale_y ?? 1)
    ctx.rotate(((transform.rotation ?? 0) * Math.PI) / 180)
    const layerWidth = (layer.width || doc.width) * scale
    const layerHeight = (layer.height || doc.height) * scale
    ctx.drawImage(image, 0, 0, layerWidth, layerHeight)
    ctx.restore()
  }

  // 选区遮罩叠加
  const selection = store.selection
  if (selection?.mask?.url) {
    const maskImage = await loadImage(selection.mask.id)
    if (maskImage) {
      ctx.save()
      ctx.globalAlpha = 0.45
      ctx.globalCompositeOperation = 'multiply'
      ctx.drawImage(maskImage, 0, 0, width, height)
      ctx.restore()
    }
  }

  // 笔刷预览
  if (painting && stroke.length > 1) {
    ctx.save()
    ctx.strokeStyle = 'rgba(22, 119, 255, 0.85)'
    ctx.lineWidth = Math.max(2, radius.value * width)
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    ctx.beginPath()
    stroke.forEach((point, index) => {
      const px = point.x * width
      const py = point.y * height
      if (index === 0) {
        ctx.moveTo(px, py)
      } else {
        ctx.lineTo(px, py)
      }
    })
    ctx.stroke()
    ctx.restore()
  }
}

function toNormalized(event: PointerEvent) {
  const canvas = canvasRef.value
  if (!canvas) {
    return null
  }
  const rect = canvas.getBoundingClientRect()
  const x = (event.clientX - rect.left) / rect.width
  const y = (event.clientY - rect.top) / rect.height
  if (x < 0 || x > 1 || y < 0 || y > 1) {
    return null
  }
  return { x: Number(x.toFixed(4)), y: Number(y.toFixed(4)) }
}

async function onPointerDown(event: PointerEvent) {
  if (!store.canEdit) {
    return
  }
  const point = toNormalized(event)
  if (!point) {
    return
  }
  if (mode.value === 'point') {
    await submit({ points: [point] })
  } else if (mode.value === 'brush') {
    painting = true
    stroke = [point]
    canvasRef.value?.setPointerCapture(event.pointerId)
    void render()
  }
}

function onPointerMove(event: PointerEvent) {
  if (!painting || !store.canEdit) {
    return
  }
  const point = toNormalized(event)
  if (point) {
    stroke.push(point)
    void render()
  }
}

async function onPointerUp() {
  if (!painting) {
    return
  }
  painting = false
  const points = stroke
  stroke = []
  if (points.length > 1) {
    await submit({ strokes: [points], radius: radius.value })
  } else {
    void render()
  }
}

async function submit(payload: {
  points?: { x: number; y: number }[]
  strokes?: { x: number; y: number }[][]
  radius?: number
}) {
  const revision = store.canvas?.revision
  if (!revision) {
    return
  }
  try {
    await store.createSelection({ revision, ...payload })
    message.success('选区已创建')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '选区创建失败')
  }
}

watch(
  () => [store.canvas?.revision, store.canvas?.assets?.length, store.selection?.revision],
  () => void render(),
)

watch(document, () => void render(), { deep: true })

onMounted(() => {
  void render()
  window.addEventListener('resize', render)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', render)
})

defineExpose({ render })
</script>

<style scoped>
.agent-canvas__size {
  margin-left: 8px;
  color: #999;
  font-size: 12px;
}

.agent-canvas__wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 360px;
  background: repeating-conic-gradient(#f0f0f0 0% 25%, #ffffff 0% 50%) 50% / 20px 20px;
  border: 1px solid #eee;
  border-radius: 6px;
  overflow: auto;
}

.agent-canvas__stage {
  touch-action: none;
  cursor: crosshair;
}

.agent-canvas__empty {
  padding: 60px 0;
}

.agent-canvas__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  color: #666;
  font-size: 12px;
}

.agent-canvas__hint {
  color: #999;
}
</style>
