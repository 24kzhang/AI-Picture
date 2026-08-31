<template>
  <div id="searchPicturePage">
    <a-spin v-if="accessState === 'loading'" tip="正在验证查询图片权限…" />
    <a-result
      v-else-if="accessState === 'denied'"
      status="403"
      title="无法使用该图片检索"
      sub-title="图片不存在，或你没有所属图库的查看权限。"
    >
      <template #extra>
        <a-button type="primary" href="/">返回公共图库</a-button>
      </template>
    </a-result>
    <template v-else>
      <div class="page-heading">
        <div>
          <h2>智能向量以图搜图</h2>
          <p>由阿里云百炼视觉 Embedding 与本地 Chroma 提供语义相似度检索</p>
        </div>
        <a-tag :color="vectorAvailable ? 'green' : 'red'">
          {{ vectorAvailable ? '向量服务正常' : '向量服务不可用' }}
        </a-tag>
      </div>

      <a-alert
        v-if="!vectorAvailable"
        type="warning"
        show-icon
        message="请检查百炼 API Key 并启动 vector-service，再执行检索。"
        style="margin-bottom: 16px"
      />

      <a-card class="query-card" title="查询图片">
        <div class="query-content">
          <img :alt="picture.name" :src="picture.thumbnailUrl ?? picture.url" class="query-image" />
          <div class="query-options">
            <strong>{{ picture.name ?? '加载中…' }}</strong>
            <a-tag :color="picture.spaceId ? 'blue' : 'green'">
              {{ picture.spaceId ? '当前空间图库' : '公共图库' }}
            </a-tag>
            <a-checkbox
              v-if="picture.spaceId"
              v-model:checked="includePublic"
              @change="fetchResultData"
            >
              同时检索公共图库
            </a-checkbox>
            <span v-else class="scope-tip">公共图库检索不会读取私人或多人图库。</span>
            <a-button
              type="primary"
              :loading="loading"
              :disabled="!vectorAvailable"
              @click="fetchResultData"
            >
              重新检索
            </a-button>
          </div>
        </div>
      </a-card>

      <h3 class="result-heading">相似图片</h3>
      <a-list
        :grid="{ gutter: 16, xs: 1, sm: 2, md: 3, lg: 4, xl: 5, xxl: 6 }"
        :data-source="dataList"
        :loading="loading"
      >
        <template #renderItem="{ item }">
          <a-list-item style="padding: 0">
            <router-link :to="item.fromUrl ?? `/picture/${item.pictureId}`">
              <a-card hoverable>
                <template #cover>
                  <img :alt="item.name" :src="item.thumbUrl" class="result-image" />
                </template>
                <a-card-meta :title="item.name ?? '未命名图片'">
                  <template #description>
                    <a-space wrap>
                      <a-tag :color="scopeColor(item.scope)">{{ item.scope }}</a-tag>
                      <span>{{ similarityText(item.similarity) }}</span>
                    </a-space>
                  </template>
                </a-card-meta>
              </a-card>
            </router-link>
          </a-list-item>
        </template>
        <template #empty>
          <a-empty description="当前检索范围内暂无相似图片" />
        </template>
      </a-list>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  getPictureVoByIdUsingGet,
  getVectorHealthUsingGet,
  searchPictureByPictureUsingPost,
} from '@/api/pictureController'
import { message } from 'ant-design-vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const pictureId = computed<string | undefined>(() => {
  const value = route.query.pictureId
  if (!value) return undefined
  const raw = String(Array.isArray(value) ? value[0] : value)
  return /^\d+$/.test(raw) && raw !== '0' ? raw : undefined
})
const picture = ref<API.PictureVO>({})
const dataList = ref<API.ImageSearchResult[]>([])
const includePublic = ref(false)
const loading = ref(false)
const vectorAvailable = ref(false)
const accessState = ref<'loading' | 'granted' | 'denied'>('loading')

const fetchHealth = async () => {
  try {
    const res = await getVectorHealthUsingGet()
    vectorAvailable.value = res.data.code === 0 && res.data.data === true
  } catch {
    vectorAvailable.value = false
  }
}

const fetchPictureDetail = async () => {
  accessState.value = 'loading'
  picture.value = {}
  if (!pictureId.value) {
    accessState.value = 'denied'
    return false
  }
  try {
    const res = await getPictureVoByIdUsingGet({ id: pictureId.value })
    if (res.data.code === 0 && res.data.data) {
      picture.value = res.data.data
      accessState.value = 'granted'
      return true
    }
    accessState.value = 'denied'
    message.error('获取图片详情失败：' + res.data.message)
  } catch (error: any) {
    accessState.value = 'denied'
    message.error('获取图片详情失败：' + error.message)
  }
  return false
}

const fetchResultData = async () => {
  if (!pictureId.value || !vectorAvailable.value) return
  loading.value = true
  try {
    const res = await searchPictureByPictureUsingPost({
      pictureId: pictureId.value,
      includePublic: includePublic.value,
      limit: 12,
    })
    if (res.data.code === 0) {
      dataList.value = res.data.data ?? []
    } else {
      message.error('检索失败：' + res.data.message)
    }
  } catch (error: any) {
    message.error('检索失败：' + error.message)
  } finally {
    loading.value = false
  }
}

const similarityText = (value?: number) => {
  if (value === undefined || value === null) return '相似度未知'
  return `相似度 ${(value * 100).toFixed(1)}%`
}

const scopeColor = (scope?: string) => {
  if (scope === '公共图库') return 'green'
  if (scope === '多人图库') return 'purple'
  return 'blue'
}

onMounted(async () => {
  await fetchHealth()
  if (await fetchPictureDetail()) {
    await fetchResultData()
  }
})
</script>

<style scoped>
#searchPicturePage {
  margin-bottom: 24px;
}

.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-heading h2 {
  margin-bottom: 4px;
}

.page-heading p,
.scope-tip {
  color: #64748b;
}

.query-card {
  margin-bottom: 20px;
}

.query-content {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}

.query-image {
  width: 220px;
  height: 160px;
  object-fit: cover;
  border-radius: 8px;
}

.query-options {
  display: flex;
  align-items: flex-start;
  flex-direction: column;
  gap: 12px;
}

.result-heading {
  margin: 16px 0;
}

.result-image {
  height: 180px;
  width: 100%;
  object-fit: cover;
}
</style>
