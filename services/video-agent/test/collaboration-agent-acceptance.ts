/** A real Qwen generation in flight while editing rights move to another actual member. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root} from '../src/config.js'
const dir=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8')),{spaceId}=JSON.parse(await readFile(resolve(dir,'integration-report.json'),'utf8'))
async function api(path:string,body?:any,user=0){const r=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:users[user].cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const v=await r.json() as any;assert.ok(r.ok,JSON.stringify(v));return v}
const p=await api('/projects',{name:'真实 Agent 生成中交接 · 咖啡店实景',spaceId}),sessionId=randomUUID(),held=await api(`/projects/${p.id}/lease`,{action:'acquire',sessionId}),report:any={startedAt:new Date().toISOString(),projectId:p.id}
const ctx={expectedRevision:1,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()}
const run=await api(`/projects/${p.id}/messages`,{...ctx,message:'调用 generate_image，生成一张纪实摄影风格的临街咖啡店照片，木桌上放着咖啡与旅行手帐，窗外有真实城市行人，暖色自然光，width=768、height=512、steps=12、seed=42。生成后通过 insert_clip 将该素材插入visual轨的0帧，长度72帧。不要导出。'});report.runId=run.id
const output=resolve(dir,'collaboration-agent-report-20261004.json'),save=()=>writeFile(output,JSON.stringify(report,null,2),'utf8')
await save()
let other:any,otherSession=randomUUID()
try{
 let submitting=false
 for(let n=0;n<180;n++){const fresh=await api(`/runs/${run.id}`);if(fresh.steps.some((s:any)=>s.status==='submitting')){submitting=true;break}assert.ok(['queued','running'].includes(fresh.status),fresh.error||'生成阶段未开始');await new Promise(r=>setTimeout(r,1000))}
 assert.ok(submitting)
 await api(`/projects/${p.id}/lease`,{action:'handoff',sessionId,epoch:held.epoch,targetId:users[1].id})
 other=await api(`/projects/${p.id}/lease`,{action:'acquire',sessionId:otherSession},1)
 const updated=await api(`/projects/${p.id}/operations`,{expectedRevision:1,sessionId:otherSession,epoch:other.epoch,idempotencyKey:randomUUID(),operations:[{type:'insert_clip',trackId:'titles',at:0,length:72,text:'接收成员的新版本必须保留'}]},1)
 report.receivingRevision=updated.revision;await save();console.log('真实模型已提交，编辑权已交接，接收成员已保存新版本')
 let result:any
 for(let n=0;n<600;n++){result=await api(`/runs/${run.id}`);if(!['queued','running'].includes(result.status))break;await new Promise(r=>setTimeout(r,1000))}
 assert.equal(result.status,'done',result.error);assert.equal(result.result.pending,true);assert.equal(result.result.applied,false)
 const latest=await api(`/projects/${p.id}`,undefined,2);assert.equal(latest.revision,updated.revision);assert.equal(latest.state.clips.length,1);assert.equal(latest.state.clips[0].text,'接收成员的新版本必须保留');assert.equal(latest.assets.length,1)
 report.result=result.result;report.assetIds=latest.assets.map((a:any)=>a.id);report.steps=result.steps.map((s:any)=>({step:s.step_id,status:s.status,taskId:s.remote_task_id,buildId:s.build_id}));report.completedAt=new Date().toISOString();await save();console.log('真实 Agent 交接保护通过：生成实景留在素材库，旧候选未覆盖接收成员版本')
}catch(error){report.failure=(error as Error).message;await save();throw error}finally{if(other)await api(`/projects/${p.id}/lease`,{action:'release',sessionId:otherSession,epoch:other.epoch},1).catch(()=>{})}
