import { describe, expect, it } from 'vitest'
import {
  assetKindText,
  batchItemStatusColor,
  batchItemStatusText,
  isTerminalRun,
  mergeRunEventInto,
  runStatusText,
  sessionStatusColor,
  sessionStatusText,
  versionSourceText,
} from './agentStatus'
import type { AgentRunEvent, AgentTurn } from '@/api/agent'

function turn(id: string, status = 'running'): AgentTurn {
  return {
    id,
    agentRunId: `agent-${id}`,
    runType: 'TOOL',
    status,
    steps: [],
  }
}

function event(runId: string, eventId: string, status: string, progress = 50): AgentRunEvent {
  return { eventId, runId, status, progress, timestamp: Date.now() }
}

describe('agentStatus 状态映射', () => {
  it('识别运行终态', () => {
    expect(isTerminalRun('succeeded')).toBe(true)
    expect(isTerminalRun('failed')).toBe(true)
    expect(isTerminalRun('canceled')).toBe(true)
    expect(isTerminalRun('running')).toBe(false)
    expect(isTerminalRun(null)).toBe(false)
  })

  it('运行状态与未知状态文案', () => {
    expect(runStatusText('waiting')).toBe('等待确认计划')
    expect(runStatusText('succeeded')).toBe('已完成')
    expect(runStatusText('weird')).toBe('weird')
    expect(runStatusText(null)).toBe('-')
  })

  it('会话状态文案与颜色', () => {
    expect(sessionStatusText('READY_TO_COMMIT')).toBe('待提交')
    expect(sessionStatusText('CONFLICT')).toBe('版本冲突')
    expect(sessionStatusColor('CONFLICT')).toBe('red')
    expect(sessionStatusColor('COMMITTED')).toBe('green')
    expect(sessionStatusColor(undefined)).toBe('default')
  })

  it('素材类型与版本来源文案', () => {
    expect(assetKindText('marketing')).toBe('营销图')
    expect(assetKindText('unknown')).toBe('unknown')
    expect(versionSourceText('AGENT')).toBe('Agent 精修')
    expect(versionSourceText('RESTORE')).toBe('版本恢复')
  })

  it('批量逐项状态文案与颜色', () => {
    expect(batchItemStatusText('conflict')).toBe('版本冲突')
    expect(batchItemStatusColor('conflict')).toBe('orange')
    expect(batchItemStatusColor('failed')).toBe('red')
    expect(batchItemStatusColor('running')).toBe('processing')
    expect(batchItemStatusColor('pending')).toBe('default')
  })
})

describe('运行事件合并（SSE 去重）', () => {
  it('按 runId 更新轮次', () => {
    const turns = [turn('1'), turn('2')]
    const applied = new Set<string>()
    const [next] = mergeRunEventInto(turns, event('1', 'e1', 'running', 30), applied)
    expect(next[0].progress).toBe(30)
    expect(next[1].progress).toBeUndefined()
  })

  it('同一 eventId 只应用一次', () => {
    const applied = new Set<string>()
    const [first, applied1] = mergeRunEventInto([turn('1')], event('1', 'e1', 'running', 10), applied)
    expect(applied1).toBe(true)
    const [second, applied2] = mergeRunEventInto(first, event('1', 'e1', 'running', 10), applied)
    expect(applied2).toBe(false)
    expect(second[0].progress).toBe(10)
  })

  it('未知 runId 不改变列表', () => {
    const applied = new Set<string>()
    const [next, ok] = mergeRunEventInto([turn('1')], event('999', 'e2', 'succeeded'), applied)
    expect(ok).toBe(false)
    expect(next[0].status).toBe('running')
  })

  it('终态事件写入状态与阶段', () => {
    const applied = new Set<string>()
    const [next] = mergeRunEventInto([turn('1')], event('1', 'e3', 'succeeded', 100), applied)
    expect(next[0].status).toBe('succeeded')
    expect(next[0].progress).toBe(100)
  })
})
