import test from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {emptyState,applyOperations,subtitlesForClip,replaceSubtitles,validateState,type Asset} from '../src/timeline.js'
const asset:Asset={id:randomUUID(),name:'真实场景示例',kind:'video',frames:96,width:768,height:512,hasAudio:true,path:'scene.mp4',size:100}
function setup(source:'video'|'audio'='video'){
  const state=applyOperations(emptyState(),[{type:'insert_clip',assetId:asset.id,trackId:'visual',at:0}],[asset])
  state.clips.push(...subtitlesForClip(state.clips.find(c=>c.kind===source)!,[{start:0,end:2,text:'森林中的溪流'},{start:2,end:4,text:'清晨的旅程'}]))
  return state
}
test('unlinking picture/audio preserves captions following their actual source',()=>{
  for(const kind of ['video','audio'] as const){let state=setup(kind),media=state.clips.find(c=>c.kind===kind)!,other=state.clips.find(c=>c.kind!==kind&&!c.subtitle)!
    state=applyOperations(state,[{type:'unlink_clip',clipId:media.id},{type:'move_clip',clipId:media.id,at:24}],[asset])
    assert.equal(state.clips.find(c=>c.id===other.id)!.at,0)
    assert.deepEqual(state.clips.filter(c=>c.subtitle).map(c=>c.at),[24,72])
  }
})
test('after audio unlink, moving the other media cannot carry captions belonging to the detached audio',()=>{
  let state=setup('audio'),audio=state.clips.find(c=>c.kind==='audio')!,video=state.clips.find(c=>c.kind==='video')!
  state=applyOperations(state,[{type:'unlink_clip',clipId:audio.id},{type:'move_clip',clipId:video.id,at:24}],[asset])
  assert.deepEqual(state.clips.filter(c=>c.subtitle).map(c=>c.at),[0,48])
})
test('copied and split captions keep the corresponding new media as their source',()=>{
  for(const op of ['duplicate_clip','split_clip'] as const){let state=setup(),video=state.clips.find(c=>c.kind==='video')!
    state=applyOperations(state,[op==='duplicate_clip'?{type:op,clipId:video.id,at:120}:{type:op,clipId:video.id,frame:48}],[asset])
    const right=state.clips.find(c=>c.kind==='video'&&c.id!==video.id)!
    const captions=state.clips.filter(c=>c.subtitle&&c.at>=right.at)
    assert.ok(captions.length>0);assert.ok(captions.every(c=>(c as any).sourceClipId===right.id))
    state=applyOperations(state,[{type:'unlink_clip',clipId:right.id},{type:'move_clip',clipId:right.id,at:right.at+24}],[asset])
    assert.deepEqual(state.clips.filter(c=>c.subtitle&&captions.some(p=>p.id===c.id)).map(c=>c.at),captions.map(c=>c.at+24))
  }
})
test('legacy link-only captions retain picture association when picture/audio is detached',()=>{
  let state=setup();for(const c of state.clips)delete (c as any).sourceClipId
  const video=state.clips.find(c=>c.kind==='video')!
  state=applyOperations(state,[{type:'unlink_clip',clipId:video.id},{type:'move_clip',clipId:video.id,at:24}],[asset])
  assert.deepEqual(state.clips.filter(c=>c.subtitle).map(c=>c.at),[24,72])
})
test('retranscription replaces anchored captions even without a group link; empty speech clears them',()=>{
  const state=setup(),video=state.clips.find(c=>c.kind==='video')!
  for(const c of state.clips)delete c.link
  replaceSubtitles(state,video,[{start:1,end:3,text:'新的转写'}])
  assert.deepEqual(state.clips.filter(c=>c.subtitle).map(c=>[c.text,c.at,c.length]),[['新的转写',24,48]])
  replaceSubtitles(state,video,[]);assert.equal(state.clips.filter(c=>c.subtitle).length,0)
})
test('transcribing linked original audio replaces picture captions without duplicating them',()=>{
  const state=setup(),audio=state.clips.find(c=>c.kind==='audio')!
  replaceSubtitles(state,audio,[{start:0,end:2,text:'原声音频的新字幕'}])
  assert.equal(state.clips.filter(c=>c.subtitle).length,1)
  assert.equal(state.clips.find(c=>c.subtitle)!.sourceClipId,audio.id)
  validateState(state,[asset])
})
test('voice replacement removes only stale captions in the interval after picture/audio unlink',()=>{
  const speech:Asset={...asset,id:randomUUID(),kind:'audio',purpose:'speech',width:0,height:0}
  let state=setup(),video=state.clips.find(c=>c.kind==='video')!
  state.clips=state.clips.filter(c=>!c.subtitle);state.clips.push(...subtitlesForClip(video,[{start:0,end:4,text:'待替换的原声字幕'}]))
  state=applyOperations(state,[{type:'unlink_clip',clipId:video.id},{type:'insert_clip',assetId:speech.id,trackId:'voice',at:24,length:48}],[asset,speech])
  assert.deepEqual(state.clips.filter(c=>c.subtitle).sort((a,b)=>a.at-b.at).map(c=>[c.at,c.length,c.sourceClipId]),[[0,24,video.id],[72,24,video.id]])
  assert.equal(state.clips.filter(c=>c.kind==='video').length,1)
  assert.deepEqual(state.clips.filter(c=>c.trackId==='original').sort((a,b)=>a.at-b.at).map(c=>[c.at,c.length,c.in,c.muted]),[[0,24,0,false],[24,48,24,true],[72,24,72,false]])
})
test('voice replacement preserves captions belonging to independently shifted audio',()=>{
  const speech:Asset={...asset,id:randomUUID(),kind:'audio',purpose:'speech',width:0,height:0}
  let state=setup(),video=state.clips.find(c=>c.kind==='video')!,audio=state.clips.find(c=>c.kind==='audio')!
  state=applyOperations(state,[{type:'unlink_clip',clipId:video.id},{type:'move_clip',clipId:audio.id,at:120},{type:'insert_clip',assetId:speech.id,trackId:'voice',at:120,length:48}],[asset,speech])
  assert.deepEqual(state.clips.filter(c=>c.subtitle).map(c=>[c.at,c.length]),[[0,48],[48,48]])
})
