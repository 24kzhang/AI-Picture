#!/usr/bin/env node

const base = (process.env.API_BASE || 'http://localhost:8090/api').replace(/\/+$/, '')
const ids = (process.env.AGENT_BATCH_PICTURE_IDS || '2100466108105007106,2100466113125588993,2100465761579999234').split(',')
const cookies = new Map()

main().catch((error) => {
  console.error(`批处理验收失败：${error.message}`)
  process.exitCode = 1
})

async function main() {
  await api('/user/login', { method: 'POST', body: { userAccount: process.env.AGENT_ACCEPTANCE_ACCOUNT || 'alice', userPassword: process.env.AGENT_ACCEPTANCE_PASSWORD || 'gallery123' } })
  const session = await api(`/picture/${ids[0]}/agent-sessions`, { method: 'POST' })
  assert(session.data?.id, '批处理会话创建失败')
  const run = await api(`/agent-sessions/${session.data.id}/tools`, {
    method: 'POST',
    body: {
      tool: 'batch_process',
      params: {
        pictureIds: ids,
        operations: [{ tool: 'adjust_image', params: { brightness: 0.1, contrast: 0, saturation: 0 } }],
      },
    },
  })
  assert(run.data?.id, '批处理 run id 为空')
  const completed = await waitRun(run.data.id)
  assert(completed.status === 'succeeded', `批处理未成功：${completed.errorMessage || completed.status}`)
  const snapshot = await api(`/agent-sessions/${session.data.id}`, {})
  const deliveries = (snapshot.data?.assets || []).filter((asset) => asset.kind === 'delivery')
  assert(deliveries.length >= ids.length, `交付资产数量不足：${deliveries.length}`)
  const zip = await raw(`/agent-sessions/${session.data.id}/export/zip?kind=delivery`, { method: 'POST' })
  assert(zip.ok && (zip.headers.get('content-type') || '').includes('zip'), '批处理 ZIP 导出失败')
  const bytes = new Uint8Array(await zip.arrayBuffer())
  assert(bytes.length > 22, '批处理 ZIP 为空')
  await api(`/agent-sessions/${session.data.id}/lease/release`, { method: 'POST' })
  console.log(`批处理验收通过：${ids.length} 张图片，${deliveries.length} 个交付资产，ZIP ${bytes.length} bytes`)
}

async function waitRun(id) {
  const until = Date.now() + Number(process.env.AGENT_ACCEPTANCE_WAIT_MS || 30000)
  let latest
  while (Date.now() < until) {
    latest = await api(`/agent-runs/${id}`, {})
    if (['succeeded', 'failed', 'cancelled'].includes(latest.data?.status)) return latest.data
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`等待批处理 run 超时：${latest?.data?.status}`)
}

async function api(path, options = {}) {
  const response = await raw(path, options)
  const text = await response.text()
  const data = text ? JSON.parse(text) : null
  if (!response.ok) throw new Error(`${path} HTTP ${response.status}: ${text}`)
  if (data && data.code !== 0 && !options.allowBusinessError) throw new Error(`${path} 业务失败：${data.message || JSON.stringify(data)}`)
  return data
}

async function raw(path, options = {}) {
  const headers = new Headers(options.headers || {})
  if (options.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (cookies.size) headers.set('Cookie', [...cookies].map(([key, value]) => `${key}=${value}`).join('; '))
  const response = await fetch(`${base}${path}`, { ...options, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) })
  const setCookie = response.headers.get('set-cookie')
  if (setCookie) for (const part of setCookie.split(/,(?=\s*[^;,=\s]+=[^;,]*)/g)) {
    const [pair] = part.split(';'); const index = pair.indexOf('=')
    if (index > 0) cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim())
  }
  return response
}

function assert(condition, message) { if (!condition) throw new Error(message) }
