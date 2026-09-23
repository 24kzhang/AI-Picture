import axios from 'axios'
import { message } from 'ant-design-vue'

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  timeout: 120000,
  withCredentials: true,
})

const pictureUrlFields = new Set(['url', 'thumbnailUrl', 'assetUrl', 'outputImageUrl'])

/**
 * 历史图片记录可能保留旧的本地端口（例如 8080）。
 * 只改写本地文件接口 URL，外部 COS/第三方图片地址保持不变。
 */
const normalizePictureUrls = (value: unknown, field?: string): unknown => {
  if (typeof value === 'string' && field && pictureUrlFields.has(field) && value.includes('/api/files/')) {
    try {
      const currentApiOrigin = new URL(service.defaults.baseURL ?? window.location.origin, window.location.origin).origin
      const parsed = new URL(value, window.location.origin)
      if (parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1') {
        return `${currentApiOrigin}${parsed.pathname}${parsed.search}${parsed.hash}`
      }
    } catch {
      // 保留无法解析的原始值，让浏览器继续报告真实资源错误。
    }
    return value
  }
  if (Array.isArray(value)) {
    return value.map((item) => normalizePictureUrls(item))
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, normalizePictureUrls(item, key)]),
    )
  }
  return value
}

service.interceptors.request.use(
  (config) => config,
  (error) => Promise.reject(error),
)

service.interceptors.response.use(
  (response) => {
    response.data = normalizePictureUrls(response.data)
    const { data } = response
    if (data.code === 40100) {
      const responseUrl = response.request?.responseURL ?? ''
      if (!responseUrl.includes('user/get/login') && !window.location.pathname.includes('/user/login')) {
        message.warning('请先登录')
        const redirect = encodeURIComponent(window.location.href)
        window.location.href = `/user/login?redirect=${redirect}`
      }
    }
    return response
  },
  (error) => {
    if (error.code === 'ECONNABORTED') {
      message.error('请求超时，请检查后端、百炼 API 与向量服务状态')
    }
    return Promise.reject(error)
  },
)

export const request = service
