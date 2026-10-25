/** 历史本地仓库图片使用当前站点代理，避免保留旧开发端口。 */
export function galleryImageUrl(value?: string): string | undefined {
  if (!value) return value
  const parsed = (() => {
    try { return new URL(value, window.location.origin) } catch { return undefined }
  })()
  if (!parsed || !/^\/api\/files\//i.test(parsed.pathname)) return value
  const apiOrigin = String(import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
  // 开发环境前端端口和后端 API 端口可能不同；受控图片接口需要沿用 API 会话。
  return apiOrigin ? `${apiOrigin}${parsed.pathname}${parsed.search}${parsed.hash}`
    : `${parsed.pathname}${parsed.search}${parsed.hash}`
}
