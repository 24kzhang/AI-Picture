<template>
  <div id="pictureDetailPage">
    <a-spin v-if="accessState === 'loading'" tip="正在验证图片访问权限…" />
    <a-result
      v-else-if="accessState === 'denied'"
      status="403"
      title="无权访问该图片"
      sub-title="图片不存在，或你没有所属图库的查看权限。"
    >
      <template #extra>
        <a-button type="primary" href="/">返回公共图库</a-button>
      </template>
    </a-result>
    <template v-else>
      <a-row :gutter="[16, 16]">
        <!-- 图片预览 -->
        <a-col :sm="24" :md="16" :xl="18">
          <a-card title="图片预览">
            <a-image :src="picture.url" style="max-height: 600px; object-fit: contain" />
          </a-card>
        </a-col>
        <!-- 图片信息区域 -->
        <a-col :sm="24" :md="8" :xl="6">
          <a-card title="图片信息">
            <a-descriptions :column="1">
              <a-descriptions-item label="作者">
                <a-space>
                  <a-avatar :size="24" :src="picture.user?.userAvatar" />
                  <div>{{ picture.user?.userName }}</div>
                </a-space>
              </a-descriptions-item>
              <a-descriptions-item label="名称">
                {{ picture.name ?? '未命名' }}
              </a-descriptions-item>
              <a-descriptions-item label="简介">
                {{ picture.introduction ?? '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="分类">
                {{ picture.category ?? '默认' }}
              </a-descriptions-item>
              <a-descriptions-item label="标签">
                <a-tag v-for="tag in picture.tags" :key="tag">
                  {{ tag }}
                </a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="格式">
                {{ picture.picFormat ?? '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="宽度">
                {{ picture.picWidth ?? '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="高度">
                {{ picture.picHeight ?? '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="宽高比">
                {{ picture.picScale ?? '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="大小">
                {{ formatSize(picture.picSize) }}
              </a-descriptions-item>
              <a-descriptions-item label="主色调">
                <a-space>
                  {{ picture.picColor ?? '-' }}
                  <div
                    v-if="picture.picColor"
                    :style="{
                      width: '16px',
                      height: '16px',
                      backgroundColor: toHexColor(picture.picColor),
                    }"
                  />
                </a-space>
              </a-descriptions-item>
            </a-descriptions>
            <!-- 图片操作 -->
            <a-space wrap>
              <a-button type="primary" @click="doDownload">
                免费下载
                <template #icon>
                  <DownloadOutlined />
                </template>
              </a-button>
              <a-button :icon="h(ShareAltOutlined)" type="primary" ghost @click="doShare">
                分享
              </a-button>
              <a-button :icon="h(SearchOutlined)" type="default" @click="doSearch">
                以图搜图
              </a-button>
              <a-button v-if="canEdit" :icon="h(EditOutlined)" type="default" @click="doEdit">
                编辑
              </a-button>
              <a-button
                v-if="canEdit"
                :icon="h(RobotOutlined)"
                type="primary"
                ghost
                @click="doAgentEdit"
              >
                Agent 精修
              </a-button>
              <a-button v-if="canDelete" :icon="h(DeleteOutlined)" danger @click="doDelete">
                删除
              </a-button>
            </a-space>
          </a-card>
        </a-col>
      </a-row>
      <ShareModal ref="shareModalRef" :link="shareLink" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { deletePictureUsingPost, getPictureVoByIdUsingGet } from '@/api/pictureController.ts'
import { message } from 'ant-design-vue'
import { useRoute } from 'vue-router'
import { downloadImage, formatSize, toHexColor } from '@/utils'
import {
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  RobotOutlined,
  SearchOutlined,
  ShareAltOutlined,
} from '@ant-design/icons-vue'
import router from '@/router'
import { SPACE_PERMISSION_ENUM } from '@/constants/space.ts'
import ShareModal from '@/components/ShareModal.vue'

const route = useRoute()

interface Props {
  id: string | number
}

const props = defineProps<Props>()
const picture = ref<API.PictureVO>({})
const accessState = ref<'loading' | 'granted' | 'denied'>('loading')

// 通用权限检查函数
function createPermissionChecker(permission: string) {
  return computed(() => {
    return (picture.value.permissionList ?? []).includes(permission)
  })
}
// 定义权限检查
const canDelete = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_DELETE)
const canEdit = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_EDIT)

// 编辑图片权限检查
// const canEdit = computed(() => {
//   const loginUser = loginUserStore.loginUser
//   // 未登录不能编辑
//   if (!loginUser) {
//     message.error('请先登录')
//     return
//   }
//   // 仅本人或管理员才能编辑
//   const user = picture.value.user || {}
//   return loginUser.id === user.id || loginUser.userRole === 'admin'
// })

// 删除图片
const doDelete = async () => {
  const id = picture.value.id
  if (!id) {
    message.error('图片 id 不能为空')
    return
  }
  const res = await deletePictureUsingPost({ id })
  if (res.data.code === 0) {
    message.success('删除成功')
  } else {
    message.error('删除失败.' + res.data.message)
  }
}

// 获取图片详情
const fetchPictureDetail = async () => {
  accessState.value = 'loading'
  picture.value = {}
  try {
    // console.log("获取图片详情:", props.id)
    const res = await getPictureVoByIdUsingGet({
      id: String(props.id),
      // id,
    })
    // console.log("获取图片详情:", res)
    if (res.data.code === 0 && res.data.data) {
      picture.value = res.data.data
      accessState.value = 'granted'
      return true
    }
    accessState.value = 'denied'
    message.error('获取图片详情失败.' + res.data.message)
  } catch (e: any) {
    accessState.value = 'denied'
    message.error('error获取图片详情失败.' + e.message)
  }
  return false
}
// 编辑图片
const doEdit = () => {
  // 跳转时一定要携带 spaceId
  router.push({
    path: '/add_picture',
    query: {
      id: picture.value.id,
      spaceId: picture.value.spaceId,
    },
  })
}
// 进入 Agent 精修工作台
const doAgentEdit = () => {
  router.push({
    path: `/agent/picture/${picture.value.id}`,
    query: {
      spaceId: picture.value.spaceId,
    },
  })
}
// 下载图片
const doDownload = () => {
  const originUrl = picture.value.url
  downloadImage(originUrl, picture.value.name)
}

// 百炼向量以图搜图
const doSearch = () => {
  router.push({
    path: '/search_picture',
    query: { pictureId: picture.value.id },
  })
}

// ----- 分享操作 ----
const shareModalRef = ref()
// 分享链接
const shareLink = ref<string>()
// 分享
const doShare = () => {
  shareLink.value = `${window.location.protocol}//${window.location.host}/picture/${picture.value.id}`
  if (shareModalRef.value) {
    shareModalRef.value.openModal()
  }
}

onMounted(() => {
  fetchPictureDetail()
})
</script>

<style scoped>
#pictureDetailPage {
  margin-bottom: 20px;
}
</style>
