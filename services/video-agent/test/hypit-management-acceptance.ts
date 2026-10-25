import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root,dataRoot} from '../src/config.js'
import {probe} from '../src/assets.js'
const directory=resolve(root,'runtime-data/video-acceptance'),cookie=JSON.parse(await readFile(resolve(directory,'accounts.json'),'utf8'))[0].cookie,base=JSON.parse(await readFile(resolve(directory,'hypit-full-report.json'),'utf8'))
async function api(path:string,body?:any,method?:string,status=200){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:method||(body?'POST':'GET'),headers:{Cookie:cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.equal(response.status,status,JSON.stringify(value));return value}
const parent=base.projectId,parentSession=randomUUID(),parentLease=await api(`/projects/${parent}/lease`,{action:'acquire',sessionId:parentSession}),parentCurrent=await api(`/projects/${parent}/sources`),cloneRequest={sessionId:parentSession,epoch:parentLease.epoch,expectedRevision:parentCurrent.revision,idempotencyKey:randomUUID(),name:'完整工程变体 · 图片音频多产物验收'}
const clone=await api(`/projects/${parent}/variants`,cloneRequest)
assert.equal((await api(`/projects/${parent}/variants`,cloneRequest)).id,clone.id)
await api(`/projects/${parent}/lease`,{action:'release',sessionId:parentSession,epoch:parentLease.epoch})
const id=clone.id,sessionId=randomUUID(),held=await api(`/projects/${id}/lease`,{action:'acquire',sessionId});let revision=1
const context=()=>({sessionId,epoch:held.epoch,expectedRevision:revision,idempotencyKey:randomUUID()}),report:any={projectId:id,parent,startedAt:new Date().toISOString(),cloneIdempotent:true},save=()=>writeFile(resolve(directory,'hypit-management-report.json'),JSON.stringify(report,null,2),'utf8'),renewal=setInterval(()=>api(`/projects/${id}/lease`,{action:'renew',sessionId,epoch:held.epoch}).catch(()=>{}),20000)
async function wait(run:any){for(let n=0;n<900;n++){const fresh=await api(`/runs/${run.id}`);if(!['queued','running'].includes(fresh.status)){assert.equal(fresh.status,'done',fresh.error);return fresh}await new Promise(r=>setTimeout(r,2000))}throw Error('验收超时')}
try{
  const current=await api(`/projects/${id}/sources`),assets=await api(`/projects/${id}/assets`),asset=assets.find((a:any)=>current.source.assetIds.includes(a.id)&&a.hasAudio)
  assert.ok(asset);assert.ok(!parentCurrent.source.assetIds.includes(asset.id));assert.equal(current.source.files['章节/配色.svs'],parentCurrent.source.files['章节/配色.svs'])
  const source={...current.source,files:{...current.source.files,'main.svml':current.source.files['main.svml'].replace('</svml>',`<pipeline:ExtractFrame id="cover" source={a-${asset.id}} video="primary-moving" at="frame:12"/><pipeline:ExtractAudio id="sound" source={a-${asset.id}} audio="default"/></svml>`),'build.svrun':current.source.files['build.svrun'].replace('</svrun>','<target output="cover.image"/><target output="sound.audio"/></svrun>')}}
  const ctx=context(),updated=await api(`/projects/${id}/sources`,{...ctx,source},'PUT');revision=updated.revision
  await api(`/projects/${id}/sources`,{...ctx,idempotencyKey:randomUUID(),source},'PUT',409)
  const run=await wait(await api(`/projects/${id}/exports`,context()));report.mixed=run;assert.equal(run.result.artifacts.length,4);assert.ok(run.result.artifacts.some((a:any)=>a.mediaType==='image/png'));assert.ok(run.result.artifacts.some((a:any)=>a.mediaType==='audio/wav'))
  const audio=await probe(resolve(dataRoot,'exports',run.id,'audio-3-0.wav'));assert.equal(audio.streams[0].sample_rate,'48000');assert.equal(audio.streams[0].channels,2)
  // Reuse is an explicit Run choice. It must not infer cached media from names.
  const reuse={...updated.source,files:{...updated.source.files,'build.svrun':updated.source.files['build.svrun'].replace('</svrun>',`<build-record id="approved" build="${run.result.buildId}" output="final.video"/><satisfy output="final.video" candidate="approved"/></svrun>`)}}
  const saved=await api(`/projects/${id}/sources`,{...context(),source:reuse},'PUT');revision=saved.revision
  report.reused=await wait(await api(`/projects/${id}/exports`,context()));assert.equal(report.reused.result.objectKey,run.result.objectKey)
  await api(`/projects/${parent}/hypit/builds/${run.result.buildId}/inspect`,undefined,undefined,400)
  const forbidden={...saved.source,files:{...saved.source.files,'build.svrun':saved.source.files['build.svrun'].replace(run.result.buildId,base.multiOutput.buildId)}}
  // Clone explicitly inherits its parent's immutable Build access.
  await api(`/projects/${id}/sources/check`,{source:forbidden})
  const other=JSON.parse(await readFile(resolve(directory,'hypit-components-report.json'),'utf8')),foreign=other.cases.find((c:any)=>c.result?.buildId)?.result.buildId
  assert.ok(foreign);forbidden.files['build.svrun']=forbidden.files['build.svrun'].replace(base.multiOutput.buildId,foreign)
  await api(`/projects/${id}/sources`,{...context(),source:forbidden},'PUT',400);assert.equal((await api(`/projects/${id}/sources`)).revision,revision)
  report.snapshot=await wait(await api(`/projects/${id}/snapshots`,{...context(),frames:[0,24,72]}));assert.equal(report.snapshot.result.artifacts.length,3)
  report.completedAt=new Date().toISOString();await save();console.log('变体幂等、独立素材、多媒体产物、显式 Build 复用、版本冲突、跨项目隔离与原生精确帧截图通过')
}catch(error){report.error=(error as Error).message;await save();throw error}
finally{clearInterval(renewal);await api(`/projects/${id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{})}
