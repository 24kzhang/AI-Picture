/** Actual forest footage + recorded model speech: caption safety, Agent and subsecond export. */
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
const shortFrames=Number(process.env.VIDEO_SHORT_FRAMES||12)
assert.ok(Number.isInteger(shortFrames)&&shortFrames>0&&shortFrames<24)
async function api(path:string,body?:any,expected=200){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const result=await response.json() as any;assert.equal(response.status,expected,JSON.stringify(result));return result}
const created=await api('/projects',{name:'森林溪流 · 字幕独立编辑与半秒导出回归'}),sessionId=randomUUID(),held=await api(`/projects/${created.id}/lease`,{action:'acquire',sessionId})
let revision=1
const context=()=>({expectedRevision:revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()})
const renewal=setInterval(()=>void api(`/projects/${created.id}/lease`,{action:'renew',sessionId,epoch:held.epoch}).catch(()=>{}),20_000)
const report:any={startedAt:new Date().toISOString(),projectId:created.id,scenario:`真实森林溪流镜头、已生成中文口播、关联字幕的独立编辑、真实对话 Agent、${shortFrames}帧成片`,checks:[]}
const persist=()=>writeFile(resolve(dir,process.env.VIDEO_ACCEPTANCE_REPORT||'editor-regression-report.json'),JSON.stringify(report,null,2),'utf8')
async function upload(assetId:string,name:string,file:string){const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(resolve(dataRoot,'assets',assetId,file)))]),name);return api(`/projects/${created.id}/assets`,form)}
async function operate(operations:any[]){const result=await api(`/projects/${created.id}/operations`,{...context(),operations});revision=result.revision;return result.state}
async function restore(){const result=await api(`/projects/${created.id}/restore`,{...context(),revision:report.baselineRevision});revision=result.revision;return result.state}
const media=(state:any)=>state.clips.filter((c:any)=>c.kind!=='text')
async function waitRun(id:string){for(let n=0;n<600;n++){const run=await api(`/runs/${id}`);if(!['queued','running'].includes(run.status)){assert.equal(run.status,'done',run.error);return run}await new Promise(r=>setTimeout(r,1500))}throw Error('任务验收超时')}
try{
  await persist()
  const forest=await upload('debc5b08-83b7-41b9-8fa2-8046e4e884d5','森林溪流真实镜头.mp4','video.mp4')
  const imported=await probe(resolve(dataRoot,'assets',forest.id,'video.mp4')),importedAudio=imported.streams.find((s:any)=>s.codec_type==='audio')
  assert.ok(Math.abs(Number(imported.format.duration)-forest.frames/24)<=1/24)
  assert.ok(Math.abs(Number(importedAudio.duration)-forest.frames/24)<=1/24)
  report.import={assetId:forest.id,frames:forest.frames,formatDuration:imported.format.duration,audioDuration:importedAudio.duration};report.checks.push('normalized video/audio/container durations agree within one frame')
  const voice=await upload('1d44a615-f5ed-4eca-9fbb-5e63da55dc45','中文导览口播.wav','audio.wav')
  let state=await operate([{type:'insert_clip',assetId:forest.id,trackId:'visual',at:0},{type:'insert_clip',assetId:voice.id,trackId:'voice',at:0,length:forest.frames,replaceOriginal:false}])
  state.clips.find((c:any)=>c.trackId==='original').muted=true
  state.clips.push(...subtitlesForClip(state.clips.find((c:any)=>c.kind==='video'),[{start:0,end:2,text:'沿着溪流走进清晨的森林'},{start:2,end:forest.frames/24,text:'清晨的森林，开始这段旅程'}]))
  const value=await project(created.id),actor=await authorize({headers:{cookie}} as any,value,'edit'),assets=await projectAssets(created.id,actor,value)
  const saved=await commitState(created.id,actor,context(),assets,state,'字幕关联回归基线',{scenario:report.scenario});revision=saved.revision;report.baselineRevision=revision
  const baseline=saved.state,caption=baseline.clips.find((c:any)=>c.subtitle),source=baseline.clips.find((c:any)=>c.kind==='video')
  for(const [name,operation] of Object.entries({move:{type:'move_clip',clipId:caption.id,at:12},trim:{type:'trim_clip',clipId:caption.id,at:12,length:24},split:{type:'split_clip',clipId:caption.id,frame:24},copy:{type:'duplicate_clip',clipId:caption.id,at:24},delete:{type:'delete_clip',clipId:caption.id},unlink:{type:'unlink_clip',clipId:caption.id}})){
    state=await operate([operation]);assert.deepEqual(media(state),media(baseline));report.checks.push(`caption ${name} preserves all source media`);await restore()
  }
  const before=revision
  await api(`/projects/${created.id}/operations`,{...context(),operations:[{type:'set_layout',width:1279,height:720}]},400)
  assert.equal((await api(`/projects/${created.id}`)).revision,before);report.checks.push('invalid odd-size output leaves revision unchanged')
  const agent=await api(`/projects/${created.id}/messages`,{...context(),message:`先 inspect_project，然后只删除字幕片段 ${caption.id}。保留所有视频、原声音频、配音及另一条字幕。不要生成素材，不要导出。`})
  const done=await waitRun(agent.id);assert.equal(done.result.applied,true);state=(await api(`/projects/${created.id}`)).state;revision=done.result.revision;assert.deepEqual(media(state),media(baseline));assert.equal(state.clips.filter((c:any)=>c.subtitle).length,1);report.agent={id:agent.id,status:done.status,revision};report.checks.push('real qwen-plus deletes only requested caption')
  await restore()
  const second=baseline.clips.filter((c:any)=>c.subtitle)[1]
  state=await operate([{type:'delete_clip',clipId:second.id},{type:'trim_clip',clipId:source.id,at:0,length:shortFrames},{type:'trim_clip',clipId:baseline.clips.find((c:any)=>c.trackId==='voice').id,at:0,length:shortFrames}]);assert.equal(Math.max(...state.clips.map((c:any)=>c.at+c.length)),shortFrames)
  const run=await api(`/projects/${created.id}/exports`,context()),exported=await waitRun(run.id),file=resolve(dataRoot,'exports',run.id,'final.mp4'),info=await probe(file),video=info.streams.find((s:any)=>s.codec_type==='video'),audio=info.streams.find((s:any)=>s.codec_type==='audio')
  assert.equal(exported.result.frames,shortFrames);assert.equal(Number(video.nb_frames),shortFrames);assert.ok(Math.abs(Number(video.duration)-shortFrames/24)<1e-6);assert.equal(video.codec_name,'h264');assert.equal(audio.codec_name,'aac');assert.ok(Math.abs(Number(audio.duration)-shortFrames/24)<=1/24)
  report.export={runId:run.id,revision:exported.result.revision,file,video,audio};report.checks.push(`actual Hypit export is ${shortFrames} frames with matching AAC duration`)
  await restore();report.browserRevision=revision;report.completedAt=new Date().toISOString();await persist();console.log('实际字幕编辑、对话 Agent、半秒导出回归通过',created.id,report.checks.length)
}catch(error){report.error=(error as Error).message;await persist();throw error}
finally{clearInterval(renewal);await api(`/projects/${created.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{});await db.end()}
