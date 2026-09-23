#!/usr/bin/env node

/**
 * Java 原生修图 Agent 浏览器验收脚本。
 *
 * 必需环境变量：
 * - AGENT_ACCEPTANCE_ACCOUNT
 * - AGENT_ACCEPTANCE_PASSWORD
 * - AGENT_ACCEPTANCE_PICTURE_ID
 *
 * 可选环境变量：
 * - FRONTEND_BASE，默认 http://localhost:5173
 * - PLAYWRIGHT_BROWSER，默认 chromium
 * - AGENT_ACCEPTANCE_HEADLESS=false 可显示浏览器
 */

const FRONTEND_BASE = (process.env.FRONTEND_BASE || 'http://localhost:5173').replace(/\/+$/, '')
const ACCOUNT = process.env.AGENT_ACCEPTANCE_ACCOUNT
const PASSWORD = process.env.AGENT_ACCEPTANCE_PASSWORD
const PICTURE_ID = process.env.AGENT_ACCEPTANCE_PICTURE_ID
const BROWSER = process.env.PLAYWRIGHT_BROWSER || 'chromium'
const HEADLESS = process.env.AGENT_ACCEPTANCE_HEADLESS !== 'false'

main().catch((error) => {
  console.error(`浏览器验收失败：${error.message}`)
  process.exitCode = 1
})

async function main() {
  requireEnv('AGENT_ACCEPTANCE_ACCOUNT', ACCOUNT)
  requireEnv('AGENT_ACCEPTANCE_PASSWORD', PASSWORD)
  requireEnv('AGENT_ACCEPTANCE_PICTURE_ID', PICTURE_ID)

  const playwright = await loadPlaywright()
  const browserType = playwright[BROWSER]
  assert(browserType, `不支持的 PLAYWRIGHT_BROWSER：${BROWSER}`)

  const browser = await browserType.launch({ headless: HEADLESS })
  const context = await browser.newContext()
  const page = await context.newPage()
  const checks = []

  try {
    await step(checks, '登录可编辑账号', async () => {
      await page.goto(`${FRONTEND_BASE}/user/login`, { waitUntil: 'networkidle' })
      await page.getByPlaceholder('请输入账号').fill(ACCOUNT)
      await page.getByPlaceholder('请输入密码').fill(PASSWORD)
      await page.getByRole('button', { name: '登录' }).click()
      await page.waitForURL(`${FRONTEND_BASE}/`, { timeout: 15000 })
    })

    await step(checks, '打开 Agent 工作台并恢复会话', async () => {
      await page.goto(`${FRONTEND_BASE}/agent/picture/${encodeURIComponent(PICTURE_ID)}`, {
        waitUntil: 'networkidle',
      })
      await page.getByText(/Agent 精修 · 图片/).waitFor({ timeout: 30000 })
      await page.getByText(/revision\s+\d+/).waitFor({ timeout: 10000 })
      await page.getByText('工具').waitFor({ timeout: 10000 })
      await page.getByText('版本管理').waitFor({ timeout: 10000 })
    })

    await step(checks, '三栏工作台关键控件可见', async () => {
      await page.locator('.side-panel').waitFor({ timeout: 10000 })
      await page.locator('.center-panel').waitFor({ timeout: 10000 })
      await page.locator('.chat-panel-wrap').waitFor({ timeout: 10000 })
      await page.getByRole('button', { name: /透明度|调色|投放尺寸|换背景/ }).first().waitFor()
    })

    await step(checks, '直接工具弹窗可提交', async () => {
      const opacityButton = page.getByRole('button', { name: '透明度' })
      if (await opacityButton.count()) {
        await opacityButton.first().click()
      } else {
        await page.getByRole('button', { name: /工具|调色|投放尺寸|换背景/ }).first().click()
      }
      await page.locator('.ant-modal').waitFor({ timeout: 10000 })
      const okButton = page.locator('.ant-modal .ant-btn-primary').last()
      await okButton.click()
      await page.waitForLoadState('networkidle')
    })

    await step(checks, '自然语言消息入口可用', async () => {
      const textbox = page.getByRole('textbox').last()
      await textbox.fill('把背景换成白色并整体调亮')
      const sendButton = page.getByRole('button', { name: /发送|提交|执行/ }).last()
      await sendButton.click()
      await page.waitForTimeout(1500)
      await page.getByText('把背景换成白色并整体调亮').waitFor({ timeout: 10000 })
    })

    await step(checks, '版本管理入口可打开', async () => {
      await page.getByRole('button', { name: '版本管理' }).click()
      await page.locator('.ant-modal').waitFor({ timeout: 10000 })
      await page.keyboard.press('Escape')
    })

    console.log('\n浏览器验收完成：')
    for (const item of checks) {
      console.log(`- ${item}`)
    }
  } finally {
    await browser.close()
  }
}

async function loadPlaywright() {
  try {
    return await import('playwright')
  } catch (error) {
    throw new Error('未找到 playwright 包。请先在本机安装 Playwright，或用已有 Playwright 环境执行本脚本。')
  }
}

async function step(checks, title, fn) {
  process.stdout.write(`\n[浏览器] ${title} ... `)
  await fn()
  checks.push(title)
  console.log('通过')
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
