import { test } from 'node:test'
import assert from 'node:assert/strict'
import { acceptsPictureSnapshot } from '../src/utils/pictureCollaboration.ts'

const current = { id: '2106724600425287682', revision: 8, collaboration: { epoch: 7, serverTime: 1000 } }
test('late old epoch cannot restore the previous editor', () => {
  assert.equal(acceptsPictureSnapshot(current, { ...current, collaboration: { epoch: 6, serverTime: 1100 } }), false)
})
test('Java long values serialized as strings keep numeric lease ordering', () => {
  const previous = { ...current, collaboration: { epoch: '9', serverTime: '1000' } }
  assert.equal(acceptsPictureSnapshot(previous, { ...current, collaboration: { epoch: '10', serverTime: '1001' } }), true)
  assert.equal(acceptsPictureSnapshot({ ...previous, collaboration: { epoch: '10', serverTime: '1001' } }, previous), false)
})
test('late earlier canvas cannot overwrite newer pixels', () => {
  assert.equal(acceptsPictureSnapshot(current, { ...current, revision: 7, collaboration: { epoch: 7, serverTime: 1100 } }), false)
})
test('late renewal cannot revive an old lease snapshot', () => {
  assert.equal(acceptsPictureSnapshot(current, { ...current, collaboration: { epoch: 7, serverTime: 900 } }), false)
})
test('historical draft cannot replace a newer shared draft', () => {
  assert.equal(acceptsPictureSnapshot(current, { ...current, id: '2106724600425287681', revision: 99 }), false)
})
test('new draft may start at revision one', () => {
  assert.equal(acceptsPictureSnapshot(current, { ...current, id: '2106724600425287683', revision: 1, collaboration: { epoch: 8, serverTime: 1200 } }), true)
})
test('new canvas and fresh same-version run progress are accepted', () => {
  assert.equal(acceptsPictureSnapshot(current, { ...current, revision: 9, collaboration: { epoch: 7, serverTime: 1100 } }), true)
  assert.equal(acceptsPictureSnapshot(current, current), true)
})
