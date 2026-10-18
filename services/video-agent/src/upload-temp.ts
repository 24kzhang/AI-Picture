import { readdir, stat, unlink } from 'node:fs/promises'
import { basename, resolve } from 'node:path'

export const uploadTempTtlMs = 60 * 60 * 1000

function isUploadTemp(name: string) {
  return /^upload-[0-9a-f-]{36}\.bin$/i.test(name)
}

export async function removeUploadTemp(path: string) {
  try {
    await unlink(path)
  } catch (error: any) {
    if (error?.code !== 'ENOENT') throw error
  }
}

export async function cleanupUploadTempFiles(
  directory: string,
  now = Date.now(),
  ttlMs = uploadTempTtlMs,
) {
  let names: string[]
  try {
    names = await readdir(directory)
  } catch (error: any) {
    if (error?.code === 'ENOENT') return 0
    throw error
  }
  let removed = 0
  for (const name of names) {
    if (!isUploadTemp(name)) continue
    const path = resolve(directory, basename(name))
    try {
      const info = await stat(path)
      if (now - info.mtimeMs < ttlMs) continue
      await removeUploadTemp(path)
      removed++
    } catch (error: any) {
      if (error?.code !== 'ENOENT') throw error
    }
  }
  return removed
}
