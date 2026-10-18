import test from 'node:test'
import assert from 'node:assert/strict'
import { isStudioIdle } from '../src/studio-lifecycle.js'

test('idle Studio can be reaped only after TTL with no active users', () => {
  const now = 100_000
  assert.equal(isStudioIdle({ lastUsedAt: 99_000, activeRequests: 0, activeSockets: 0 }, now, 1_000), true)
  assert.equal(isStudioIdle({ lastUsedAt: 99_001, activeRequests: 0, activeSockets: 0 }, now, 1_000), false)
  assert.equal(isStudioIdle({ lastUsedAt: 90_000, activeRequests: 1, activeSockets: 0 }, now, 1_000), false)
  assert.equal(isStudioIdle({ lastUsedAt: 90_000, activeRequests: 0, activeSockets: 1 }, now, 1_000), false)
})
