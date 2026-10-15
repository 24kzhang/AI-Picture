import test from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {applyOperations,emptyState,subtitlesForClip,type Asset,type State} from '../src/timeline.js'
const asset:Asset={id:randomUUID(),name:'森林运动镜头',kind:'video',frames:240,width:768,height:512,hasAudio:true,path:'forest.mp4',size:100}
function samples(state:State){return state.clips.filter(c=>c.kind==='audio'||c.kind==='video').flatMap(c=>Array.from({length:c.length},(_,n)=>`${c.kind}:${c.assetId}:${c.at+n}:${c.in+n}:${c.volume}:${c.muted}`)).sort()}
test('256 seeded edit sequences preserve every source frame through splits and reject changes atomically',()=>{
  const firstSeed=Number(process.env.VIDEO_SEQUENCE_FIRST_SEED||1)
  assert.ok(Number.isInteger(firstSeed)&&firstSeed>0)
  for(let seed=firstSeed;seed<firstSeed+256;seed++){
    let random=seed;const pick=(n:number)=>{random=(Math.imul(random,1664525)+1013904223)>>>0;return random%n}
    let state=applyOperations(emptyState(),[{type:'insert_clip',assetId:asset.id,trackId:'visual',at:24,in:12,length:168}],[asset]);state.clips.push(...subtitlesForClip(state.clips.find(c=>c.kind==='video')!,[{start:0,end:2,text:'第一段'},{start:2,end:7,text:'第二段'}]))
    for(let step=0;step<24;step++){
      const media=state.clips.filter(c=>c.kind==='video'),clip=media[pick(media.length)]!,before=structuredClone(state)
      const choice=pick(4)
      if(choice===0&&clip.length>1){const split=clip.at+1+pick(clip.length-1),next=applyOperations(state,[{type:'split_clip',clipId:clip.id,frame:split}],[asset]);assert.deepEqual(samples(next),samples(state),`seed=${seed} step=${step}`);state=next}
      else if(choice===1){state=applyOperations(state,[{type:'move_clip',clipId:clip.id,at:clip.at+pick(49)}],[asset]);const after=state.clips.find(c=>c.id===clip.id)!;assert.equal(after.in,clip.in);assert.equal(after.length,clip.length)}
      else if(choice===2){state=applyOperations(state,[{type:'unlink_clip',clipId:clip.id}],[asset]);assert.deepEqual(samples(state),samples(before))}
      else{assert.throws(()=>applyOperations(state,[{type:'trim_clip',clipId:clip.id,at:clip.at,length:asset.frames+1}],[asset]));assert.deepEqual(state,before)}
      const ids=new Set(state.clips.map(c=>c.id));for(const c of state.clips){assert.ok(c.at>=0&&c.length>0);if(c.sourceClipId)assert.ok(ids.has(c.sourceClipId))}
    }
  }
})
