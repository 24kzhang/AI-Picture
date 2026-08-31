<template>
  <a-modal
    v-model:open="visible"
    class="ai-editor-modal"
    :width="860"
    :footer="null"
    title="AI 创意编辑"
    @cancel="closeModal"
  >
    <div class="ai-editor-shell">
      <div class="preview-grid">
        <section class="preview-card">
          <span class="preview-label">当前图片</span>
          <img :src="imageUrl" alt="当前图片" />
        </section>
        <section class="preview-card result-card">
          <span class="preview-label">AI 预览</span>
          <img v-if="resultImageUrl" :src="resultImageUrl" alt="AI 编辑结果" />
          <div v-else class="empty-result">
            <span class="spark">✦</span>
            <strong>{{ generating ? '正在理解并重绘…' : '描述你希望改变的内容' }}</strong>
            <small>{{ generating ? '正在生成，请耐心等待' : '按指令调整主体、背景或画面风格' }}</small>
          </div>
        </section>
      </div>

      <div class="prompt-panel">
        <label class="field-label">编辑指令</label>
        <a-textarea
          v-model:value="prompt"
          :maxlength="800"
          :rows="3"
          show-count
          placeholder="例如：保持人物和构图不变，把背景改成雨后的霓虹街道，增强电影感光影"
        />
        <div class="prompt-examples">
          <button v-for="item in quickPrompts" :key="item" type="button" @click="prompt = item">
            {{ item }}
          </button>
        </div>

        <div class="modal-actions">
          <a-button @click="closeModal">取消</a-button>
          <a-button
            type="primary"
            ghost
            :loading="generating"
            :disabled="!prompt.trim() || generating"
            @click="createTask"
          >
            {{ resultImageUrl ? '重新生成' : '生成预览' }}
          </a-button>
          <a-button v-if="resultImageUrl" type="primary" @click="applyResult">
            载入编辑器
          </a-button>
        </div>
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  createPictureAiEditTaskUsingPost,
  getPictureAiEditTaskUsingGet,
} from '@/api/pictureController.ts'

interface Props {
  pictureId?: string
  imageUrl?: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  apply: [imageUrl: string]
}>()

const visible = ref(false)
const prompt = ref('')
const submitting = ref(false)
const taskId = ref<string>()
const resultImageUrl = ref('')
const generating = computed(() => submitting.value || Boolean(taskId.value))

const quickPrompts = [
  '保持主体不变，把背景改成柔和的日落海边',
  '转换为精致的水彩插画风格，保留原有构图',
  '提升画面清晰度和质感，光线更自然',
]

let pollingTimer: number | undefined

const clearPolling = () => {
  if (pollingTimer !== undefined) {
    window.clearInterval(pollingTimer)
    pollingTimer = undefined
  }
  taskId.value = undefined
}

const pollTask = () => {
  pollingTimer = window.setInterval(async () => {
    if (!taskId.value) return
    try {
      const res = await getPictureAiEditTaskUsingGet({ taskId: taskId.value })
      if (res.data.code !== 0 || !res.data.data?.output) {
        throw new Error(res.data.message || '查询 AI 编辑任务失败')
      }
      const output = res.data.data.output as API.ImageEditTaskOutput
      if (output.taskStatus === 'SUCCEEDED') {
        const result = output.results?.find((item) => item.url)
        if (!result?.url) throw new Error('AI 任务没有返回可用图片')
        resultImageUrl.value = result.url
        clearPolling()
        message.success('AI 编辑预览已生成')
      } else if (['FAILED', 'CANCELED', 'UNKNOWN'].includes(output.taskStatus || '')) {
        const reason = output.message || output.results?.find((item) => item.message)?.message
        clearPolling()
        message.error('AI 编辑失败' + (reason ? '：' + reason : ''))
      }
    } catch (error) {
      clearPolling()
      message.error(error instanceof Error ? error.message : 'AI 编辑任务查询失败')
    }
  }, 1500)
}

const createTask = async () => {
  if (generating.value) return
  if (!props.pictureId || !prompt.value.trim()) {
    message.warning('请先填写编辑指令')
    return
  }
  resultImageUrl.value = ''
  submitting.value = true
  try {
    const res = await createPictureAiEditTaskUsingPost({
      pictureId: props.pictureId,
      prompt: prompt.value.trim(),
    })
    const newTaskId = res.data.data?.output?.taskId
    if (res.data.code !== 0 || !newTaskId) {
      throw new Error(res.data.message || 'AI 编辑任务创建失败')
    }
    taskId.value = newTaskId
    message.info('任务已提交，请稍候')
    pollTask()
  } catch (error) {
    clearPolling()
    message.error(error instanceof Error ? error.message : 'AI 编辑任务创建失败')
  } finally {
    submitting.value = false
  }
}

const applyResult = () => {
  if (!resultImageUrl.value) return
  emit('apply', resultImageUrl.value)
  closeModal()
}

const openModal = () => {
  visible.value = true
}

const closeModal = () => {
  visible.value = false
  clearPolling()
}

onUnmounted(clearPolling)

defineExpose({ openModal })
</script>

<style scoped>
.ai-editor-shell {
  display: grid;
  gap: 22px;
}

.preview-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.preview-card {
  position: relative;
  display: grid;
  place-items: center;
  min-height: 250px;
  overflow: hidden;
  border: 1px solid #dbe3ec;
  border-radius: 18px;
  background: #eef2f5;
}

.preview-card img {
  width: 100%;
  height: 300px;
  object-fit: contain;
}

.preview-label {
  position: absolute;
  z-index: 1;
  top: 12px;
  left: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgb(16 28 38 / 78%);
  color: #fff;
  font-size: 12px;
  letter-spacing: 0.06em;
}

.result-card {
  background: radial-gradient(circle at 50% 30%, #fff4ce 0, #e9eef2 65%);
}

.empty-result {
  display: grid;
  place-items: center;
  gap: 5px;
  color: #52616d;
}

.empty-result .spark {
  color: #e89c31;
  font-size: 34px;
}

.empty-result small {
  color: #89949c;
}

.prompt-panel {
  display: grid;
  gap: 12px;
}

.field-label {
  color: #263640;
  font-weight: 650;
}

.prompt-examples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.prompt-examples button {
  padding: 6px 10px;
  border: 1px solid #d8e0e5;
  border-radius: 999px;
  background: #fff;
  color: #53616b;
  cursor: pointer;
  font-size: 12px;
}

.prompt-examples button:hover {
  border-color: #d28a2c;
  color: #a9640e;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 4px;
}

@media (max-width: 720px) {
  .preview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
