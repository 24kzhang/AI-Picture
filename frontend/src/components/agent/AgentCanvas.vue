<template>
  <div class="agent-canvas">
    <div class="canvas-toolbar">
      <a-radio-group v-model:value="selectionMode" size="small" button-style="solid">
        <a-radio-button value="none">浏览</a-radio-button>
        <a-radio-button value="rect" :disabled="!editable">矩形选区</a-radio-button>
        <a-radio-button value="brush" :disabled="!editable">笔刷选区</a-radio-button>
      </a-radio-group>
      <template v-if="selectionMode !== 'none'">
        <a-slider
          v-if="selectionMode === 'brush'"
          v-model:value="brushSize"
          :min="8"
          :max="120"
          style="width: 120px"
        />
        <a-button size="small" type="primary" :disabled="!hasStrokes" @click="confirmSelection">
          确定选区
        </a-button>
        <a-button size="small" :disabled="!hasStrokes" @click="resetStrokes">清除</a-button>
      </template>
      <a-tag v-if="selectionRevision" color="blue">选区 revision {{ selectionRevision }}</a-tag>
    </div>

    <div ref="viewportRef" class="canvas-viewport">
      <div
        class="canvas-stage"
        :style="{ width: `${displayWidth}px`, height: `${displayHeight}px` }"
        @mousedown="onStageMouseDown"
      >
        <template v-for="(layer, index) in layers" :key="layer.id ?? index">
          <img
            v-if="layer.kind !== 'text' && layer.visible !== false"
            :src="layer.assetUrl"
            class="canvas-layer"
            draggable="false"
            :style="layerStyle(layer)"
            alt=""
          />
          <div
            v-else-if="layer.kind === 'text' && layer.visible !== false"
            class="canvas-layer canvas-text"
            :style="textStyle(layer)"
          >
            {{ layer.text }}
          </div>
        </template>

        <!-- 矩形选区预览 -->
        <div v-if="rectPreview" class="selection-rect" :style="rectPreview"></div>

        <!-- 笔刷画布 -->
        <canvas
          v-show="selectionMode === 'brush'"
          ref="brushCanvasRef"
          class="brush-layer"
          :width="docWidth"
          :height="docHeight"
          :style="{ width: `${displayWidth}px`, height: `${displayHeight}px` }"
        ></canvas>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch, nextTick } from 'vue'

export interface CanvasLayer {
  id?: string
  name?: string
  kind?: string
  assetUrl?: string
  text?: string
  x?: number
  y?: number
  width?: number
  height?: number
  scaleX?: number
  scaleY?: number
  rotation?: number
  opacity?: number
  visible?: boolean
  locked?: boolean
  fontSize?: number
  fill?: string
}

export interface CanvasDocument {
  width?: number
  height?: number
  layers?: CanvasLayer[]
}

interface Point {
  x: number
  y: number
}

export interface RectStroke {
  type: 'rect'
  x: number
  y: number
  width: number
  height: number
}

export interface BrushStroke {
  type: 'brush'
  size: number
  points: Point[]
}

export type SelectionMarker = RectStroke | BrushStroke

const props = defineProps<{
  document?: CanvasDocument | null
  editable: boolean
  selectionRevision?: number
}>()

const emit = defineEmits<{
  (e: 'selection', payload: { maskBlob: Blob; markers: SelectionMarker[] }): void
}>()

const viewportRef = ref<HTMLDivElement>()
const brushCanvasRef = ref<HTMLCanvasElement>()

const selectionMode = ref<'none' | 'rect' | 'brush'>('none')
const brushSize = ref(36)

const docWidth = computed(() => props.document?.width ?? 1)
const docHeight = computed(() => props.document?.height ?? 1)
const layers = computed<CanvasLayer[]>(() => props.document?.layers ?? [])

const displayWidth = ref(0)
const displayHeight = ref(0)
const scale = computed(() => (docWidth.value > 0 ? displayWidth.value / docWidth.value : 1))

function measure() {
  const viewport = viewportRef.value
  if (!viewport) return
  const availWidth = viewport.clientWidth - 24
  const availHeight = viewport.clientHeight - 24
  const ratio = Math.min(availWidth / docWidth.value, availHeight / docHeight.value, 1.5)
  displayWidth.value = Math.max(1, Math.round(docWidth.value * ratio))
  displayHeight.value = Math.max(1, Math.round(docHeight.value * ratio))
}

onMounted(() => {
  measure()
  window.addEventListener('resize', measure)
})
watch([docWidth, docHeight], async () => {
  await nextTick()
  measure()
  resetStrokes()
})

function layerStyle(layer: CanvasLayer) {
  const x = (layer.x ?? 0) * scale.value
  const y = (layer.y ?? 0) * scale.value
  const width = (layer.width ?? 0) * scale.value
  const height = (layer.height ?? 0) * scale.value
  const scaleX = layer.scaleX ?? 1
  const scaleY = layer.scaleY ?? 1
  const rotation = layer.rotation ?? 0
  return {
    left: `${x}px`,
    top: `${y}px`,
    width: `${width}px`,
    height: `${height}px`,
    transform: `rotate(${rotation}deg) scale(${scaleX}, ${scaleY})`,
    opacity: layer.opacity ?? 1,
  }
}

function textStyle(layer: CanvasLayer) {
  return {
    left: `${(layer.x ?? 0) * scale.value}px`,
    top: `${(layer.y ?? 0) * scale.value}px`,
    fontSize: `${(layer.fontSize ?? 24) * scale.value}px`,
    color: layer.fill ?? '#000',
    opacity: layer.opacity ?? 1,
    transform: `rotate(${layer.rotation ?? 0}deg)`,
  }
}

// region 选区交互

const rectPreview = ref<Record<string, string> | null>(null)
const rectStrokes = ref<RectStroke[]>([])
const brushStrokes = ref<BrushStroke[]>([])
const drawing = ref(false)
const startPoint = ref({ x: 0, y: 0 })

const hasStrokes = computed(
  () => rectStrokes.value.length > 0 || brushStrokes.value.length > 0,
)

function stagePoint(event: MouseEvent) {
  const stage = (event.currentTarget as HTMLElement).getBoundingClientRect()
  return {
    x: (event.clientX - stage.left) / scale.value,
    y: (event.clientY - stage.top) / scale.value,
  }
}

function onStageMouseDown(event: MouseEvent) {
  if (selectionMode.value === 'none' || !props.editable) return
  drawing.value = true
  startPoint.value = stagePoint(event)
  if (selectionMode.value === 'brush') {
    brushStrokes.value.push({
      type: 'brush',
      size: brushSize.value,
      points: [startPoint.value],
    })
    redrawBrush()
  }
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
}

function onMouseMove(event: MouseEvent) {
  if (!drawing.value) return
  const stage = brushCanvasRef.value?.getBoundingClientRect()
  if (!stage) return
  const point = {
    x: (event.clientX - stage.left) / scale.value,
    y: (event.clientY - stage.top) / scale.value,
  }
  if (selectionMode.value === 'rect') {
    const x = Math.min(startPoint.value.x, point.x)
    const y = Math.min(startPoint.value.y, point.y)
    const width = Math.abs(point.x - startPoint.value.x)
    const height = Math.abs(point.y - startPoint.value.y)
    rectPreview.value = {
      left: `${x * scale.value}px`,
      top: `${y * scale.value}px`,
      width: `${width * scale.value}px`,
      height: `${height * scale.value}px`,
    }
    rectStrokes.value = [{ type: 'rect', x, y, width, height }]
  } else {
    const last = brushStrokes.value[brushStrokes.value.length - 1]
    if (last) {
      last.points.push(point)
      redrawBrush()
    }
  }
}

function onMouseUp() {
  drawing.value = false
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
  if (selectionMode.value === 'rect' && rectStrokes.value.length > 0) {
    // 预览保留，等待用户点击“确定选区”
  }
}

function redrawBrush() {
  const canvas = brushCanvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.strokeStyle = 'rgba(64, 150, 255, 0.65)'
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  for (const stroke of brushStrokes.value) {
    ctx.lineWidth = stroke.size
    ctx.beginPath()
    stroke.points.forEach((point: Point, index: number) => {
      if (index === 0) ctx.moveTo(point.x, point.y)
      else ctx.lineTo(point.x, point.y)
    })
    ctx.stroke()
  }
}

function resetStrokes() {
  rectStrokes.value = []
  brushStrokes.value = []
  rectPreview.value = null
  redrawBrush()
}

/**
 * 生成黑白 mask（白=生效区域）并回传标记数据
 */
function confirmSelection() {
  const canvas = document.createElement('canvas')
  canvas.width = docWidth.value
  canvas.height = docHeight.value
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.fillStyle = '#000'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = '#fff'
  ctx.strokeStyle = '#fff'
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  const markers: SelectionMarker[] = []
  for (const stroke of rectStrokes.value) {
    ctx.fillRect(stroke.x, stroke.y, stroke.width, stroke.height)
    markers.push({ ...stroke })
  }
  for (const stroke of brushStrokes.value) {
    ctx.lineWidth = stroke.size
    ctx.beginPath()
    stroke.points.forEach((point: Point, index: number) => {
      if (index === 0) ctx.moveTo(point.x, point.y)
      else ctx.lineTo(point.x, point.y)
    })
    ctx.stroke()
    markers.push({ type: 'brush', size: stroke.size, points: stroke.points.slice(0, 200) })
  }
  canvas.toBlob((blob) => {
    if (blob) {
      emit('selection', { maskBlob: blob, markers })
    }
  }, 'image/png')
}

watch(selectionMode, (mode) => {
  if (mode !== 'brush') {
    brushStrokes.value = []
    redrawBrush()
  }
  if (mode !== 'rect') {
    rectStrokes.value = []
    rectPreview.value = null
  }
})

// endregion
</script>

<style scoped>
.agent-canvas {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.canvas-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
}

.canvas-viewport {
  flex: 1;
  overflow: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f5;
  padding: 12px;
}

.canvas-stage {
  position: relative;
  background: #fff;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.12);
  overflow: hidden;
}

.canvas-layer {
  position: absolute;
  pointer-events: none;
  transform-origin: center center;
}

.canvas-text {
  white-space: pre;
  font-family: sans-serif;
  line-height: 1.2;
}

.selection-rect {
  position: absolute;
  border: 2px dashed #1677ff;
  background: rgba(22, 119, 255, 0.12);
  pointer-events: none;
}

.brush-layer {
  position: absolute;
  left: 0;
  top: 0;
  pointer-events: none;
}
</style>
