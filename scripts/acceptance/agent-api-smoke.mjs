#!/usr/bin/env node

/**
 * Java 原生修图 Agent 接口冒烟验收。
 *
 * 必需环境变量：
 * - AGENT_ACCEPTANCE_ACCOUNT
 * - AGENT_ACCEPTANCE_PASSWORD
 * - AGENT_ACCEPTANCE_PICTURE_ID
 *
 * 可选环境变量：
 * - API_BASE，默认 http://localhost:8080/api
 * - AGENT_ACCEPTANCE_MESSAGE，默认触发一个多步规划指令
 * - AGENT_ACCEPTANCE_WAIT_MS，默认 30000
 */

const API_BASE = normalizeBase(process.env.API_BASE || 'http://localhost:8080/api')
const ACCOUNT = process.env.AGENT_ACCEPTANCE_ACCOUNT
const PASSWORD = process.env.AGENT_ACCEPTANCE_PASSWORD
const PICTURE_ID = process.env.AGENT_ACCEPTANCE_PICTURE_ID
const MESSAGE = process.env.AGENT_ACCEPTANCE_MESSAGE || '把背景换成白色并整体调亮'
const WAIT_MS = Number(process.env.AGENT_ACCEPTANCE_WAIT_MS || 30000)

const cookieJar = new Map()
const checks = []

main().catch((error) => {
  console.error(`验收失败：${error.message}`)
  process.exitCode = 1
})

async function main() {
  requireEnv('AGENT_ACCEPTANCE_ACCOUNT', ACCOUNT)
  requireEnv('AGENT_ACCEPTANCE_PASSWORD', PASSWORD)
  requireEnv('AGENT_ACCEPTANCE_PICTURE_ID', PICTURE_ID)

  await check('后端健康检查', async () => {
    const res = await api('/user/health', { method: 'GET' })
    assert(res.data === 'OK!', '健康检查未返回 OK')
  })

  await check('登录 Alice/可编辑账号', async () => {
    const res = await api('/user/login', {
      method: 'POST',
      body: {
        userAccount: ACCOUNT,
        userPassword: PASSWORD,
      },
    })
    assert(res.data?.id, '登录响应缺少用户 id')
  })

  const session = await check('创建或复用 Agent 会话', async () => {
    const res = await api(`/picture/${encodeURIComponent(PICTURE_ID)}/agent-sessions`, {
      method: 'POST',
    })
    assert(res.data?.id, '会话 id 为空')
    assert(String(res.data?.status).toLowerCase() === 'active', `会话状态不是 active：${res.data?.status}`)
    assert(Number(res.data?.revision) >= 1, '会话 revision 异常')
    assert(Array.isArray(res.data?.tools) && res.data.tools.length > 0, '会话工具清单为空')
    return res.data
  })

  await check('工具注册表覆盖第一阶段核心工具', async () => {
    const res = await api('/agent/tools', { method: 'GET' })
    const names = new Set((res.data || []).map((tool) => tool.name))
    for (const name of [
      'replace_background',
      'adjust_image',
      'erase_region',
      'prepare_delivery_sizes',
      'batch_process',
      'set_layer_opacity',
    ]) {
      assert(names.has(name), `缺少工具：${name}`)
    }
  })

  const toolRun = await check('同步文档工具直调并生成运行记录', async () => {
    const res = await api(`/agent-sessions/${session.id}/tools`, {
      method: 'POST',
      body: {
        tool: 'set_layer_opacity',
        params: { opacity: 1 },
      },
    })
    assert(res.data?.id, '工具运行所属 run id 为空')
    assert(['running', 'succeeded'].includes(res.data.status), `工具 run 状态异常：${res.data.status}`)
    return res.data
  })

  await check('SSE 首包快照可读', async () => {
    const firstEvent = await readFirstSseEvent(`/agent-runs/${toolRun.id}/events`, WAIT_MS)
    assert(firstEvent.includes(String(toolRun.id)), 'SSE 首包不包含 runId')
  })

  await check('撤销、重做和选区接口可达', async () => {
    await api(`/agent-sessions/${session.id}/undo`, { method: 'POST' })
    await api(`/agent-sessions/${session.id}/redo`, { method: 'POST' })
    const selection = await api(`/agent-sessions/${session.id}/selection`, { method: 'GET' })
    assert(selection.code === 0, '选区查询未返回成功')
  })

  const plannedRun = await check('自然语言指令创建计划或给出明确失败原因', async () => {
    const res = await api(`/agent-sessions/${session.id}/messages`, {
      method: 'POST',
      body: { message: MESSAGE },
      allowBusinessError: true,
    })
    if (res.code !== 0) {
      assert(
        /Key|DashScope|百炼|规划|模型|配置|api/i.test(res.message || ''),
        `自然语言规划失败原因不明确：${res.message || JSON.stringify(res)}`,
      )
      return null
    }
    assert(res.data?.id, '自然语言 run id 为空')
    assert(Array.isArray(res.data.plan), '自然语言 run 缺少 plan 数组')
    return res.data
  })

  if (plannedRun && plannedRun.status === 'waiting') {
    await check('多步计划确认接口可用', async () => {
      const res = await api(`/agent-runs/${plannedRun.id}/confirm`, { method: 'POST' })
      assert(['running', 'succeeded'].includes(res.data?.status), `确认后状态异常：${res.data?.status}`)
    })
  }

  await check('版本列表和 ZIP 导出接口可达', async () => {
    const versions = await api(`/picture/${encodeURIComponent(PICTURE_ID)}/versions`, { method: 'GET' })
    assert(Array.isArray(versions.data), '版本列表不是数组')

    const zip = await raw(`/agent-sessions/${session.id}/export/zip?kind=all`, { method: 'POST' })
    assert(zip.ok, `ZIP 导出 HTTP ${zip.status}`)
    assert((zip.headers.get('content-type') || '').includes('zip'), 'ZIP content-type 异常')
    await zip.arrayBuffer()
  })

  await check('释放 Agent 编辑租约', async () => {
    await api(`/agent-sessions/${session.id}/lease/release`, { method: 'POST' })
  })

  console.log('\n接口冒烟验收完成：')
  for (const item of checks) {
    console.log(`- ${item}`)
  }
}

async function check(title, fn) {
  process.stdout.write(`\n[验收] ${title} ... `)
  const result = await fn()
  checks.push(title)
  console.log('通过')
  return result
}

async function api(path, options = {}) {
  const response = await raw(path, {
    method: options.method || 'GET',
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  const text = await response.text()
  const data = text ? JSON.parse(text) : null
  if (!response.ok) {
    throw new Error(`${path} HTTP ${response.status}: ${text}`)
  }
  if (data && data.code !== 0 && !options.allowBusinessError) {
    throw new Error(`${path} 业务失败：${data.message || JSON.stringify(data)}`)
  }
  return data
}

async function raw(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const cookie = cookieHeader()
  if (cookie) {
    headers.set('Cookie', cookie)
  }
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  })
  rememberCookies(response)
  return response
}

async function readFirstSseEvent(path, timeoutMs) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await raw(path, {
      method: 'GET',
      headers: { Accept: 'text/event-stream' },
      signal: controller.signal,
    })
    assert(response.ok, `SSE HTTP ${response.status}`)
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      if (buffer.includes('\n\n')) {
        await reader.cancel()
        return buffer
      }
    }
    throw new Error('SSE 未返回事件')
  } finally {
    clearTimeout(timer)
  }
}

function rememberCookies(response) {
  const setCookie = response.headers.get('set-cookie')
  if (!setCookie) return
  for (const part of splitSetCookie(setCookie)) {
    const [pair] = part.split(';')
    const index = pair.indexOf('=')
    if (index > 0) {
      cookieJar.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim())
    }
  }
}

function splitSetCookie(value) {
  return value.split(/,(?=\s*[^;,=\s]+=[^;,]*)/g)
}

function cookieHeader() {
  return Array.from(cookieJar.entries())
    .map(([key, value]) => `${key}=${value}`)
    .join('; ')
}

function normalizeBase(base) {
  return base.replace(/\/+$/, '')
}

function requireEnv(name, value) {
  if (!value) {
    throw new Error(`缺少环境变量 ${name}`)
  }
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}
