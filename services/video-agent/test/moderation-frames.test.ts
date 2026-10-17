import test from 'node:test'
import assert from 'node:assert/strict'
import { reviewFrameIndexes } from '../src/moderation-frames.js'

test('review selects exactly the first, middle and final decoded frames', () => {
  assert.deepEqual(reviewFrameIndexes(197), [0, 98, 196])
  assert.deepEqual(reviewFrameIndexes(240), [0, 119, 239])
  assert.deepEqual(reviewFrameIndexes(86400), [0, 43199, 86399])
})

test('short clips reuse existing frames and invalid frame counts never pass', () => {
  assert.deepEqual(reviewFrameIndexes(1), [0, 0, 0])
  assert.deepEqual(reviewFrameIndexes(2), [0, 0, 1])
  for (const count of [0, -1, NaN, Infinity, 1.5]) assert.throws(() => reviewFrameIndexes(count))
})
