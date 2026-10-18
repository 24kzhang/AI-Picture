import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdir, mkdtemp, utimes, writeFile, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { cleanupUploadTempFiles, removeUploadTemp } from '../src/upload-temp.js'

test('upload temp cleanup removes only stale upload files', async () => {
  const directory = await mkdtemp(resolve(tmpdir(), 'gallery-upload-'))
  try {
    const stale = resolve(directory, 'upload-00000000-0000-0000-0000-000000000000.bin')
    const fresh = resolve(directory, 'upload-11111111-1111-1111-1111-111111111111.bin')
    const unrelated = resolve(directory, 'upload-not-a-uuid.bin')
    await Promise.all([writeFile(stale, 'old'), writeFile(fresh, 'new'), writeFile(unrelated, 'keep')])
    const now = Date.now()
    await utimes(stale, new Date(now - 10_000), new Date(now - 10_000))
    await utimes(fresh, new Date(now - 100), new Date(now - 100))
    assert.equal(await cleanupUploadTempFiles(directory, now, 1_000), 1)
    await assert.rejects(readFile(stale))
    assert.equal(await readFile(fresh, 'utf8'), 'new')
    assert.equal(await readFile(unrelated, 'utf8'), 'keep')
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('removing a completed or failed upload is idempotent', async () => {
  const directory = await mkdtemp(resolve(tmpdir(), 'gallery-upload-'))
  const path = resolve(directory, 'upload-22222222-2222-2222-2222-222222222222.bin')
  try {
    await mkdir(directory, { recursive: true })
    await writeFile(path, 'temporary')
    await removeUploadTemp(path)
    await removeUploadTemp(path)
    await assert.rejects(readFile(path))
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})
