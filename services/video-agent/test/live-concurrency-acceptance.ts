/** Explicit real-model race: editing during generation must retain the new asset without overwriting a newer version. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root} from '../src/config.js'
const dir=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8')),cookie=users[0].cookie
async function api(path:string,body?:any){const r=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const v=await r.json() as any;if(!r.ok)throw Error(JSON.stringify(v));return v}
const p=await api('/projects',{name:'真实模型并发编辑保护验收'}),sessionId=randomUUID(),held=await api(`/projects/${p.id}/lease`,{action:'acquire',sessionId})
let revision=1
const ctx=()=>({expectedRevision:revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()})
const renewal=setInterval(()=>api(`/projects/${p.id}/lease`,{action:'renew',sessionId,epoch:held.epoch}).catch(()=>{}),20000)
try{
 const run=await api(`/projects/${p.id}/messages`,{...ctx(),message:'调用 generate_image 生成纪实摄影风格的森林溪流旅行封面，width=768、height=512、steps=12、seed=39，然后插入画面轨visual的0帧，长度72帧。不要导出。'})
 let acknowledged=false
 for(let n=0;n<150;n++){const r=await api(`/runs/${run.id}`);if(r.steps.some((s:any)=>s.status==='submitting')){acknowledged=true;break}if(!['queued','running'].includes(r.status))throw Error(r.error||'任务提前结束');await new Promise(r=>setTimeout(r,1000))}
 assert.ok(acknowledged,'未观测到真实模型提交阶段')
 const edited=await api(`/projects/${p.id}/operations`,{...ctx(),operations:[{type:'insert_clip',trackId:'titles',at:0,length:72,text:'手动编辑必须保留'}]});revision=edited.revision
 let result:any
 for(let n=0;n<300;n++){result=await api(`/runs/${run.id}`);if(!['queued','running'].includes(result.status))break;await new Promise(r=>setTimeout(r,2000))}
 assert.equal(result.status,'done',result.error);assert.equal(result.result.pending,true);assert.equal(result.result.applied,false)
 const latest=await api(`/projects/${p.id}`);assert.equal(latest.revision,revision);assert.equal(latest.state.clips.length,1);assert.equal(latest.state.clips[0].text,'手动编辑必须保留');assert.ok(latest.assets.length>0)
 await writeFile(resolve(dir,'live-concurrency-report.json'),JSON.stringify({completedAt:new Date().toISOString(),projectId:p.id,runId:run.id,revision,assetIds:latest.assets.map((a:any)=>a.id),result:result.result},null,2),'utf8')
 console.log('真实模型并发验收通过：手动版本保留，生成素材待插入')
}finally{clearInterval(renewal);await api(`/projects/${p.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{})}
