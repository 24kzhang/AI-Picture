import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root,dataRoot} from '../src/config.js'
import {probe} from '../src/assets.js'
const directory=resolve(root,'runtime-data/video-acceptance'),cookie=JSON.parse(await readFile(resolve(directory,'accounts.json'),'utf8'))[0].cookie,parent=JSON.parse(await readFile(resolve(directory,'hypit-management-report.json'),'utf8')).projectId
async function api(path:string,body?:any,method?:string){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:method||(body?'POST':'GET'),headers:{Cookie:cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.ok(response.ok,JSON.stringify(value));return value}
const original=await api(`/projects/${parent}/sources`),session=randomUUID(),held=await api(`/projects/${parent}/lease`,{action:'acquire',sessionId:session}),clone=await api(`/projects/${parent}/variants`,{expectedRevision:original.revision,sessionId:session,epoch:held.epoch,idempotencyKey:randomUUID(),name:'复合产物与30fps · 真实场景验收'})
await api(`/projects/${parent}/lease`,{action:'release',sessionId:session,epoch:held.epoch})
const id=clone.id,sessionId=randomUUID(),lease=await api(`/projects/${id}/lease`,{action:'acquire',sessionId});let revision=1
const ctx=()=>({expectedRevision:revision,sessionId,epoch:lease.epoch,idempotencyKey:randomUUID()}),report:any={projectId:id,startedAt:new Date().toISOString()},save=()=>writeFile(resolve(directory,'hypit-composite-report.json'),JSON.stringify(report,null,2),'utf8'),renewal=setInterval(()=>api(`/projects/${id}/lease`,{action:'renew',sessionId,epoch:lease.epoch}).catch(()=>{}),20000)
async function wait(run:any){for(let n=0;n<900;n++){const value=await api(`/runs/${run.id}`);if(!['queued','running'].includes(value.status)){assert.equal(value.status,'done',value.error);return value}await new Promise(r=>setTimeout(r,2000))}throw Error('复合输出验收超时')}
try{
  const current=await api(`/projects/${id}/sources`),source={...current.source,files:{...current.source.files,'main.svml':current.source.files['main.svml'].replace('frame-rate="24"','frame-rate="30"'),'build.svrun':current.source.files['build.svrun'].replace(/<build-record\b[^>]+\/>|<satisfy\b[^>]+\/>/g,'').replace('</svrun>','<target output="main.composition"/></svrun>')}}
  revision=(await api(`/projects/${id}/sources`,{...ctx(),source},'PUT')).revision
  report.export=await wait(await api(`/projects/${id}/exports`,ctx()));const result=report.export.result
  assert.equal(result.fps,30);assert.equal(result.frames,96);assert.ok(result.artifacts.some((a:any)=>a.mediaType==='application/zip'));assert.ok(result.artifacts.some((a:any)=>a.kind==='composite'&&a.relativePath==='value.json'))
  const info=await probe(resolve(dataRoot,'exports',report.export.id,'video-0-0.mp4'));assert.equal(info.streams.find((s:any)=>s.codec_type==='video').avg_frame_rate,'30/1')
  const zip=await readFile(resolve(dataRoot,'exports',report.export.id,'bundle-4.zip'));assert.equal(zip.readUInt32LE(0),0x04034b50)
  report.gallery=await api('/videos',{...ctx(),projectId:id,revision,runId:report.export.id,name:'30fps真实实景成片'});const video=await api(`/videos/${report.gallery.id}`);assert.equal(video.metadata.fps,30);assert.equal(video.metadata.frames,96)
  report.completedAt=new Date().toISOString();await save();console.log('30fps真实画面/声音导出、复合数据与资源ZIP归档、图库帧率元数据通过')
}catch(error){report.error=(error as Error).message;await save();throw error}
finally{clearInterval(renewal);await api(`/projects/${id}/lease`,{action:'release',sessionId,epoch:lease.epoch}).catch(()=>{})}
