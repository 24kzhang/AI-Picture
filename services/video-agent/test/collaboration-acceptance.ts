/** Live multi-actor regression using existing moving forest/city footage. No synthetic colour media. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root,dataRoot} from '../src/config.js'
const dir=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8'))
const {spaceId}=JSON.parse(await readFile(resolve(dir,'integration-report.json'),'utf8'))
const report:any={startedAt:new Date().toISOString(),spaceId,checks:[],rounds:[]}
async function request(path:string,body?:any,user=0,method?:string){
 const r=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:method||(body?'POST':'GET'),headers:{Cookie:users[user].cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined})
 return {status:r.status,value:await r.json() as any}
}
async function api(path:string,body?:any,user=0,method?:string){const r=await request(path,body,user,method);assert.equal(r.status,200,JSON.stringify(r.value));return r.value}
const save=()=>writeFile(resolve(dir,'collaboration-report-20261004.json'),JSON.stringify(report,null,2),'utf8')
const check=(text:string)=>{report.checks.push(text);console.log(text)}
let active:{projectId:string;sessionId:string;epoch:number;user:number}|undefined
async function hold(id:string,user=0){const sessionId=randomUUID(),held=await api(`/projects/${id}/lease`,{action:'acquire',sessionId},user);active={projectId:id,sessionId,epoch:held.epoch,user};return held}
async function release(){if(active){await request(`/projects/${active.projectId}/lease`,{action:'release',sessionId:active.sessionId,epoch:active.epoch},active.user);active=undefined}}
try{
 const p=await api('/projects',{name:'多人协同 · 森林与城市实景',spaceId});report.projectId=p.id
 const path=`/projects/${p.id}`,uploaded:any[]=[]
 for(const [id,name] of [['99fe9dc9-aa21-4e02-ad63-957326366db8','协同森林实景.mp4'],['e5ab8998-ac0b-493e-b2b0-2bf41c6b6e7d','协同城市实景.mp4']]){
  const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(resolve(dataRoot,'assets',id!,'video.mp4')))]),name!);uploaded.push(await api(path+'/assets',form))
 }
 report.assetIds=uploaded.map(a=>a.id);check('两段真实移动实景已上传并持久化；三名成员均可查看')
 for(const user of [0,1,2]){const value=await api(path,undefined,user);assert.equal(value.assets.length,2);assert.equal(value.project.revision,value.revision);assert.equal(typeof value.serverTime,'number')}
 let rev=1
 for(let round=0;round<3;round++){
  const sessions=Array.from({length:12},()=>randomUUID())
  const racers=await Promise.all(sessions.map((sessionId,n)=>request(path+'/lease',{action:'acquire',sessionId},n%2)))
  const winner=racers.findIndex(r=>r.status===200);assert.equal(racers.filter(r=>r.status===200).length,1);assert.equal(racers.filter(r=>r.status===409).length,11)
  active={projectId:p.id,sessionId:sessions[winner]!,epoch:racers[winner]!.value.epoch,user:winner%2};await release()
  await hold(p.id);let ctx={expectedRevision:rev,sessionId:active!.sessionId,epoch:active!.epoch,idempotencyKey:randomUUID()}
  assert.equal((await request(path+'/lease',{action:'acquire',sessionId:randomUUID()},2)).status,403)
  const before=await api(path)
  assert.equal((await request(path+'/lease',{action:'handoff',sessionId:ctx.sessionId,epoch:ctx.epoch,targetId:users[2].id})).status,403)
  assert.equal((await api(path)).project.lease_epoch,before.project.lease_epoch)
  assert.equal((await request(path+'/lease',{action:'handoff',sessionId:ctx.sessionId,epoch:ctx.epoch,targetId:'9999999999999'})).status,403)
  await api(path+'/lease',{action:'handoff',sessionId:ctx.sessionId,epoch:ctx.epoch,targetId:users[1].id});active=undefined
  assert.equal((await request(path+'/lease',{action:'acquire',sessionId:randomUUID()})).status,409)
  await hold(p.id,1)
  assert.equal((await request(path+'/operations',{...ctx,operations:[{type:'insert_clip',trackId:'titles',text:'旧会话不得写入',at:0,length:24}]})).status,409)
  ctx={expectedRevision:rev,sessionId:active!.sessionId,epoch:active!.epoch,idempotencyKey:randomUUID()}
  const operations=round===0?[{type:'insert_clip',assetId:uploaded[0].id,trackId:'visual',at:0,length:72,in:12},{type:'insert_clip',assetId:uploaded[1].id,trackId:'visual',at:72,length:72,in:6}]:[{type:'insert_clip',trackId:'titles',text:`第 ${round+1} 轮交接编辑`,at:round*24,length:24}]
  const edits=await Promise.all(Array.from({length:8},()=>request(path+'/operations',{...ctx,idempotencyKey:randomUUID(),operations},1)))
  assert.equal(edits.filter(r=>r.status===200).length,1);assert.equal(edits.filter(r=>r.status===409).length,7);rev++
  const duplicate={...ctx,expectedRevision:rev,idempotencyKey:randomUUID(),operations:[{type:'insert_clip',trackId:'titles',text:`第 ${round+1} 轮幂等标题`,at:96,length:24}]}
  const repeats=await Promise.all(Array.from({length:12},()=>request(path+'/operations',duplicate,1)))
  assert.ok(repeats.every(r=>r.status===200&&r.value.revision===rev+1));rev++
  const snapshot=await api(path);assert.equal(snapshot.project.revision,snapshot.revision);assert.equal(snapshot.revision,rev)
  for(const clip of snapshot.state.clips.filter((c:any)=>c.kind==='video')){const audio=snapshot.state.clips.find((c:any)=>c.kind==='audio'&&c.link===clip.link);assert.ok(audio);assert.equal(audio.in,clip.in);assert.equal(audio.length,clip.length);assert.equal(audio.at,clip.at)}
  report.rounds.push({round:round+1,revision:rev,acquireRequests:12,concurrentEdits:8,identicalRetries:12});await release();await save()
  check(`第 ${round+1} 轮：抢锁唯一成功、查看者拒绝、交接预约、旧会话拒绝、版本竞争及幂等重放通过`)
 }
 const privateProject=await api('/projects',{name:'协同权限隔离草稿'});assert.equal((await request(`/projects/${privateProject.id}`,undefined,1)).status,403)
 await hold(privateProject.id)
 assert.equal((await request(`/projects/${privateProject.id}/lease`,{action:'handoff',sessionId:active!.sessionId,epoch:active!.epoch,targetId:users[1].id})).status,403);await release();check('团队编辑者不能查看私人草稿或接收其编辑权')
 await hold(p.id);const expired={...active!}
 check('开始实际等待 60 秒租约到期（不修改数据库时间）');await save()
 await new Promise(r=>setTimeout(r,61_000))
 assert.equal((await request(path+'/lease',{action:'renew',sessionId:expired.sessionId,epoch:expired.epoch})).status,409)
 active=undefined;await hold(p.id,1)
 assert.ok(active!.epoch>expired.epoch)
 assert.equal((await request(path+'/operations',{expectedRevision:rev,sessionId:expired.sessionId,epoch:expired.epoch,idempotencyKey:randomUUID(),operations:[{type:'insert_clip',trackId:'titles',text:'过期写入',at:0,length:24}]})).status,409)
 check('真实到期后另一个成员可接管；旧 epoch 续租和写入均拒绝')
 const exportContext={expectedRevision:rev,sessionId:active!.sessionId,epoch:active!.epoch,idempotencyKey:randomUUID()}
 const exported=await api(path+'/exports',exportContext,1);report.exportRunId=exported.id;report.exportRevision=rev
 const next=await api(path+'/operations',{...exportContext,idempotencyKey:randomUUID(),operations:[{type:'insert_clip',trackId:'titles',text:'导出后新增，不应进入旧成片',at:120,length:24}]},1);rev=next.revision
 await release()
 for(let n=0;n<300;n++){const run=await api(`/runs/${exported.id}`,undefined,2);if(!['queued','running'].includes(run.status)){assert.equal(run.status,'done',run.error);assert.equal(run.result.revision,report.exportRevision);assert.equal(run.result.frames,144);report.export=run.result;break}await new Promise(r=>setTimeout(r,1000))}
 assert.ok(report.export);check('成员交接后的真实 144 帧实景导出完成；固定旧版本不受后续编辑影响')
 report.revision=rev
 await hold(p.id)
 const full=await api(path+'/hypit/template',{expectedRevision:rev,sessionId:active!.sessionId,epoch:active!.epoch,idempotencyKey:randomUUID(),template:'travel'});report.nativeRevision=full.revision;await release()
 report.completedAt=new Date().toISOString();await save();console.log('多人协同循环验收完成',p.id)
}catch(error){report.failure=(error as Error).message;await save();throw error}finally{await release()}
