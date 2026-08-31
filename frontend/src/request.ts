import axios from 'axios'
import { message } from 'ant-design-vue'

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  timeout: 120000,
  withCredentials: true,
})

service.interceptors.request.use(
  (config) => config,
  (error) => Promise.reject(error),
)

service.interceptors.response.use(
  (response) => {
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
