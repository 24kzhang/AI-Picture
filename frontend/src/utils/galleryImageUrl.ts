/** 历史本地仓库图片使用当前站点代理，避免保留旧开发端口。 */
export function galleryImageUrl(value?: string): string | undefined {
  return value?.replace(
    /^https?:\/\/(?:localhost|127\.0\.0\.1|\[::1\])(?::\d+)?(?=\/api\/files\/)/i,
    '',
  )
}
