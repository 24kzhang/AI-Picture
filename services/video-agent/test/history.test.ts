import test from 'node:test'
import assert from 'node:assert/strict'
import { restoreHistory } from '../../../frontend/src/utils/videoHistory.js'
import { fitPreview } from '../../../frontend/src/utils/videoPreview.js'

test('failed undo/redo retains both history stacks for retry', async () => {
  const undo = [1, 2], redo = [4]
  await restoreHistory(undo, redo, 3, async () => false)
  assert.deepEqual(undo, [1, 2]); assert.deepEqual(redo, [4])
  await restoreHistory(undo, redo, 3, async target => { assert.equal(target, 2); return true })
  assert.deepEqual(undo, [1]); assert.deepEqual(redo, [4, 3])
  await restoreHistory(redo, undo, 5, async target => { assert.equal(target, 3); return true })
  assert.deepEqual(undo, [1, 5]); assert.deepEqual(redo, [4])
})
test('empty history never sends a restore', async () => {
  await restoreHistory([], [], 3, async () => { assert.fail('unexpected restore'); return true })
})
test('landscape/portrait preview always keeps the output aspect ratio inside its container', () => {
  for (const [width, height] of [[1280,720],[720,1280]]) {
    for (const [availableWidth, availableHeight] of [[576,156],[320,640],[900,450]]) {
      const size = fitPreview(width!,height!,availableWidth!,availableHeight!)
      assert.ok(size.width <= availableWidth!); assert.ok(size.height <= availableHeight!)
      assert.ok(Math.abs(size.width / size.height - width! / height!) < 1e-10)
    }
  }
})
