import test from 'node:test'
import assert from 'node:assert/strict'
import { FairRunQueue } from '../src/fair-run-queue.js'

const wait = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

test('round robin starts another user before one users backlog', async () => {
  const queue = new FairRunQueue({ maxConcurrent: 1, maxConcurrentPerUser: 1, maxQueuedPerUser: 4 })
  const started: string[] = []
  let resolveAll!: () => void
  const allDone = new Promise<void>(resolve => { resolveAll = resolve })
  let finished = 0
  const job = (id: string, userId: string) => ({ id, userId, work: async () => { started.push(id); await wait(1); if (++finished === 4) resolveAll() } })
  assert.equal(queue.enqueue(job('a1', 'alice')), 'accepted')
  assert.equal(queue.enqueue(job('a2', 'alice')), 'accepted')
  assert.equal(queue.enqueue(job('a3', 'alice')), 'accepted')
  assert.equal(queue.enqueue(job('b1', 'bob')), 'accepted')
  await allDone
  assert.deepEqual(started.slice(0, 3), ['a1', 'b1', 'a2'])
})

test('per user queue limit rejects excess work without affecting another user', async () => {
  const queue = new FairRunQueue({ maxConcurrent: 1, maxConcurrentPerUser: 1, maxQueuedPerUser: 2 })
  let release!: () => void
  const held = new Promise<void>(resolve => { release = resolve })
  const first = queue.enqueue({ id: 'a1', userId: 'alice', work: async () => held })
  assert.equal(first, 'accepted')
  assert.equal(queue.enqueue({ id: 'a2', userId: 'alice', work: async () => undefined }), 'accepted')
  assert.equal(queue.enqueue({ id: 'a3', userId: 'alice', work: async () => undefined }), 'accepted')
  assert.equal(queue.enqueue({ id: 'a4', userId: 'alice', work: async () => undefined }), 'rejected')
  assert.equal(queue.enqueue({ id: 'b1', userId: 'bob', work: async () => undefined }), 'accepted')
  release()
  for (let attempt = 0; attempt < 100 && queue.runningCount; attempt++) await wait(1)
  assert.equal(queue.queuedCount('alice'), 0)
})

test('same run is scheduled once even when enqueue is repeated', async () => {
  const queue = new FairRunQueue({ maxConcurrent: 1, maxConcurrentPerUser: 1, maxQueuedPerUser: 2 })
  let executions = 0
  const work = async () => { executions++ }
  const job = { id: 'same', userId: 'alice', work }
  assert.equal(queue.enqueue(job), 'accepted')
  assert.equal(queue.enqueue(job), 'duplicate')
  for (let attempt = 0; attempt < 100 && queue.runningCount; attempt++) await wait(1)
  assert.equal(executions, 1)
})
