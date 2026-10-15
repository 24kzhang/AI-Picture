import test from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {transcribeTimelineClip} from '../src/transcription.js'
import {emptyState,applyOperations,type Asset} from '../src/timeline.js'

function fixture() {
  const asset:Asset={id:randomUUID(),name:'配音',kind:'audio',frames:96,width:0,height:0,hasAudio:true,path:'voice.wav',size:100}
  const state=applyOperations(emptyState(),[{type:'insert_clip',assetId:asset.id,trackId:'voice',at:24}],[asset])
  const snapshot={revision:2,source:{mode:'timeline'},state},context={expectedRevision:2,epoch:1,sessionId:randomUUID(),idempotencyKey:randomUUID()}
  const actor={userId:'1',cookie:'test'} as any
  let committed:any,commitCount=0
  const deps:any={revision:async()=>snapshot,projectAssets:async()=>[asset],
    transcribeClip:async()=>({segments:[{start:0,end:1,text:'真实语音字幕'}]}),cancelled:async()=>false,
    authorize:async()=>actor,commitState:async(...args:any[])=>{committed=args[4];commitCount++;return {revision:3}}}
  return {snapshot,context,actor,deps,id:state.clips[0]!.id,committed:()=>committed,commitCount:()=>commitCount}
}
test('direct transcription works without a planner and preserves source media',async()=>{
  const f=fixture(),before=structuredClone(f.snapshot.state)
  const result=await transcribeTimelineClip({id:'project'},f.actor,f.context,f.id,'run',f.deps)
  assert.equal(result.applied,true);assert.equal(result.subtitleCount,1);assert.equal(result.revision,3)
  assert.deepEqual(f.snapshot.state,before)
  assert.deepEqual(f.committed().clips.filter((c:any)=>c.kind!=='text'),before.clips)
  assert.equal(f.committed().clips.find((c:any)=>c.kind==='text').at,24)
})
test('cancellation and authorization loss cannot commit a transcript',async()=>{
  const cancelled=fixture();cancelled.deps.cancelled=async()=>true
  await assert.rejects(transcribeTimelineClip({id:'project'},cancelled.actor,cancelled.context,cancelled.id,'run',cancelled.deps),/已取消/)
  assert.equal(cancelled.commitCount(),0)
  const revoked=fixture();revoked.deps.authorize=async()=>{throw Error('权限已撤销')}
  await assert.rejects(transcribeTimelineClip({id:'project'},revoked.actor,revoked.context,revoked.id,'run',revoked.deps),/权限已撤销/)
  assert.equal(revoked.commitCount(),0)
})
test('empty speech clears only matching captions; missing clips never call ASR',async()=>{
  const f=fixture();f.snapshot.state.clips.push({...f.snapshot.state.clips[0]!,id:randomUUID(),kind:'text',text:'旧字幕',subtitle:true,sourceClipId:f.id,trackId:'titles'})
  f.deps.transcribeClip=async()=>({segments:[]})
  const result=await transcribeTimelineClip({id:'project'},f.actor,f.context,f.id,'run',f.deps)
  assert.equal(result.subtitleCount,0);assert.equal(f.committed().clips.filter((c:any)=>c.kind==='text').length,0)
  f.deps.transcribeClip=async()=>{throw Error('ASR must not run')}
  await assert.rejects(transcribeTimelineClip({id:'project'},f.actor,f.context,randomUUID(),'other',f.deps),/片段不存在/)
})
