import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {emptyState, applyOperations} from '../src/timeline.js'
import {mergeAnnotations} from '../src/annotation-operations.js'
test('disjoint concurrent subtitles merge; editing or deleting the same object rejects stale work',()=>{
  const base=applyOperations(emptyState(),[{type:'insert_clip',trackId:'titles',at:0,text:'甲'},{type:'insert_clip',trackId:'titles',at:24,text:'乙'}],[])
  const a=base.clips[0]!,b=base.clips[1]!
  const latest=mergeAnnotations(base,base,[{type:'edit_subtitles',clipId:a.id,text:'甲已修改'}],[],2880)
  const merged=mergeAnnotations(base,latest,[{type:'edit_subtitles',clipId:b.id,text:'乙也修改'}],[],2880)
  assert.deepEqual(merged.clips.map(c=>c.text),['甲已修改','乙也修改'])
  assert.throws(()=>mergeAnnotations(base,latest,[{type:'delete_clip',clipId:a.id}],[],2880),/其他成员/)
  const deleted=mergeAnnotations(base,base,[{type:'delete_clip',clipId:a.id}],[],2880)
  assert.throws(()=>mergeAnnotations(base,deleted,[{type:'edit_subtitles',clipId:a.id,text:'复活'}],[],2880),/其他成员/)
})
test('shared mode refuses base video, audio, global layout and track structure; image overlays survive serialization',()=>{
  const asset:any={id:randomUUID(),kind:'image',name:'贴图',frames:72,width:40,height:40,hasAudio:false,path:'x',size:10}
  const base=applyOperations(emptyState(),[{type:'insert_clip',assetId:asset.id,trackId:'visual',at:0}], [asset])
  for(const op of [{type:'delete_clip',clipId:base.clips[0]!.id},{type:'set_layout',width:720,height:1280},{type:'add_track',kind:'visual',name:'禁止'}])assert.throws(()=>mergeAnnotations(base,base,[op],[asset],2880))
  const overlay=mergeAnnotations(base,base,[{type:'insert_clip',assetId:asset.id,trackId:'overlays',at:0,overlay:true}],[asset],2880)
  assert.equal(overlay.clips[1]!.overlay,true)
  assert.equal(mergeAnnotations(overlay,overlay,[{type:'set_layout',clipId:overlay.clips[1]!.id,x:30}],[asset],2880).clips[1]!.x,30)
})
