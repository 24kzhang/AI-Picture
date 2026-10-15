/** Fit the whole output canvas without changing its aspect ratio. */
export function fitPreview(width: number, height: number, availableWidth: number, availableHeight: number) {
  const scale = Math.max(0, Math.min(availableWidth / width, availableHeight / height))
  return { width: width * scale, height: height * scale }
}
