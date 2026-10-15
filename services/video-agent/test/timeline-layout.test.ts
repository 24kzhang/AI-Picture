import test from 'node:test'
import assert from 'node:assert/strict'
import {timelineRows} from '../../../frontend/src/utils/videoTimeline.js'
test('overlapping titles/subtitles get distinct clickable rows without changing source track/order',()=>{
  const clips=[{id:'source1',trackId:'titles',at:24,length:36},{id:'source2',trackId:'titles',at:60,length:36},{id:'title',trackId:'titles',at:24,length:108},{id:'asr',trackId:'titles',at:64,length:56},{id:'video',trackId:'visual',at:24,length:72}],before=structuredClone(clips),result=timelineRows(clips)
  assert.equal(result.slots.get('source1'),result.slots.get('source2'));assert.equal(result.heights.get('titles'),124);assert.equal(result.heights.get('visual'),44);assert.deepEqual(clips,before)
  for(const a of clips)for(const b of clips)if(a.id!==b.id&&a.trackId===b.trackId&&a.at<b.at+b.length&&b.at<a.at+a.length)assert.notEqual(result.slots.get(a.id),result.slots.get(b.id))
})
test('layout keeps touching half-open clips in one row and isolates separate tracks',()=>{
  const result=timelineRows([{id:'a',trackId:'visual',at:0,length:24},{id:'b',trackId:'visual',at:24,length:24},{id:'c',trackId:'voice',at:0,length:48}]);assert.deepEqual([...result.slots.values()],[0,0,0]);assert.equal(result.heights.get('visual'),44);assert.equal(timelineRows([]).slots.size,0)
})
