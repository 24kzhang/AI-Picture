import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root,dataRoot} from '../src/config.js'
import {probe} from '../src/assets.js'
const directory=resolve(root,'runtime-data/video-acceptance'),accounts=JSON.parse(await readFile(resolve(directory,'accounts.json'),'utf8')),cookie=accounts[0].cookie
async function api(path:string,body?:any){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.ok(response.ok,JSON.stringify(value));return value}
const project=await api('/projects',{name:'原生组件与语义字幕 · 真实场景验收'}),sessionId=randomUUID(),held=await api(`/projects/${project.id}/lease`,{action:'acquire',sessionId});let revision=1
const report:any={startedAt:new Date().toISOString(),projectId:project.id,cases:[]},save=()=>writeFile(resolve(directory,'hypit-components-report.json'),JSON.stringify(report,null,2),'utf8'),context=()=>({expectedRevision:revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()})
const renewal=setInterval(()=>api(`/projects/${project.id}/lease`,{action:'renew',sessionId,epoch:held.epoch}).catch(()=>{}),20000)
async function wait(run:any){for(let n=0;n<900;n++){const fresh=await api(`/runs/${run.id}`);if(!['queued','running'].includes(fresh.status)){assert.equal(fresh.status,'done',fresh.error);return fresh}await new Promise(r=>setTimeout(r,2000))}throw Error('原生组件验收超时')}
try{
  for(const [id,name,file] of [['99fe9dc9-aa21-4e02-ad63-957326366db8','森林实景.mp4','video.mp4'],['e5ab8998-ac0b-493e-b2b0-2bf41c6b6e7d','城市实景.mp4','video.mp4'],['1d44a615-f5ed-4eca-9fbb-5e63da55dc45','中文旅行配音.wav','audio.wav']]){const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(resolve(dataRoot,'assets',id!,file!)))]),name!);await api(`/projects/${project.id}/assets`,form)}
  for(const template of (process.env.HYPIT_COMPONENT_CASES||'ranking,cards,comment').split(',').filter(Boolean)){
    const row:any={template};report.cases.push(row);await save()
    const applied=await api(`/projects/${project.id}/hypit/template`,{...context(),template});revision=applied.revision;row.revision=revision
    await api(`/projects/${project.id}/sources/plan`,{source:applied.source})
    const run=await wait(await api(`/projects/${project.id}/exports`,context()));row.runId=run.id;row.result=run.result
    const info=await probe(resolve(dataRoot,'exports',run.id,'video-0-0.mp4'));assert.equal(Number(info.streams.find((s:any)=>s.codec_type==='video').nb_frames),template==='cards'?192:96);await save();console.log(`原生 ${template} 真实素材编排与导出通过`)
  }
  // Prepare actual speech evidence with the existing self-hosted service rather than inventing a transcript.
  const voice=(await api(`/projects/${project.id}/assets`)).find((a:any)=>a.kind==='audio'),base=await api(`/projects/${project.id}/restore`,{...context(),revision:1});revision=base.revision
  const inserted=await api(`/projects/${project.id}/operations`,{...context(),operations:[{type:'insert_clip',assetId:voice.id,trackId:'voice',at:0,length:voice.frames}]});revision=inserted.revision
  const clipId=inserted.state.clips.find((c:any)=>c.kind==='audio').id
  const transcription=await wait(await api(`/projects/${project.id}/messages`,{...context(),message:`只调用 transcribe 对片段 ${clipId} 做真实中文转写，不要导出。`,selection:{clipId}}));revision=transcription.result.revision
  const projected=await api(`/projects/${project.id}`),text=projected.state.clips.filter((c:any)=>c.subtitle).sort((a:any,b:any)=>a.at-b.at).map((c:any)=>c.text).join('')
  assert.ok(text.length>2,'真实 ASR 没有识别到配音')
  const row:any={template:'semantic',voiceId:voice.id,text};report.cases.push(row);await save()
  const semantic=await api(`/projects/${project.id}/hypit/template`,{...context(),template:'semantic',args:{voiceId:voice.id,text}});revision=semantic.revision;row.revision=revision
  const run=await wait(await api(`/projects/${project.id}/exports`,context()));row.runId=run.id;row.result=run.result;assert.equal(run.result.frames,voice.frames);await save()
  report.completedAt=new Date().toISOString();await save();console.log('原生 Script、WhisperX 自建 Provider、中文 Caption Fine 与真实配音导出通过')
}catch(error){report.error=(error as Error).message;await save();throw error}
finally{clearInterval(renewal);await api(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{})}
