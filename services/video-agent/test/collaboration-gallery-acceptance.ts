import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root} from '../src/config.js'
import {db,rows} from '../src/db.js'
import {authorize} from '../src/auth.js'
import {createRun} from '../src/runs.js'
const dir=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8')),proof=JSON.parse(await readFile(resolve(dir,'collaboration-report-20261004.json'),'utf8'))
async function request(path:string,body?:any,user=0,method?:string){const r=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:method||(body?'POST':'GET'),headers:{Cookie:users[user].cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});return {status:r.status,value:await r.json() as any}}
async function api(path:string,body?:any,user=0){const r=await request(path,body,user);assert.equal(r.status,200,JSON.stringify(r.value));return r.value}
const p=await api(`/projects/${proof.projectId}`),sessionId=randomUUID(),held=await api(`/projects/${proof.projectId}/lease`,{action:'acquire',sessionId}),ctx={expectedRevision:p.revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()},scope=`space-${proof.spaceId}`
let other:any,otherSession=randomUUID(),fixtureRun:any
const report:any={startedAt:new Date().toISOString(),projectId:proof.projectId,checks:[]}
try{
 const actor=await authorize({headers:{cookie:users[0].cookie}} as any,p.project,'edit')
 fixtureRun=await createRun(proof.projectId,actor,{...ctx,idempotencyKey:randomUUID()},'export',{})
 const cancelBody={...ctx,idempotencyKey:randomUUID()},cancelled=await Promise.all(Array.from({length:16},()=>request(`/runs/${fixtureRun.id}/cancel`,cancelBody)))
 assert.ok(cancelled.every(r=>r.status===200&&r.value.cancelRequested));assert.equal(Number((await rows("SELECT COUNT(*) count FROM video_event WHERE run_id=? AND type='cancel_requested'",[fixtureRun.id]))[0]!.count),1)
 report.checks.push('真实 HTTP：16 次重复取消只有一个取消事件，均返回相同成功语义')
 const before=Number((await rows('SELECT video_count FROM video_quota WHERE scope=?',[scope]))[0]!.video_count)
 const body={...ctx,idempotencyKey:randomUUID(),projectId:proof.projectId,runId:proof.exportRunId,revision:proof.exportRevision,name:'多人协同并发保存 · 森林城市真实成片'}
 const saves=await Promise.all(Array.from({length:12},()=>request('/videos',body)))
 assert.ok(saves.every(r=>r.status===200),JSON.stringify(saves.map(r=>({status:r.status,message:r.value.message}))))
 assert.equal(new Set(saves.map(r=>r.value.id)).size,1);const videoId=saves[0]!.value.id;report.videoId=videoId
 assert.equal(Number((await rows('SELECT video_count FROM video_quota WHERE scope=?',[scope]))[0]!.video_count),before+1)
 assert.equal((await request('/videos',{...body,name:'同一键改变名称'})).status,409)
 report.checks.push('真实实景成片：12 次并发保存只有一个视频记录和一次配额增量；不同参数复用键返回 409')
 await api(`/projects/${proof.projectId}/lease`,{action:'handoff',sessionId,epoch:held.epoch,targetId:users[1].id})
 other=await api(`/projects/${proof.projectId}/lease`,{action:'acquire',sessionId:otherSession},1)
 assert.equal((await request(`/videos/${videoId}`,{...ctx,idempotencyKey:randomUUID()},0,'DELETE')).status,409)
 const removed=await request(`/videos/${videoId}`,{...ctx,sessionId:otherSession,epoch:other.epoch,idempotencyKey:randomUUID()},1,'DELETE');assert.equal(removed.status,200);assert.ok(removed.value.retainedProject)
 assert.equal(Number((await rows('SELECT video_count FROM video_quota WHERE scope=?',[scope]))[0]!.video_count),before)
 assert.equal((await request(`/videos/${videoId}`,{...ctx,sessionId:otherSession,epoch:other.epoch,idempotencyKey:randomUUID()},1,'DELETE')).status,404)
 assert.equal(Number((await rows('SELECT video_count FROM video_quota WHERE scope=?',[scope]))[0]!.video_count),before)
 report.checks.push('交接后旧会话删除拒绝；接收成员删除成功，重复删除没有再次扣减配额，项目保留')
 report.completedAt=new Date().toISOString();await writeFile(resolve(dir,'collaboration-gallery-report-20261004.json'),JSON.stringify(report,null,2),'utf8');console.log('协同取消、并发保存及删除配额验收通过')
}finally{if(fixtureRun)await db.query("UPDATE video_run SET status='cancelled' WHERE id=? AND cancelled=TRUE",[fixtureRun.id]);await request(`/projects/${proof.projectId}/lease`,{action:'release',sessionId:other?otherSession:sessionId,epoch:other?other.epoch:held.epoch},other?1:0);await db.end()}
