/** Run explicitly against local Java/Node/MySQL. Network fault injection is confined to this process. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root,config} from '../src/config.js'
import {db,rows} from '../src/db.js'
import {remoteJob,modelFetch} from '../src/providers.js'
import {stepUpdate,getRun,createRun} from '../src/runs.js'
import {authorize} from '../src/auth.js'
import {project,revision,lease,operate,commitState} from '../src/projects.js'
import {checkSource} from '../src/hypit.js'
import {storeObject} from '../src/assets.js'
const directory=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(directory,'accounts.json'),'utf8'))
const originalFetch=globalThis.fetch,checks:string[]=[]
const api=async(path:string,body?:any,user=0,expected=200)=>{const response=await originalFetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:users[user].cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const result=await response.json() as any;assert.equal(response.status,expected,JSON.stringify(result));return result}
const java=async(path:string,body:any,user=0)=>{const response=await originalFetch('http://127.0.0.1:8090/api'+path,{method:'POST',headers:{Cookie:users[user].cookie,'Content-Type':'application/json'},body:JSON.stringify(body)});const text=await response.text();const value=JSON.parse(text.replace(/("(?:id|userId|spaceId)":)(\d{16,})/g,'$1"$2"'));assert.equal(value.code,0,JSON.stringify(value));return value.data}
const actor=await authorize({headers:{cookie:users[0].cookie}} as any)
const p=await api('/projects',{name:'故障与权限验收'}),sessionId=randomUUID(),held=await api(`/projects/${p.id}/lease`,{action:'acquire',sessionId})
let rev=1
const ctx=()=>({expectedRevision:rev,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()})
const newRun=async()=>createRun(p.id,actor,ctx(),'generation',{})
try{
 const operation={...ctx(),operations:[{type:'insert_clip',trackId:'titles',at:0,length:24,text:'幂等验收'}]}
 const first=await api(`/projects/${p.id}/operations`,operation);rev=first.revision
 const duplicate=await api(`/projects/${p.id}/operations`,operation);assert.deepEqual(duplicate,first);checks.push('重复编辑返回同一版本')
 await api(`/projects/${p.id}/operations`,{...operation,idempotencyKey:randomUUID()},0,409);checks.push('旧版本提交拒绝')
 await api(`/projects/${p.id}`,undefined,1,403);checks.push('公共草稿与素材权限隔离')
 const snap=await revision(p.id)
 await assert.rejects(checkSource(p.id,{...snap.source,'main.svml':'<invalid>'},[]));assert.equal((await revision(p.id)).revision,rev);checks.push('编译失败保留旧版本')
 let attempts=0
 const limited=await newRun()
 globalThis.fetch=async()=>++attempts===1?new Response('',{status:429,headers:{'Retry-After':'0'}}):Response.json({ok:true})
 assert.equal((await modelFetch('/qwen-image/api/generate',{method:'POST'},limited.id,true)).status,200);assert.equal(attempts,2);checks.push('429按回执延迟重试')
 const unknown=await newRun();let submits=0
 await assert.rejects(remoteJob(unknown.id,'unknown','/qwen-image',{},async()=>{submits++;throw Object.assign(new Error('unknown'),{code:'SUBMISSION_UNKNOWN'})}))
 await stepUpdate(unknown.id,'unknown',{status:'unknown'})
 await assert.rejects(remoteJob(unknown.id,'unknown','/qwen-image',{},async()=>{submits++;return Response.json({})}),(e:any)=>e.code==='SUBMISSION_UNKNOWN');assert.equal(submits,1);checks.push('未知提交禁止自动重复生成')
 const lost=await newRun();globalThis.fetch=async()=>new Response('',{status:404})
 await assert.rejects(remoteJob(lost.id,'lost','/ltx-video',{},async()=>Response.json({task_id:'lost-receipt'})),(e:any)=>e.code==='REMOTE_TASK_LOST');assert.equal((await getRun(lost.id)).steps[0].remote_task_id,'lost-receipt');checks.push('远端404仍保留回执')
 const disconnected=await newRun();globalThis.fetch=async()=>{throw Error('offline')}
 await assert.rejects(remoteJob(disconnected.id,'disconnect','/indextts',{},async()=>Response.json({task_id:'known-receipt'})),(e:any)=>e.code==='MODEL_DISCONNECTED');assert.equal((await getRun(disconnected.id)).steps[0].remote_task_id,'known-receipt');checks.push('断连保留回执')
 globalThis.fetch=async()=>Response.json({status:'done',result:{audio:'/indextts/outputs/receipt.wav'}})
 let resumed=0;assert.equal((await remoteJob(disconnected.id,'disconnect','/indextts',{},async()=>{resumed++;return Response.json({})})).audio,'/indextts/outputs/receipt.wav');assert.equal(resumed,0);checks.push('恢复轮询不重复提交')
 const cancel=await newRun();let polls=0;globalThis.fetch=async()=>{polls++;return Response.json({})}
 await db.query('UPDATE video_run SET cancelled=TRUE WHERE id=?',[cancel.id]);await assert.rejects(remoteJob(cancel.id,'cancel','/ltx-video',{},async()=>Response.json({task_id:'cancel-receipt'})),(e:any)=>e.code==='RUN_CANCELLED');assert.equal(polls,0);checks.push('取消后不继续轮询或调用删除推理接口')
 await assert.rejects(commitState(p.id,actor,{...ctx(),idempotencyKey:randomUUID()},[],snap.state,'取消提交',{},cancel.id),(e:any)=>e.code==='RUN_CANCELLED');assert.equal((await revision(p.id)).revision,rev);checks.push('已取消Agent禁止提交候选版本')
 const before=(await rows('SELECT bytes FROM video_quota WHERE scope=?',[actor.scope]))[0]?.bytes||0
 const local=resolve(directory,'cos-failure.bin');await writeFile(local,randomUUID())
 globalThis.fetch=async(input)=>String(input).includes('/storage-ticket')?Response.json({code:0,data:{url:'https://fixture.invalid/object'}}):new Response('',{status:503})
 await assert.rejects(storeObject(actor,p,local,'bin'),(e:any)=>e.code==='COS_UPLOAD_FAILED');assert.equal((await rows('SELECT bytes FROM video_quota WHERE scope=?',[actor.scope]))[0]?.bytes||0,before);checks.push('COS失败回滚配额')
 globalThis.fetch=originalFetch
 await api(`/projects/${p.id}/lease`,{action:'release',sessionId,epoch:held.epoch})
 const secondSession=randomUUID(),secondLease=await api(`/projects/${p.id}/lease`,{action:'acquire',sessionId:secondSession})
 await api(`/projects/${p.id}/operations`,{...ctx(),operations:[{type:'insert_clip',trackId:'titles',at:0,length:24,text:'旧会话'}]},0,409);checks.push('旧租约代次拒绝')
 await db.query('UPDATE video_project SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?',[p.id])
 await api(`/projects/${p.id}/operations`,{...ctx(),sessionId:secondSession,epoch:secondLease.epoch,operations:[{type:'insert_clip',trackId:'titles',at:0,length:24,text:'过期会话'}]},0,409);checks.push('过期租约拒绝')
 // Existing Java role rules remain authoritative for the additive video feature.
 const previous=await readFile(resolve(directory,'integration-report.json'),'utf8').then(JSON.parse).catch(()=>null)
 const spaceId=previous?.spaceId||String(await java('/space/add',{spaceName:'视频协作验收',spaceType:1,spaceLevel:0}))
 if(!previous){await java('/spaceUser/add',{spaceId,userId:users[1].id,spaceRole:'editor'});await java('/spaceUser/add',{spaceId,userId:users[2].id,spaceRole:'viewer'})}
 const team=await api('/projects',{name:'团队交接验收',spaceId})
 const teamSession=randomUUID(),teamLease=await api(`/projects/${team.id}/lease`,{action:'acquire',sessionId:teamSession})
 await api(`/projects/${team.id}`,undefined,2);await api(`/projects/${team.id}/lease`,{action:'acquire',sessionId:randomUUID()},2,403);checks.push('团队只读成员可查看不可编辑')
 await api(`/projects/${team.id}/lease`,{action:'handoff',sessionId:teamSession,epoch:teamLease.epoch,targetId:users[2].id},0,403)
 await api(`/projects/${team.id}/lease`,{action:'handoff',sessionId:teamSession,epoch:teamLease.epoch,targetId:users[1].id})
 const editorSession=randomUUID(),editorLease=await api(`/projects/${team.id}/lease`,{action:'acquire',sessionId:editorSession},1)
 await api(`/projects/${team.id}/operations`,{expectedRevision:1,sessionId:teamSession,epoch:teamLease.epoch,idempotencyKey:randomUUID(),operations:[{type:'insert_clip',trackId:'titles',at:0,length:24,text:'旧编辑者'}]},0,409)
 await api(`/projects/${team.id}/operations`,{expectedRevision:1,sessionId:editorSession,epoch:editorLease.epoch,idempotencyKey:randomUUID(),operations:[{type:'insert_clip',trackId:'titles',at:0,length:24,text:'新编辑者'}]},1);checks.push('团队交接后只有接收会话可提交')
 await api(`/projects/${team.id}/lease`,{action:'release',sessionId:editorSession,epoch:editorLease.epoch},1)
 await writeFile(resolve(directory,'integration-report.json'),JSON.stringify({completedAt:new Date().toISOString(),projectId:p.id,teamProjectId:team.id,spaceId,checks},null,2),'utf8')
 console.log('通过',checks.length,'项持久化、故障与权限验收')
}finally{globalThis.fetch=originalFetch;await db.end()}
