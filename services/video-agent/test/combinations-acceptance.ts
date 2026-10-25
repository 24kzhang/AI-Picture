/** Independent picture/audio, split/copy, real ASR retranscription and a mixed-scene export. */
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {root,dataRoot} from '../src/config.js'
import {db} from '../src/db.js'
import {project,commitState} from '../src/projects.js'
import {authorize} from '../src/auth.js'
import {projectAssets,probe} from '../src/assets.js'
import {subtitlesForClip} from '../src/timeline.js'
const dir=resolve(root,'runtime-data/video-acceptance'),accounts=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8')),cookie=accounts[0].cookie
async function api(path:string,body?:any,expected=200){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.equal(response.status,expected,JSON.stringify(value));return value}
const created=await api('/projects',{name:'森林、城市与咖啡 · 解除联动和重复转写验收'}),sessionId=randomUUID(),held=await api(`/projects/${created.id}/lease`,{action:'acquire',sessionId})
let revision=1
const context=()=>({expectedRevision:revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()})
const renewal=setInterval(()=>void api(`/projects/${created.id}/lease`,{action:'renew',sessionId,epoch:held.epoch}).catch(()=>{}),20_000)
const report:any={startedAt:new Date().toISOString(),projectId:created.id,checks:[],assets:{},transcriptions:[]}
const persist=()=>writeFile(resolve(dir,'combinations-report.json'),JSON.stringify(report,null,2),'utf8')
async function upload(path:string,name:string){const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(path))]),name);return api(`/projects/${created.id}/assets`,form)}
async function operate(operations:any[]){const result=await api(`/projects/${created.id}/operations`,{...context(),operations});revision=result.revision;return result.state}
async function waitRun(id:string){for(let n=0;n<600;n++){const run=await api(`/runs/${id}`);if(!['queued','running'].includes(run.status)){assert.equal(run.status,'done',run.error);return run}await new Promise(r=>setTimeout(r,1500))}throw Error('任务验收超时')}
try{
  const forest=report.assets.forest=await upload(resolve(dataRoot,'assets/99fe9dc9-aa21-4e02-ad63-957326366db8/video.mp4'),'森林溪流.mp4')
  const city=report.assets.city=await upload(resolve(dataRoot,'assets/e5ab8998-ac0b-493e-b2b0-2bf41c6b6e7d/video.mp4'),'城市集市.mp4')
  const cafe=report.assets.cafe=await upload(resolve(dir,'formats/café-orientation6.jpg'),'竖向咖啡馆.jpg')
  const voice=report.assets.voice=await upload(resolve(dataRoot,'assets/1d44a615-f5ed-4eca-9fbb-5e63da55dc45/audio.wav'),'中文旅程口播.wav')
  const music=report.assets.music=await upload(resolve(dataRoot,'assets/a4f886d1-d2f0-4224-864f-68011c489eff/audio.wav'),'旅行背景音乐.wav')
  let state=await operate([{type:'insert_clip',assetId:forest.id,trackId:'visual',at:0,in:12,length:72},{type:'insert_clip',assetId:city.id,trackId:'visual',at:132,in:12,length:60},{type:'insert_clip',assetId:cafe.id,trackId:'visual',at:192,length:48},{type:'insert_clip',assetId:voice.id,trackId:'voice',at:0,replaceOriginal:false},{type:'insert_clip',assetId:music.id,trackId:'music',at:0,length:240}])
  const picture=state.clips.find((c:any)=>c.assetId===forest.id&&c.kind==='video'),original=state.clips.find((c:any)=>c.assetId===forest.id&&c.kind==='audio'),speech=state.clips.find((c:any)=>c.trackId==='voice')
  for(const clip of state.clips){if(clip.trackId==='original')clip.muted=true;if(clip.trackId==='music'){clip.volume=.1;clip.fadeIn=24;clip.fadeOut=24}}
  state.clips.push(...subtitlesForClip(picture,[{start:0,end:3,text:'森林溪流 · 画面来源字幕'}]).map(c=>({...c,y:-240,fontSize:30})))
  const value=await project(created.id),actor=await authorize({headers:{cookie}} as any,value,'edit'),assets=await projectAssets(created.id,actor,value)
  const baseline=await commitState(created.id,actor,context(),assets,state,'真实场景与来源字幕基线',{scenario:'不同媒体格式与字幕来源组合'});revision=baseline.revision
  state=await operate([{type:'unlink_clip',clipId:picture.id},{type:'move_clip',clipId:picture.id,at:24}]);assert.equal(state.clips.find((c:any)=>c.id===original.id).at,0);assert.equal(state.clips.find((c:any)=>c.subtitle).at,24);report.checks.push('detached picture moves its source caption without moving original audio')
  state=await operate([{type:'split_clip',clipId:picture.id,frame:60}]);const right=state.clips.find((c:any)=>c.kind==='video'&&c.assetId===forest.id&&c.id!==picture.id);assert.equal(right.in,48);assert.equal(state.clips.find((c:any)=>c.subtitle&&c.at===60).sourceClipId,right.id)
  state=await operate([{type:'duplicate_clip',clipId:right.id,at:96}]);const copy=state.clips.find((c:any)=>c.kind==='video'&&c.at===96);assert.equal(state.clips.find((c:any)=>c.subtitle&&c.at===96).sourceClipId,copy.id);report.checks.push('real-scene split/copy uses correct source-in and new caption source IDs')
  state=await operate([{type:'insert_clip',text:'森林、城市与咖啡',trackId:'titles',at:24,length:108}]);const title=state.clips.find((c:any)=>c.kind==='text'&&!c.subtitle);state=await operate([{type:'edit_subtitles',clipId:title.id,y:-300,fontSize:40}])
  const oldMedia=state.clips.filter((c:any)=>c.kind!=='text'),forestCaptions=state.clips.filter((c:any)=>c.subtitle)
  for(let attempt=0;attempt<2;attempt++){
    const run=await api(`/projects/${created.id}/messages`,{...context(),message:`先 inspect_project，然后只调用 transcribe 转写配音片段 ${speech.id} 一次。用新转写结果替换这个配音已有字幕，不修改森林来源字幕、任何音视频、位置、音量或画幅。不要生成新素材，不要导出。`}),done=await waitRun(run.id)
    assert.equal(done.result.applied,true);revision=done.result.revision;state=(await api(`/projects/${created.id}`)).state;assert.deepEqual(state.clips.filter((c:any)=>c.kind!=='text'),oldMedia);assert.deepEqual(state.clips.filter((c:any)=>c.subtitle&&c.sourceClipId!==speech.id),forestCaptions)
    const captions=state.clips.filter((c:any)=>c.subtitle&&c.sourceClipId===speech.id);assert.ok(captions.length>0);if(attempt)assert.equal(captions.length,report.transcriptions[0].subtitleCount)
    report.transcriptions.push({runId:run.id,revision,subtitleCount:captions.length,text:captions.map((c:any)=>c.text).join('')});await persist()
  }
  report.checks.push('two real qwen-plus / self-hosted Chinese ASR calls replace voice subtitles without duplicates or other media changes')
  const run=await api(`/projects/${created.id}/exports`,context()),done=await waitRun(run.id),output=resolve(dataRoot,'exports',run.id,'final.mp4'),info=await probe(output),video=info.streams.find((s:any)=>s.codec_type==='video'),audio=info.streams.find((s:any)=>s.codec_type==='audio')
  assert.equal(Number(video.nb_frames),240);assert.equal(Number(video.duration),10);assert.ok(Math.abs(Number(audio.duration)-10)<=1/24)
  report.export={runId:run.id,revision:done.result.revision,output,frames:240};report.checks.push('real Hypit 240-frame export contains split/copy footage, EXIF portrait, source captions, Chinese narration and music')
  report.browserRevision=revision;report.completedAt=new Date().toISOString();await persist();console.log('真实场景组合验收通过',created.id,report.checks.length)
}catch(error){report.error=(error as Error).message;await persist();throw error}
finally{clearInterval(renewal);await api(`/projects/${created.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{});await db.end()}
