<template>
  <section class="picture-generation">
    <a-typography-paragraph type="secondary">描述想要的画面，预览生成结果后点击“插入图库”。图片将保存到当前选择的空间。</a-typography-paragraph>
    <a-form layout="vertical">
      <a-form-item v-if="recent.length" label="最近生成">
        <a-select :value="task?.id" placeholder="继续查看之前的任务" :options="recent.map((item) => ({ value: item.id, label: `${item.prompt.slice(0, 32)} · ${item.savedPictureId ? '已保存' : labels[item.status]}` }))" :disabled="busy || submitting || !!submission" @change="selectRecent" />
      </a-form-item>
      <a-form-item label="画面描述" required>
        <a-textarea v-model:value="prompt" placeholder="例如：傍晚的湖边咖啡店，木桌上放着一杯拿铁，窗外有行人和树影，真实摄影风格" :maxlength="4000" show-count :rows="4" :disabled="busy || submitting || !!submission" />
      </a-form-item>
      <a-form-item label="图片尺寸">
        <a-radio-group v-model:value="size" :disabled="busy || submitting || !!submission">
          <a-radio-button value="1024x1024">方图</a-radio-button><a-radio-button value="1280x720">横图</a-radio-button><a-radio-button value="720x1280">竖图</a-radio-button>
        </a-radio-group>
      </a-form-item>
      <a-button type="primary" :loading="busy || submitting" :disabled="!prompt.trim()" @click="generate">{{ submission ? '重试创建请求' : task ? '重新生成' : '生成图片' }}</a-button>
    </a-form>
    <a-alert v-if="statusText" :type="task?.status === 'FAILED' || task?.status === 'UNKNOWN' || error ? 'warning' : 'info'" show-icon :message="error || statusText" style="margin-top: 16px">
      <template v-if="error && task" #action><a-button size="small" @click="refresh">重新查询</a-button></template>
    </a-alert>
    <a-button v-if="task?.status === 'OUTPUT_PENDING'" style="margin-top: 12px" @click="transfer">重试转存图片</a-button>
    <div v-if="task?.resultUrl" class="generated-result">
      <a-image :src="task.resultUrl" alt="AI 生成图片预览" :style="{ maxWidth: '100%' }" />
      <p>{{ task.width }} × {{ task.height }}</p>
      <a-form layout="vertical"><a-form-item label="图片名称"><a-input v-model:value="name" placeholder="AI 生成图片" :maxlength="100" :disabled="!!task.savedPictureId || saving" /></a-form-item></a-form>
      <a-button type="primary" :loading="saving" :disabled="!!task.savedPictureId" @click="save">{{ task.savedPictureId ? '已插入图库' : '插入图库' }}</a-button>
      <router-link v-if="task.savedPictureId" :to="`/picture/${task.savedPictureId}`" style="margin-left: 12px">查看图片</router-link>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { createImageGeneration, getImageGeneration, listImageGenerations, retryImageTransfer, saveGeneratedPicture, type ImageGenerationInput, type ImageGenerationTask } from '@/api/pictureGeneration'
const props = defineProps<{ spaceId?: string; onSuccess?: (picture: API.PictureVO) => void }>()
const user = useLoginUserStore()
const task = ref<ImageGenerationTask>()
const recent = ref<ImageGenerationTask[]>([])
const submission = ref<ImageGenerationInput>()
const prompt = ref(''), size = ref('1024x1024'), name = ref('AI 生成图片'), error = ref('')
const submitting = ref(false), saving = ref(false)
const busy = computed(() => !!task.value && ['QUEUED', 'RUNNING', 'TRANSFER'].includes(task.value.status))
const key = computed(() => `gallery-image-generation:${user.loginUser.id ?? 'guest'}:${props.spaceId ?? 'public'}`)
const labels: Record<ImageGenerationTask['status'], string> = { QUEUED: '任务已排队，可刷新页面后继续查看', RUNNING: '正在生成图片，可刷新页面后继续查看', TRANSFER: '图片已生成，正在保存预览', SUCCEEDED: '生成完成，确认预览后可插入图库', FAILED: '生成失败，可修改描述后重新生成', UNKNOWN: '生成结果未知，请明确点击重新生成', OUTPUT_PENDING: '图片已生成，等待重试转存' }
const statusText = computed(() => error.value || task.value?.errorMessage || (task.value ? labels[task.value.status] : ''))
let timer: number | undefined, alive = true, polling = false
function persist() { sessionStorage.setItem(key.value, JSON.stringify({ taskId: task.value?.id, submission: submission.value })) }
async function loadRecent() { const scope = key.value; try { const response = await listImageGenerations(props.spaceId); if (alive && key.value === scope && response.data.code === 0) recent.value = response.data.data } catch { /* A selected task can still be polled independently. */ } }
function presetFor(width: number, height: number) { return width / height > 1.2 ? '1280x720' : width / height < 0.8 ? '720x1280' : '1024x1024' }
async function selectRecent(id: string) { task.value = recent.value.find((item) => item.id === id); if (task.value) { prompt.value = task.value.prompt; size.value = presetFor(task.value.width, task.value.height); error.value = ''; persist(); await refresh() } }
function schedule() { window.clearTimeout(timer); if (busy.value && alive) timer = window.setTimeout(() => { void refresh() }, 1500) }
async function refresh() {
  if (!task.value || polling || !alive) return
  const scope = key.value, id = task.value.id; polling = true
  try {
    const response = await getImageGeneration(id)
    if (!alive || key.value !== scope) return
    if (response.data.code !== 0) throw new Error(response.data.message || '任务读取失败')
    task.value = response.data.data; recent.value = recent.value.map((item) => item.id === id ? response.data.data : item); error.value = ''; persist()
  } catch (cause) { if (alive && key.value === scope) error.value = cause instanceof Error ? cause.message : '任务读取失败，可重新查询' }
  finally { polling = false; schedule() }
}
async function generate() {
  if (busy.value || submitting.value || !prompt.value.trim()) return
  const scope = key.value; submitting.value = true; error.value = ''
  if (!submission.value) { const [width, height] = size.value.split('x').map(Number); submission.value = { prompt: prompt.value.trim(), width, height, spaceId: props.spaceId, idempotencyKey: crypto.randomUUID() }; persist() }
  try {
    const response = await createImageGeneration(submission.value)
    if (!alive || key.value !== scope) return
    if (response.data.code !== 0) { submission.value = undefined; persist(); throw new Error(response.data.message || '生图任务创建失败') }
    task.value = response.data.data; submission.value = undefined; persist(); schedule(); void loadRecent()
  } catch (cause) { if (alive && key.value === scope) error.value = cause instanceof Error ? cause.message : '请求未确认，重试将查询同一任务' }
  finally { submitting.value = false }
}
async function transfer() {
  if (!task.value) return
  const scope = key.value
  try { const response = await retryImageTransfer(task.value.id); if (!alive || key.value !== scope) return; if (response.data.code !== 0) throw new Error(response.data.message); task.value = response.data.data; error.value = ''; schedule() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '转存失败' }
}
async function save() {
  if (!task.value || saving.value || task.value.savedPictureId) return
  const scope = key.value; saving.value = true
  try {
    const response = await saveGeneratedPicture(task.value.id, name.value)
    if (!alive || key.value !== scope) return
    if (response.data.code !== 0) throw new Error(response.data.message || '插入图库失败')
    task.value.savedPictureId = String(response.data.data.id); persist(); props.onSuccess?.(response.data.data); message.success('AI 图片已插入图库'); void loadRecent()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '插入图库失败，生成结果已保留' }
  finally { saving.value = false }
}
watch(key, async () => {
  window.clearTimeout(timer); task.value = undefined; submission.value = undefined; recent.value = []; error.value = ''; void loadRecent()
  try {
    const saved = JSON.parse(sessionStorage.getItem(key.value) || '{}') as { taskId?: string; submission?: ImageGenerationInput }
    if (saved.submission) { submission.value = saved.submission; prompt.value = saved.submission.prompt; size.value = `${saved.submission.width}x${saved.submission.height}` }
    if (saved.taskId) { task.value = { id: saved.taskId, status: 'QUEUED', prompt: '', width: 0, height: 0 }; await refresh(); if (task.value) { prompt.value = task.value.prompt; size.value = presetFor(task.value.width, task.value.height) } }
  } catch { sessionStorage.removeItem(key.value) }
}, { immediate: true })
onUnmounted(() => { alive = false; window.clearTimeout(timer) })
</script>
<style scoped>
.generated-result { margin-top: 20px; padding: 16px; border: 1px solid #e5e7eb; border-radius: 12px; background: #fafafa }
.generated-result :deep(.ant-image) { width: 100% }
.generated-result :deep(.ant-image-img) { max-height: 480px; object-fit: contain }
.generated-result p { color: #64748b }
</style>
