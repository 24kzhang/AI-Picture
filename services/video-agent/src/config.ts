import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes } from 'node:crypto'

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const properties: Record<string, string> = {}
const settingsFile = resolve(root, 'runtime-data/system-settings.properties')
if (existsSync(settingsFile)) {
  for (const line of readFileSync(settingsFile, 'utf8').split(/\r?\n/)) {
    const index = line.indexOf('=')
    if (index > 0 && !line.trimStart().startsWith('#')) properties[line.slice(0, index).trim()] = line.slice(index + 1).trim().replace(/\\([:=\\])/g, '$1')
  }
}
export const dataRoot = resolve(process.env.VIDEO_DATA_ROOT || resolve(root, 'runtime-data/video-agent'))
mkdirSync(dataRoot, { recursive: true })
const keyFile = resolve(root, 'runtime-data/video-service.key')
if (!existsSync(keyFile)) writeFileSync(keyFile, randomBytes(32).toString('hex'), { mode: 0o600 })
export const serviceKey = process.env.VIDEO_SERVICE_KEY || readFileSync(keyFile, 'utf8').trim()
const jdbc = (process.env.DB_URL || properties['database.url'] || 'jdbc:mysql://localhost:3306/cloud_gallery').replace(/^jdbc:/, '')
const dbUrl = new URL(jdbc)
export const config = {
  port: Number(process.env.VIDEO_PORT || 8091),
  host: process.env.VIDEO_HOST || '127.0.0.1',
  javaUrl: process.env.VIDEO_JAVA_URL || 'http://127.0.0.1:8090/api',
  modelUrl: process.env.VIDEO_MODEL_URL || 'http://172.18.20.44:30066',
  maxAssetBytes: Number(process.env.VIDEO_MAX_ASSET_BYTES || 100 * 1024 ** 2),
  maxFrames: Number(process.env.VIDEO_MAX_SECONDS || 120) * 24,
  quotaBytes: Number(process.env.VIDEO_QUOTA_BYTES || 1024 ** 3),
  quotaCount: Number(process.env.VIDEO_QUOTA_COUNT || 50),
  llmUrl: process.env.VIDEO_LLM_URL || 'http://172.18.20.44:30066/qwen-vl/v1',
  agentMaxRounds: Math.max(1,Math.min(64,Number(process.env.VIDEO_AGENT_MAX_ROUNDS || 32))),
  llmKey: process.env.VIDEO_LLM_KEY || '',
  llmModel: process.env.VIDEO_LLM_MODEL || 'Qwen3.8-27B-FP8',
  llmTimeoutMs: Number(process.env.VIDEO_LLM_TIMEOUT_MS || 630_000),
  database: { host: dbUrl.hostname, port: Number(dbUrl.port || 3306), database: dbUrl.pathname.slice(1),
    user: process.env.DB_USERNAME || properties['database.username'] || 'root', password: process.env.DB_PASSWORD || properties['database.password'] || '',
    supportBigNumbers: true, bigNumberStrings: true, charset: 'utf8mb4', timezone: 'Z', connectionLimit: 8 },
}
