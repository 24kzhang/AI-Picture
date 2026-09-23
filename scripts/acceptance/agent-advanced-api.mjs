#!/usr/bin/env node

const API_BASE = (process.env.API_BASE || 'http://localhost:8090/api').replace(/\/+$/, '')
const PICTURE_ID = process.env.AGENT_ADVANCED_PICTURE_ID || '2100466108105007106'
const ALICE = { userAccount: process.env.AGENT_ACCEPTANCE_ACCOUNT || 'alice', userPassword: process.env.AGENT_ACCEPTANCE_PASSWORD || 'gallery123' }
const BOB = { userAccount: process.env.AGENT_VIEWER_ACCOUNT || 'bobby', userPassword: process.env.AGENT_VIEWER_PASSWORD || 'gallery123' }
const WAIT_MS = Number(process.env.AGENT_ACCEPTANCE_WAIT_MS || 30000)

const checks = []

main().catch((error) => {
  console.error(`高级接口验收失败：${error.message}`)
  process.exitCode = 1
})

async function main() {
  const alice = client()
  await check('Alice 登录', async () => {
    const res = await alice.api('/user/login', { method: 'POST', body: ALICE })
    assert(res.data?.id, 'Alice 登录响应缺少用户 id')
  })

  const session = await check('创建独立 Agent 会话', async () => {
    const res = await alice.api(`/picture/${PICTURE_ID}/agent-sessions`, { method: 'POST' })
    assert(res.data?.id, '会话 id 为空')
    assert(String(res.data.status).toLowerCase() === 'active', `会话状态异常：${res.data.status}`)
    return res.data
  })
  const baseVersion = Number(session.baseEditVersion)

  const run = await check('创建异步资产任务', async () => {
    const res = await alice.api(`/agent-sessions/${session.id}/messages`, {
      method: 'POST',
      body: { message: '把背景换成白色' },
    })
    assert(res.data?.id, 'run id 为空')
    if (res.data.status === 'waiting') {
      await alice.api(`/agent-runs/${res.data.id}/confirm`, { method: 'POST' })
    }
    return await waitRun(alice, res.data.id)
  })
  const snapshot = await alice.api(`/agent-sessions/${session.id}`, { method: 'GET' })
  const asset = (snapshot.data?.assets || []).find((item) => item.id && item.kind !== 'mask')
  assert(asset?.id, '会话资产墙没有可提交资产')

  await check('选定最终草稿', async () => {
    const res = await alice.api(`/agent-sessions/${session.id}/final-asset`, {
      method: 'POST',
      body: { assetId: asset.id },
    })
    assert(String(res.data?.finalAssetId) === String(asset.id), '最终草稿未写入会话')
  })

  const version = await check('提交正式版本并递增 editVersion', async () => {
    const res = await alice.api(`/agent-sessions/${session.id}/commit`, {
      method: 'POST',
      body: { finalAssetId: asset.id, expectedEditVersion: baseVersion },
    })
    assert(res.data?.id, '提交响应缺少版本 id')
    return res.data
  })

  await check('提交接口幂等返回同一版本', async () => {
    const res = await alice.api(`/agent-sessions/${session.id}/commit`, {
      method: 'POST',
      body: { finalAssetId: asset.id, expectedEditVersion: baseVersion },
    })
    assert(String(res.data?.id) === String(version.id), '重复提交未返回既有版本')
  })

  const versions = await check('读取版本列表并包含提交版本', async () => {
    const res = await alice.api(`/picture/${PICTURE_ID}/versions`, { method: 'GET' })
    assert(Array.isArray(res.data), '版本列表不是数组')
    assert(res.data.some((item) => String(item.id) === String(version.id)), '版本列表缺少新版本')
    return res.data
  })

  const picture = await alice.api(`/picture/get/vo?id=${PICTURE_ID}`, { method: 'GET' })
  assert(picture.code === 0, '提交后图片详情接口不可用')
  const currentVersion = baseVersion + 1

  await check('恢复历史版本创建新版本', async () => {
    const res = await alice.api(`/picture/${PICTURE_ID}/versions/${version.id}/restore`, {
      method: 'POST',
      body: { expectedEditVersion: currentVersion },
    })
    assert(res.data?.id, '恢复响应缺少版本 id')
    assert(String(res.data.id) !== String(version.id), '恢复不应复用原版本 id')
  })

  await check('过期 editVersion 被拒绝', async () => {
    const res = await alice.api(`/picture/${PICTURE_ID}/versions/${version.id}/restore`, {
      method: 'POST',
      body: { expectedEditVersion: baseVersion },
      allowBusinessError: true,
    })
    assert(res.code !== 0, '过期 editVersion 未触发冲突')
    assert(/更新|刷新|冲突|版本/.test(res.message || ''), `冲突提示不明确：${res.message}`)
  })

  const bob = client()
  await check('Bob 登录', async () => {
    const res = await bob.api('/user/login', { method: 'POST', body: BOB })
    assert(res.data?.id, 'Bob 登录响应缺少用户 id')
  })
  await check('Bob viewer 无法创建编辑会话', async () => {
    const res = await bob.api(`/picture/${PICTURE_ID}/agent-sessions`, { method: 'POST', allowBusinessError: true })
    assert(res.code !== 0, 'viewer 账号错误地获得了 Agent 编辑会话')
  })

  await check('会话 ZIP 导出可用', async () => {
    const response = await alice.raw(`/agent-sessions/${session.id}/export/zip?kind=all`, { method: 'POST' })
    assert(response.ok, `ZIP HTTP ${response.status}`)
    assert((response.headers.get('content-type') || '').includes('zip'), 'ZIP content-type 异常')
    const bytes = new Uint8Array(await response.arrayBuffer())
    assert(bytes.length > 22, 'ZIP 内容为空')
  })

  console.log('\n高级接口验收完成：')
  for (const item of checks) console.log(`- ${item}`)
}

async function waitRun(apiClient, runId) {
  const end = Date.now() + WAIT_MS
  let last
  while (Date.now() < end) {
    last = await apiClient.api(`/agent-runs/${runId}`, { method: 'GET' })
    const status = last.data?.status
    if (['succeeded', 'failed', 'cancelled'].includes(status)) {
      assert(status === 'succeeded', `工具任务未成功：${last.data?.errorMessage || status}`)
      return last.data
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`等待 run ${runId} 超时，最后状态：${last?.data?.status}`)
}

function client() {
  const cookies = new Map()
  return {
    api: (path, options = {}) => request(cookies, path, options),
    raw: (path, options = {}) => raw(cookies, path, options),
  }
}

async function request(cookies, path, options = {}) {
  const headers = { ...(options.headers || {}) }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await raw(cookies, path, {
    method: options.method || 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  const text = await response.text()
  const data = text ? JSON.parse(text) : null
  if (!response.ok) throw new Error(`${path} HTTP ${response.status}: ${text}`)
  if (data && data.code !== 0 && !options.allowBusinessError) {
    throw new Error(`${path} 业务失败：${data.message || JSON.stringify(data)}`)
  }
  return data
}

async function raw(cookies, path, options = {}) {
  const headers = new Headers(options.headers || {})
  const cookie = [...cookies.entries()].map(([key, value]) => `${key}=${value}`).join('; ')
  if (cookie) headers.set('Cookie', cookie)
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  const setCookie = response.headers.get('set-cookie')
  if (setCookie) {
    for (const part of setCookie.split(/,(?=\s*[^;,=\s]+=[^;,]*)/g)) {
      const [pair] = part.split(';')
      const index = pair.indexOf('=')
      if (index > 0) cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim())
    }
  }
  return response
}

async function check(title, fn) {
  process.stdout.write(`\n[验收] ${title} ... `)
  const result = await fn()
  checks.push(title)
  console.log('通过')
  return result
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
