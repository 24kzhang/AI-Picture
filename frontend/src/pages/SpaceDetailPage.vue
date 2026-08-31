<template>
  <div id="spaceDetailPage">
    <a-spin v-if="accessState === 'loading'" tip="正在验证图库访问权限…" />
    <a-result
      v-else-if="accessState === 'denied'"
      status="403"
      title="无权访问该图库"
      sub-title="该图库仅对所有者或已加入的成员开放。"
    >
      <template #extra>
        <a-button type="primary" href="/">返回公共图库</a-button>
      </template>
    </a-result>
    <template v-else>
      <!-- 空间信息 -->
      <a-flex justify="space-between">
        <h2>{{ space.spaceName }} （{{ SPACE_TYPE_MAP[space.spaceType] }}）</h2>
        <a-space size="middle">
          <a-button
            v-if="canUploadPicture"
            type="primary"
            :href="`/add_picture?spaceId=${spaceId}`"
            target="_blank"
          >
            + 创建图片
          </a-button>
          <a-button
            v-if="canManageSpaceUser"
            type="primary"
            ghost
            :icon="h(TeamOutlined)"
            :href="`/spaceUserManage/${spaceId}`"
            target="_blank"
          >
            成员管理
          </a-button>
          <a-button
            type="primary"
            ghost
            :icon="h(BarChartOutlined)"
            :href="`/space_analyze?spaceId=${spaceId}`"
            target="_blank"
          >
            空间分析
          </a-button>
          <a-button v-if="canEditPicture" :icon="h(EditOutlined)" @click="doBatchEdit">
            批量编辑
          </a-button>
          <a-tooltip
            :title="`占用空间 ${formatSize(space.totalSize)} / ${formatSize(space.maxSize)}`"
          >
            <a-progress type="circle" :size="42" :percent="spaceUsagePercent" />
          </a-tooltip>
        </a-space>
      </a-flex>
      <div style="margin-bottom: 16px" />
      <!-- 搜索表单 -->
      <PictureSearchForm :onSearch="onSearch" />
      <div style="margin-bottom: 16px" />
      <!-- 按颜色搜索，跟其他搜索条件独立 -->
      <a-form-item label="按颜色搜索">
        <input
          type="color"
          value="#1677ff"
          aria-label="选择搜索颜色"
          class="native-color-picker"
          @change="onColorInput"
        />
      </a-form-item>
      <!-- 图片列表 -->
      <PictureList
        :dataList="dataList"
        :loading="loading"
        :showOp="true"
        :canEdit="canEditPicture"
        :canDelete="canDeletePicture"
        :onReload="fetchData"
      />
      <!-- 分页 -->
      <a-pagination
        style="text-align: right"
        v-model:current="searchParams.current"
        v-model:pageSize="searchParams.pageSize"
        :total="total"
        @change="onPageChange"
      />
      <BatchEditPictureModal
        ref="batchEditPictureModalRef"
        :spaceId="spaceId"
        :pictureList="dataList"
        :onSuccess="onBatchEditPictureSuccess"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { getSpaceVoByIdUsingGet } from '@/api/spaceController.ts'
import { message } from 'ant-design-vue'
import {
  listPictureVoByPageUsingPost,
  searchPictureByColorUsingPost,
} from '@/api/pictureController.ts'
import { formatSize } from '@/utils'
import PictureList from '@/components/PictureList.vue'
import { BarChartOutlined, EditOutlined, TeamOutlined } from '@ant-design/icons-vue'
import { SPACE_PERMISSION_ENUM, SPACE_TYPE_MAP } from '../constants/space.ts'
import PictureSearchForm from '@/components/PictureSearchForm.vue'
import BatchEditPictureModal from '@/components/BatchEditPictureModal.vue'

interface Props {
  id: string | number
}

const props = defineProps<Props>()
const spaceId = computed(() => String(props.id))
const space = ref<API.SpaceVO>({})
const accessState = ref<'loading' | 'granted' | 'denied'>('loading')
const spaceUsagePercent = computed(() => {
  const totalSize = Number(space.value.totalSize ?? 0)
  const maxSize = Number(space.value.maxSize ?? 0)
  if (!Number.isFinite(totalSize) || !Number.isFinite(maxSize) || maxSize <= 0) {
    return 0
  }
  return Number(((totalSize * 100) / maxSize).toFixed(1))
})

// 通用权限检查函数
function createPermissionChecker(permission: string) {
  return computed(() => {
    return (space.value.permissionList ?? []).includes(permission)
  })
}

// 定义权限检查
const canManageSpaceUser = createPermissionChecker(SPACE_PERMISSION_ENUM.SPACE_USER_MANAGE)
const canUploadPicture = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_UPLOAD)
const canEditPicture = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_EDIT)
const canDeletePicture = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_DELETE)

// -------- 获取空间详情 --------
const fetchSpaceDetail = async () => {
  accessState.value = 'loading'
  space.value = {}
  try {
    const res = await getSpaceVoByIdUsingGet({
      id: spaceId.value,
    })
    if (res.data.code === 0 && res.data.data) {
      space.value = res.data.data
      accessState.value = 'granted'
      return true
    }
    accessState.value = 'denied'
    message.error('获取空间详情失败，' + res.data.message)
  } catch (e: any) {
    accessState.value = 'denied'
    message.error('获取空间详情失败：' + e.message)
  }
  return false
}

// --------- 获取图片列表 --------

// 定义数据
const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const loading = ref(true)

// 搜索条件
const searchParams = ref<API.PictureQueryRequest>({
  current: 1,
  pageSize: 12,
  sortField: 'createTime',
  sortOrder: 'descend',
})

// 获取数据
const fetchData = async () => {
  loading.value = true
  try {
    const params = {
      spaceId: spaceId.value,
      ...searchParams.value,
    }
    const res = await listPictureVoByPageUsingPost(params)
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total ?? 0)
    } else {
      dataList.value = []
      total.value = 0
      message.error('获取数据失败，' + res.data.message)
    }
  } catch (error: any) {
    dataList.value = []
    total.value = 0
    message.error('获取数据失败：' + error.message)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (await fetchSpaceDetail()) {
    await fetchData()
  }
})

// 分页参数
const onPageChange = (page: number, pageSize: number) => {
  searchParams.value.current = page
  searchParams.value.pageSize = pageSize
  fetchData()
}

// 搜索
const onSearch = (newSearchParams: API.PictureQueryRequest) => {
  // console.log('new', newSearchParams)

  searchParams.value = {
    ...searchParams.value,
    ...newSearchParams,
    current: 1,
  }
  // console.log('searchparams', searchParams.value)
  fetchData()
}

// 按照颜色搜索
const onColorChange = async (color: string) => {
  loading.value = true
  const res = await searchPictureByColorUsingPost({
    picColor: color,
    spaceId: spaceId.value,
  })
  if (res.data.code === 0 && res.data.data) {
    const data = res.data.data ?? []
    dataList.value = data
    total.value = data.length
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
  loading.value = false
}

const onColorInput = (event: Event) => {
  const input = event.target as HTMLInputElement
  if (input.value) {
    onColorChange(input.value)
  }
}

// ---- 批量编辑图片 -----
const batchEditPictureModalRef = ref()

// 批量编辑图片成功
const onBatchEditPictureSuccess = () => {
  fetchData()
}

// 打开批量编辑图片弹窗
const doBatchEdit = () => {
  if (batchEditPictureModalRef.value) {
    batchEditPictureModalRef.value.openModal()
  }
}

// 空间 id 改变时，必须重新获取数据
watch(
  () => props.id,
  async () => {
    if (await fetchSpaceDetail()) {
      await fetchData()
    } else {
      dataList.value = []
      total.value = 0
    }
  },
)
</script>

<style scoped>
#spaceDetailPage {
  margin-bottom: 16px;
}

.native-color-picker {
  width: 42px;
  height: 32px;
  padding: 2px;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  cursor: pointer;
}
</style>
