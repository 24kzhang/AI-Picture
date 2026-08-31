<template>
  <a-modal v-model:open="visible" :width="1160" :footer="null" title="协同图片工作台" style="top: 12px" @cancel="closeModal">
    <div class="status-bar">
      <div class="status-main">
        <div class="editing-status"><i :class="{ live: isTeamSpace }"></i><b>{{ isTeamSpace ? '多人协同' : '个人编辑' }}</b><span>{{ statusText }}</span></div>
        <div v-if="isTeamSpace" class="watchers" aria-label="工作台观看列表">
          <span class="watcher-title">观看列表 · {{ viewerUsers.length }}</span>
          <div class="watcher-list">
            <span
              v-for="(viewer, index) in viewerUsers"
              :key="String(viewer.id ?? index)"
              class="watcher-chip"
              :class="{ self: isCurrentUser(viewer) }"
              :title="viewerName(viewer)"
            >
              <span class="watcher-avatar">{{ viewerInitial(viewer) }}</span>
              <span>{{ viewerName(viewer) }}{{ isCurrentUser(viewer) ? '（我）' : '' }}</span>
            </span>
            <span v-if="!viewerUsers.length" class="watcher-empty">连接中…</span>
          </div>
        </div>
      </div>
      <a-space v-if="isTeamSpace">
        <a-button v-if="canEnterEdit" type="primary" ghost @click="enterEdit">获取编辑权</a-button>
        <a-button v-if="canExitEdit" danger ghost @click="exitEdit">释放编辑权</a-button>
      </a-space>
    </div>

    <div class="editor-shell">
      <aside class="tool-rail">
        <label>历史</label>
        <div class="tool-grid">
          <button :disabled="!canEdit || !history.length" @click="undo">↶<small>撤销</small></button>
          <button :disabled="!canEdit || !future.length" @click="redo">↷<small>重做</small></button>
        </div>
        <label>变换</label>
        <div class="tool-grid">
          <button :disabled="!canEdit" @click="zoom(0.1)">＋<small>放大</small></button>
          <button :disabled="!canEdit" @click="zoom(-0.1)">－<small>缩小</small></button>
          <button :disabled="!canEdit" @click="rotate(-90)">↺<small>左转</small></button>
          <button :disabled="!canEdit" @click="rotate(90)">↻<small>右转</small></button>
          <button :disabled="!canEdit" @click="flip('x')">⇆<small>水平</small></button>
          <button :disabled="!canEdit" @click="flip('y')">⇅<small>垂直</small></button>
        </div>
        <label>创作</label>
        <div class="tool-grid">
          <button :class="{ selected: tool === 'brush' }" :disabled="!canEdit" @click="toggleBrush">✎<small>画笔</small></button>
          <button :disabled="!canEdit" @click="openAiEditor">✦<small>AI 编辑</small></button>
        </div>
        <button class="reset" :disabled="!canEdit" @click="resetEditor(true)">重置画布</button>
      </aside>

      <main class="stage">
        <div class="canvas-wrap" :class="{ drawing: tool === 'brush' && canEdit, 'over-sticker': Boolean(hoveredStickerId), grabbing: Boolean(dragState) }">
          <canvas
            ref="canvasRef"
            @pointerdown="startPointer" @pointermove="movePointer"
            @pointerup="endPointer" @pointercancel="endPointer" @pointerleave="leavePointer"
          ></canvas>
          <span v-if="imageLoading" class="loading">正在载入图片…</span>
        </div>
        <div class="meta"><span>{{ canvasSize }}</span><span>{{ Math.round(state.scale * 100) }}%</span></div>
      </main>

      <aside class="properties">
        <section>
          <header><b>画笔</b><small>{{ brushWidth }} px</small></header>
          <div class="brush-row"><input v-model="brushColor" type="color" :disabled="!canEdit" /><a-slider v-model:value="brushWidth" :min="2" :max="48" :disabled="!canEdit" /></div>
        </section>
        <section>
          <header><b>文字</b><small>添加到画布中心</small></header>
          <a-input-group compact><a-input v-model:value="textValue" :disabled="!canEdit" placeholder="输入文字" @press-enter="addText" /><a-button type="primary" :disabled="!canEdit || !textValue.trim()" @click="addText">添加</a-button></a-input-group>
        </section>
        <section>
          <header><b>贴图</b><small>点击添加 · 画布拖动</small></header>
          <div class="stickers"><button v-for="item in stickers" :key="item" :disabled="!canEdit" @click="addSticker(item)">{{ item }}</button></div>
        </section>
        <section class="filters">
          <header><b>光影与色彩</b><small>实时预览</small></header>
          <label v-for="item in filterControls" :key="item.key">
            <span>{{ item.label }} <b>{{ state.filters[item.key] }}</b></span>
            <a-slider :value="state.filters[item.key]" :min="item.min" :max="item.max" :disabled="!canEdit" @change="(value: number) => updateFilter(item.key, value)" />
          </label>
        </section>
      </aside>
    </div>

    <footer class="footer"><span><b>{{ state.strokes.length }}</b> 条笔迹 · <b>{{ state.overlays.length }}</b> 个图层</span><a-space><a-button @click="closeModal">取消</a-button><a-button type="primary" :loading="loading" :disabled="!canEdit || imageLoading" @click="saveImage">保存并应用</a-button></a-space></footer>
    <ImageAiEditor ref="aiRef" :picture-id="picture?.id" :image-url="state.baseImageUrl" @apply="applyAiResult" />
  </a-modal>
</template>

<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { uploadPictureUsingPost } from '@/api/pictureController.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import PictureEditWebSocket from '@/utils/pictureEditWebSocket.ts'
import { PICTURE_EDIT_ACTION_ENUM, PICTURE_EDIT_MESSAGE_TYPE_ENUM } from '@/constants/picture.ts'
import { SPACE_PERMISSION_ENUM, SPACE_TYPE_ENUM } from '@/constants/space.ts'
import ImageAiEditor from '@/components/ImageAiEditor.vue'

interface Props { imageUrl?: string; picture?: API.PictureVO; spaceId?: string; space?: API.SpaceVO; onSuccess?: (picture: API.PictureVO) => void }
interface Point { x: number; y: number }
interface Stroke { points: Point[]; color: string; width: number }
interface Overlay { id: string; type: 'text' | 'sticker'; value: string; x: number; y: number; color: string; size: number }
type FilterKey = 'brightness' | 'contrast' | 'saturation' | 'grayscale' | 'sepia'
type Filters = Record<FilterKey, number>
interface EditorState { baseImageUrl: string; scale: number; rotation: number; flipX: number; flipY: number; filters: Filters; strokes: Stroke[]; overlays: Overlay[] }
interface StickerDrag { id: string; pointerId: number; start: Point; originX: number; originY: number; beforeState: string; moved: boolean }
interface PresenceMessage { viewers?: API.UserVO[]; editingUser?: API.UserVO | null }

const props = defineProps<Props>()
const defaults = (url = ''): EditorState => ({ baseImageUrl: url, scale: 1, rotation: 0, flipX: 1, flipY: 1, filters: { brightness: 0, contrast: 0, saturation: 0, grayscale: 0, sepia: 0 }, strokes: [], overlays: [] })
const state = ref(defaults(props.imageUrl))
const visible = ref(false)
const loading = ref(false)
const imageLoading = ref(false)
const canvasRef = ref<HTMLCanvasElement>()
const sourceImage = ref<HTMLImageElement>()
const sourceSize = ref({ width: 0, height: 0 })
const tool = ref<'select' | 'brush'>('select')
const brushColor = ref('#ff5b45')
const brushWidth = ref(8)
const textValue = ref('')
const history = ref<string[]>([])
const future = ref<string[]>([])
const activeStroke = ref<Stroke>()
const hoveredStickerId = ref<string>()
const dragState = ref<StickerDrag>()
const stickers = ['✨', '❤️', '🌿', '☀️', '☁️', '⭐', '🎨', '📌']
const filterControls: Array<{ key: FilterKey; label: string; min: number; max: number }> = [
  { key: 'brightness', label: '亮度', min: -80, max: 80 }, { key: 'contrast', label: '对比度', min: -80, max: 80 },
  { key: 'saturation', label: '饱和度', min: -100, max: 100 }, { key: 'grayscale', label: '黑白', min: 0, max: 100 },
  { key: 'sepia', label: '复古', min: 0, max: 100 },
]

const isTeamSpace = computed(() => props.space?.spaceType === SPACE_TYPE_ENUM.TEAM)
const userStore = useLoginUserStore()
const editingUser = ref<API.UserVO>()
const hasEditPermission = computed(() => props.space?.permissionList?.includes(SPACE_PERMISSION_ENUM.PICTURE_EDIT) ?? false)
const canEnterEdit = computed(() => !editingUser.value && hasEditPermission.value)
const viewerUsers = ref<API.UserVO[]>([])
const sameUser = (left?: number | string, right?: number | string) => left != null && right != null && String(left) === String(right)
const isCurrentUser = (viewer: API.UserVO) => sameUser(viewer.id, userStore.loginUser.id)
const viewerName = (viewer: API.UserVO) => viewer.userName || viewer.userAccount || '访客'
const viewerInitial = (viewer: API.UserVO) => viewerName(viewer).trim().slice(0, 1) || '访'
const updatePresence = (data?: PresenceMessage) => { if (Array.isArray(data?.viewers)) viewerUsers.value = data.viewers; editingUser.value = data?.editingUser || undefined }

const canExitEdit = computed(() => sameUser(editingUser.value?.id, userStore.loginUser.id))
const canEdit = computed(() => !isTeamSpace.value || canExitEdit.value)
const statusText = computed(() => isTeamSpace.value ? (editingUser.value ? editingUser.value.userName + ' 正在编辑' : '当前无人占用编辑权') : '所有处理均在浏览器本地完成')
const canvasSize = computed(() => sourceSize.value.width ? sourceSize.value.width + ' × ' + sourceSize.value.height : '等待图片')
const clone = () => JSON.parse(JSON.stringify(state.value)) as EditorState
const snapshot = () => JSON.stringify(state.value)

const filterCss = () => {
  const f = state.value.filters
  return 'brightness(' + (100 + f.brightness) + '%) contrast(' + (100 + f.contrast) + '%) saturate(' + (100 + f.saturation) + '%) grayscale(' + f.grayscale + '%) sepia(' + f.sepia + '%)'
}
const paintStroke = (ctx: CanvasRenderingContext2D, stroke: Stroke) => {
  if (!stroke.points.length) return
  const canvas = ctx.canvas
  ctx.save(); ctx.beginPath(); ctx.strokeStyle = stroke.color; ctx.lineWidth = Math.max(1, stroke.width * canvas.width); ctx.lineCap = 'round'; ctx.lineJoin = 'round'
  stroke.points.forEach((p, index) => index ? ctx.lineTo(p.x * canvas.width, p.y * canvas.height) : ctx.moveTo(p.x * canvas.width, p.y * canvas.height))
  if (stroke.points.length === 1) ctx.lineTo(stroke.points[0].x * canvas.width + 0.1, stroke.points[0].y * canvas.height + 0.1)
  ctx.stroke(); ctx.restore()
}
const render = () => window.requestAnimationFrame(() => {
  const canvas = canvasRef.value; const image = sourceImage.value; const ctx = canvas?.getContext('2d')
  if (!canvas || !image || !ctx) return
  ctx.clearRect(0, 0, canvas.width, canvas.height); ctx.save(); ctx.translate(canvas.width / 2, canvas.height / 2)
  ctx.rotate(state.value.rotation * Math.PI / 180); ctx.scale(state.value.scale * state.value.flipX, state.value.scale * state.value.flipY)
  ctx.filter = filterCss(); ctx.drawImage(image, -canvas.width / 2, -canvas.height / 2, canvas.width, canvas.height); ctx.restore(); ctx.filter = 'none'
  state.value.strokes.forEach((stroke) => paintStroke(ctx, stroke)); if (activeStroke.value) paintStroke(ctx, activeStroke.value)
  state.value.overlays.forEach((item) => { ctx.save(); const size = Math.max(18, item.size * canvas.width); ctx.font = (item.type === 'text' ? '700 ' : '') + size + 'px Microsoft YaHei, sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillStyle = item.color; ctx.shadowColor = 'rgba(0,0,0,.22)'; ctx.shadowBlur = item.type === 'text' ? 4 : 0; ctx.fillText(item.value, item.x * canvas.width, item.y * canvas.height); ctx.restore() })
})
const loadSource = (url: string) => new Promise<void>((resolve, reject) => {
  imageLoading.value = true; const image = new Image(); image.crossOrigin = 'anonymous'
  image.onload = () => { sourceImage.value = image; sourceSize.value = { width: image.naturalWidth, height: image.naturalHeight }; if (canvasRef.value) { canvasRef.value.width = image.naturalWidth; canvasRef.value.height = image.naturalHeight }; imageLoading.value = false; render(); resolve() }
  image.onerror = () => { imageLoading.value = false; reject(new Error('图片加载失败，请检查图片跨域配置')) }; image.src = url
})
const remember = () => { history.value.push(snapshot()); if (history.value.length > 30) history.value.shift(); future.value = [] }
let websocket: PictureEditWebSocket | null = null
const sync = (action: string) => { if (websocket && isTeamSpace.value && canEdit.value) websocket.sendMessage({ type: PICTURE_EDIT_MESSAGE_TYPE_ENUM.EDIT_ACTION, editAction: action, editData: { state: clone() } }) }
const commit = (action: string, change: () => void) => { if (!canEdit.value) return; remember(); change(); render(); sync(action) }

const zoom = (value: number) => commit(value > 0 ? PICTURE_EDIT_ACTION_ENUM.ZOOM_IN : PICTURE_EDIT_ACTION_ENUM.ZOOM_OUT, () => state.value.scale = Math.min(2.5, Math.max(0.2, Number((state.value.scale + value).toFixed(2)))))
const rotate = (value: number) => commit(value < 0 ? PICTURE_EDIT_ACTION_ENUM.ROTATE_LEFT : PICTURE_EDIT_ACTION_ENUM.ROTATE_RIGHT, () => state.value.rotation = (state.value.rotation + value + 360) % 360)
const flip = (axis: 'x' | 'y') => commit(axis === 'x' ? PICTURE_EDIT_ACTION_ENUM.FLIP_HORIZONTAL : PICTURE_EDIT_ACTION_ENUM.FLIP_VERTICAL, () => axis === 'x' ? state.value.flipX *= -1 : state.value.flipY *= -1)
const updateFilter = (key: FilterKey, value: number) => commit(PICTURE_EDIT_ACTION_ENUM.SET_FILTER, () => state.value.filters[key] = value)
const toggleBrush = () => tool.value = tool.value === 'brush' ? 'select' : 'brush'
const addText = () => { const value = textValue.value.trim(); if (!value) return; commit(PICTURE_EDIT_ACTION_ENUM.ADD_TEXT, () => state.value.overlays.push({ id: String(Date.now()), type: 'text', value, x: .5, y: .5, color: brushColor.value, size: .055 })); textValue.value = '' }
const addSticker = (value: string) => {
  tool.value = 'select'
  commit(PICTURE_EDIT_ACTION_ENUM.ADD_STICKER, () => { const offset = state.value.overlays.length % 5 * .04; state.value.overlays.push({ id: String(Date.now()), type: 'sticker', value, x: .42 + offset, y: .42 + offset, color: '#fff', size: .09 }) })
}
const pointAt = (event: PointerEvent): Point | undefined => {
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  return { x: Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)), y: Math.min(1, Math.max(0, (event.clientY - rect.top) / rect.height)) }
}
const stickerAt = (point: Point): Overlay | undefined => {
  const canvas = canvasRef.value
  const ctx = canvas?.getContext('2d')
  if (!canvas || !ctx) return
  const rect = canvas.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  for (let index = state.value.overlays.length - 1; index >= 0; index--) {
    const item = state.value.overlays[index]
    if (item.type !== 'sticker') continue
    const fontSize = Math.max(18, item.size * canvas.width)
    ctx.save()
    ctx.font = fontSize + 'px Microsoft YaHei, sans-serif'
    const metrics = ctx.measureText(item.value)
    ctx.restore()
    const glyphWidth = Math.max(fontSize, metrics.width)
    const glyphHeight = Math.max(fontSize, metrics.actualBoundingBoxAscent + metrics.actualBoundingBoxDescent)
    const minHitWidth = 44 * canvas.width / rect.width
    const minHitHeight = 44 * canvas.height / rect.height
    const hitX = Math.max(minHitWidth, glyphWidth * 1.25) / canvas.width / 2
    const hitY = Math.max(minHitHeight, glyphHeight * 1.25) / canvas.height / 2
    if (Math.abs(point.x - item.x) <= hitX && Math.abs(point.y - item.y) <= hitY) return item
  }
}
const startPointer = (event: PointerEvent) => {
  if (!canEdit.value || !event.isPrimary || event.button !== 0) return
  const point = pointAt(event)
  if (!point) return
  if (tool.value === 'brush') {
    canvasRef.value?.setPointerCapture(event.pointerId)
    activeStroke.value = { points: [point], color: brushColor.value, width: brushWidth.value / Math.max(1, canvasRef.value?.width || 1) }
    render()
    return
  }
  const sticker = stickerAt(point)
  if (!sticker) return
  event.preventDefault()
  canvasRef.value?.setPointerCapture(event.pointerId)
  hoveredStickerId.value = sticker.id
  dragState.value = { id: sticker.id, pointerId: event.pointerId, start: point, originX: sticker.x, originY: sticker.y, beforeState: snapshot(), moved: false }
}
const movePointer = (event: PointerEvent) => {
  const point = pointAt(event)
  if (!point) return
  if (activeStroke.value) {
    activeStroke.value.points.push(point)
    render()
    return
  }
  const drag = dragState.value
  if (drag) {
    if (drag.pointerId !== event.pointerId) return
    event.preventDefault()
    const sticker = state.value.overlays.find((item) => item.id === drag.id)
    if (!sticker) return
    const nextX = Math.min(.96, Math.max(.04, drag.originX + point.x - drag.start.x))
    const nextY = Math.min(.96, Math.max(.04, drag.originY + point.y - drag.start.y))
    if (Math.abs(nextX - drag.originX) > .001 || Math.abs(nextY - drag.originY) > .001) drag.moved = true
    sticker.x = nextX
    sticker.y = nextY
    render()
    return
  }
  hoveredStickerId.value = tool.value === 'select' && canEdit.value ? stickerAt(point)?.id : undefined
}
const endPointer = (event: PointerEvent) => {
  if (activeStroke.value) {
    const stroke = activeStroke.value
    activeStroke.value = undefined
    commit(PICTURE_EDIT_ACTION_ENUM.BRUSH_STROKE, () => state.value.strokes.push(stroke))
  }
  const drag = dragState.value
  if (drag && drag.pointerId !== event.pointerId) return
  if (drag?.moved) {
    history.value.push(drag.beforeState)
    if (history.value.length > 30) history.value.shift()
    future.value = []
    sync(PICTURE_EDIT_ACTION_ENUM.MOVE_STICKER)
  }
  dragState.value = undefined
  const canvas = canvasRef.value
  if (canvas?.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId)
}
const leavePointer = (event: PointerEvent) => {
  if (activeStroke.value || dragState.value) endPointer(event)
  hoveredStickerId.value = undefined
}
const applyState = async (next: EditorState) => { const changed = next.baseImageUrl !== state.value.baseImageUrl; state.value = JSON.parse(JSON.stringify(next)) as EditorState; if (changed) { try { await loadSource(next.baseImageUrl) } catch (error) { message.error(error instanceof Error ? error.message : '协同图片加载失败') } } else render() }
const undo = () => { const previous = history.value.pop(); if (!previous) return; future.value.unshift(snapshot()); void applyState(JSON.parse(previous) as EditorState); sync(PICTURE_EDIT_ACTION_ENUM.UNDO) }
const redo = () => { const next = future.value.shift(); if (!next) return; history.value.push(snapshot()); void applyState(JSON.parse(next) as EditorState); sync(PICTURE_EDIT_ACTION_ENUM.REDO) }
const resetEditor = async (record: boolean) => { if (!props.imageUrl) return; if (record) remember(); state.value = defaults(props.imageUrl); try { await loadSource(props.imageUrl); if (record) sync(PICTURE_EDIT_ACTION_ENUM.RESET) } catch (error) { message.error(error instanceof Error ? error.message : '图片加载失败') } }

const aiRef = ref<InstanceType<typeof ImageAiEditor>>()
const openAiEditor = () => aiRef.value?.openModal()
const applyAiResult = async (url: string) => { remember(); state.value.baseImageUrl = url; try { await loadSource(url); sync(PICTURE_EDIT_ACTION_ENUM.AI_EDIT) } catch (error) { const previous = history.value.pop(); if (previous) await applyState(JSON.parse(previous) as EditorState); message.error(error instanceof Error ? error.message : 'AI 编辑结果载入失败') } }
const saveImage = () => {
  const canvas = canvasRef.value
  if (!canvas) return
  loading.value = true
  try {
    canvas.toBlob(async (blob) => {
      if (!blob) {
        loading.value = false
        message.error('图片合成失败')
        return
      }
      const file = new File([blob], (props.picture?.name || 'image') + '.png', { type: 'image/png' })
      try {
        const params: API.PictureUploadRequest = props.picture?.id ? { id: props.picture.id } : {}
        params.spaceId = props.spaceId
        const res = await uploadPictureUsingPost(params, {}, file)
        const response = res.data as API.BaseResponsePictureVO_
        if (response.code !== 0 || !response.data) throw new Error(response.message || '图片上传失败')
        message.success('编辑结果已保存')
        props.onSuccess?.(response.data)
        closeModal()
      } catch (error) {
        message.error(error instanceof Error ? error.message : '图片上传失败')
      } finally {
        loading.value = false
      }
    }, 'image/png')
  } catch (error) {
    loading.value = false
    message.error(error instanceof Error ? error.message : '画布导出失败，请检查图片跨域配置')
  }
}

const initWebsocket = () => {
  const id = props.picture?.id; if (!id || !isTeamSpace.value || !visible.value) return; websocket?.disconnect(); websocket = new PictureEditWebSocket(id); websocket.connect()
  websocket.on(PICTURE_EDIT_MESSAGE_TYPE_ENUM.INFO, (msg) => { updatePresence(msg); message.info(msg.message); if (canExitEdit.value) window.setTimeout(() => sync(PICTURE_EDIT_ACTION_ENUM.SYNC_STATE), 80) })
  websocket.on(PICTURE_EDIT_MESSAGE_TYPE_ENUM.ERROR, (msg) => message.error(msg.message))
  websocket.on(PICTURE_EDIT_MESSAGE_TYPE_ENUM.ENTER_EDIT, (msg) => { updatePresence(msg); message.info(msg.message); if (sameUser(msg.user?.id, userStore.loginUser.id)) window.setTimeout(() => sync(PICTURE_EDIT_ACTION_ENUM.SYNC_STATE), 80) })
  websocket.on(PICTURE_EDIT_MESSAGE_TYPE_ENUM.EDIT_ACTION, (msg) => { updatePresence(msg); const remote = msg.editData?.state as EditorState | undefined; if (remote) void applyState(remote); if (msg.editAction !== PICTURE_EDIT_ACTION_ENUM.SYNC_STATE && msg.editAction !== PICTURE_EDIT_ACTION_ENUM.BRUSH_STROKE) message.info(msg.message) })
  websocket.on(PICTURE_EDIT_MESSAGE_TYPE_ENUM.EXIT_EDIT, (msg) => { updatePresence(msg); message.info(msg.message) })
}
const enterEdit = () => websocket?.sendMessage({ type: PICTURE_EDIT_MESSAGE_TYPE_ENUM.ENTER_EDIT })
const exitEdit = () => websocket?.sendMessage({ type: PICTURE_EDIT_MESSAGE_TYPE_ENUM.EXIT_EDIT })
const openModal = async () => { visible.value = true; viewerUsers.value = []; history.value = []; future.value = []; await nextTick(); await resetEditor(false); if (isTeamSpace.value) initWebsocket() }
const closeModal = () => { if (canExitEdit.value) exitEdit(); websocket?.disconnect(); websocket = null; editingUser.value = undefined; viewerUsers.value = []; activeStroke.value = undefined; hoveredStickerId.value = undefined; dragState.value = undefined; visible.value = false }
watch(() => props.imageUrl, (url) => { if (visible.value && url && url !== state.value.baseImageUrl) void resetEditor(false) })
onUnmounted(() => websocket?.disconnect())
defineExpose({ openModal })
</script>

<style scoped>
.status-bar,.footer{display:flex;align-items:center;justify-content:space-between}.status-bar{gap:16px;margin-bottom:14px;padding:11px 14px;border:1px solid #dce4e9;border-radius:14px;background:#f7f9fa}.status-main,.editing-status,.watchers,.watcher-list{display:flex;align-items:center}.status-main{min-width:0;flex:1;gap:14px}.editing-status{flex:none;gap:8px}.editing-status span{color:#7b8992}.status-bar i{width:9px;height:9px;border-radius:50%;background:#9aa5ab}.status-bar i.live{background:#26a269;box-shadow:0 0 0 4px rgb(38 162 105 / 14%)}.watchers{min-width:0;gap:8px;padding-left:14px;border-left:1px solid #dce4e9}.watcher-title{flex:none;color:#657681;font-size:12px;font-weight:700}.watcher-list{min-width:0;gap:6px;overflow-x:auto;scrollbar-width:none}.watcher-list::-webkit-scrollbar{display:none}.watcher-chip{display:flex;align-items:center;flex:none;gap:5px;padding:3px 8px 3px 4px;border:1px solid #dce5e9;border-radius:999px;background:#fff;color:#4d616d;font-size:12px;box-shadow:0 2px 7px rgb(67 87 98 / 6%)}.watcher-avatar{display:grid;place-items:center;width:22px;height:22px;border-radius:50%;background:#e5f3ed;color:#168455;font-size:11px;font-weight:800}.watcher-empty{color:#98a5ad;font-size:12px}
.watcher-chip.self{border-color:#b8dccd;background:#f2faf6;color:#236449}
.editor-shell{display:grid;grid-template-columns:128px minmax(0,1fr) 245px;height:clamp(500px,calc(100vh - 292px),718px);min-height:0;overflow:hidden;border:1px solid #d2dde3;border-radius:20px;background:#eef3f5;box-shadow:0 16px 44px rgb(68 88 99 / 10%)}.tool-rail{display:flex;flex-direction:column;gap:13px;padding:18px 12px;border-right:1px solid #d9e2e7;background:#f7f9fa;color:#2f414b}.tool-rail>label{color:#87949c;font-size:11px;letter-spacing:.14em}.tool-grid{display:grid;grid-template-columns:1fr 1fr;gap:7px}.tool-grid button{display:grid;place-items:center;gap:1px;min-height:54px;border:1px solid #d6e0e5;border-radius:11px;background:#fff;color:#31434d;box-shadow:0 2px 8px rgb(73 91 101 / 6%);cursor:pointer;font-size:22px}.tool-grid small{color:#78868f;font-size:10px}.tool-grid button:hover:not(:disabled),.tool-grid button.selected{border-color:#db922f;background:#fff7e9;color:#b96d10}.tool-grid button:disabled,.reset:disabled{cursor:not-allowed;opacity:.38}.reset{margin-top:auto;padding:9px;border:1px solid #d6e0e5;border-radius:10px;background:#fff;color:#586a74;cursor:pointer}
.stage{display:grid;grid-template-rows:1fr auto;min-width:0;padding:22px;background:#eef3f5}.canvas-wrap{position:relative;display:flex;align-items:center;justify-content:center;min-height:0;overflow:hidden;border:1px solid #d5dde1;border-radius:14px;background-color:#e8ecee;background-image:linear-gradient(45deg,#d8dee1 25%,transparent 25%),linear-gradient(-45deg,#d8dee1 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#d8dee1 75%),linear-gradient(-45deg,transparent 75%,#d8dee1 75%);background-position:0 0,0 10px,10px -10px,-10px 0;background-size:20px 20px}.canvas-wrap.drawing canvas{cursor:crosshair}.canvas-wrap.over-sticker canvas{cursor:grab}.canvas-wrap.grabbing canvas{cursor:grabbing}.canvas-wrap canvas{display:block;max-width:100%;max-height:100%;width:auto;height:auto;box-shadow:0 14px 38px rgb(66 83 92 / 20%);touch-action:none}.loading{position:absolute;padding:10px 16px;border-radius:999px;background:rgb(45 60 68 / 82%);color:#fff}.meta{display:flex;gap:18px;padding-top:12px;color:#647681;font-size:12px}.meta span:last-child{margin-left:auto;color:#b96d10}
.properties{display:flex;flex-direction:column;gap:19px;padding:18px;border-left:1px solid #dce3e7;background:#f8fafb;overflow-y:auto}.properties section{display:grid;gap:9px}.properties header{display:flex;align-items:baseline;justify-content:space-between;color:#263640}.properties header small{color:#95a0a7;font-size:10px}.brush-row{display:grid;grid-template-columns:42px 1fr;align-items:center;gap:10px}.brush-row input{width:38px;height:32px;padding:2px;border:1px solid #cad5dc;border-radius:8px;background:#fff}.stickers{display:grid;grid-template-columns:repeat(4,1fr);gap:7px}.stickers button{display:grid;place-items:center;aspect-ratio:1;border:1px solid #dbe2e6;border-radius:10px;background:#fff;cursor:pointer;font-size:22px}.stickers button:hover:not(:disabled){border-color:#e49a3a;transform:translateY(-1px)}.filters{overflow-y:auto}.filters label>span{display:flex;justify-content:space-between;color:#65747d;font-size:12px}.filters label>span b{color:#303f48}.footer{padding-top:16px;color:#73818a}
</style>
