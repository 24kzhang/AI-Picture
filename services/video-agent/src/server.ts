import Fastify from 'fastify'
import multipart from '@fastify/multipart'
import { randomUUID, createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { writeFile, mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { Readable } from 'node:stream'
import http from 'node:http'
import { z } from 'zod'
import { config, dataRoot } from './config.js'
import { db, rows, transaction, json } from './db.js'
import { migrate } from './migrate.js'
import { authorize, ticket, bridge, type Actor } from './auth.js'
import { ApiError, invariant } from './errors.js'
import { project, revision, createProject, lease, assertWrite, operate, commitState, commitSource, type WriteContext } from './projects.js'
import { catalog, vocabulary, analyzeSource, templates, templateSource } from './hypit-engine.js'
import { writeWorkspace } from './hypit.js'
import { projectAssets, importAsset, importPicture } from './assets.js'
import { duration } from './timeline.js'
import { createRun, getRun, events, enqueue, event, setStatus, recoverRuns, resumeRun, resumedRequest, cancelRun } from './runs.js'
import { agentRun } from './agent.js'
import { generateAsset, modelSchemas, modelHealth, voices } from './providers.js'
import { renderProject } from './render.js'
import { proxyStudio, closeStudios, studioSession } from './studio.js'
import {cloneVariant} from './variants.js'
import {snapshotProject} from './snapshots.js'
import {transcribeTimelineClip} from './transcription.js'

export const app=Fastify({logger:{level:'info',redact:['req.headers.cookie','req.headers.authorization','req.headers.x-video-service-key']},bodyLimit:2*1024**2})
await app.register(multipart,{limits:{fileSize:config.maxAssetBytes,files:1,fields:8}})
const base='/api/video-editor'
const idSchema=z.string().uuid()
const contextSchema=z.object({expectedRevision:z.number().int().positive(),sessionId:z.string().uuid(),epoch:z.number().int().nonnegative(),idempotencyKey:z.string().min(8).max(100)})
const context=(body:any)=>contextSchema.parse(body) as WriteContext
const publicAsset=(asset:any)=>{const {path,audioPath,...rest}=asset;return rest}
function publicPayload(value:any):any {if(value instanceof Date)return value.toISOString();if(Buffer.isBuffer(value))return value;if(Array.isArray(value))return value.map(publicPayload);if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).filter(([key])=>!['path','audioPath'].includes(key)).map(([key,item])=>[key,publicPayload(item)]));return value}
app.addHook('preSerialization',async(_request,_reply,payload)=>publicPayload(payload))
async function projectActor(request:any,value:any,action='view') {const actor=await authorize(request,value,action);if(!value.space_id&&String(value.owner_id)!==actor.userId&&!actor.admin)throw new ApiError(403,'PRIVATE_PROJECT','公共成片的草稿项目仅对作者开放');return actor}
const projectRequest=async(request:any,action='view')=>{const value=await project(idSchema.parse(request.params.id));const actor=await projectActor(request,value,action);return {value,actor}}
const protectedRun=async(request:any,action='view')=>{const run=await getRun(idSchema.parse(request.params.id)),value=await project(run.project_id),actor=await projectActor(request,value,action);return {run,value,actor}}
app.addHook('onRequest',async(request)=>{
  if(['GET','HEAD','OPTIONS'].includes(request.method))return
  const origin=request.headers.origin
  if(origin){const allowed=(process.env.VIDEO_ALLOWED_ORIGINS||'http://localhost:5173,http://127.0.0.1:5173').split(',');const same=new URL(origin).host===request.headers.host;if(!same&&!allowed.includes(origin))throw new ApiError(403,'ORIGIN_REJECTED','拒绝跨站写入')}
})
app.setErrorHandler((error,request,reply)=>{
  const known=error instanceof ApiError
  const invalid=error instanceof z.ZodError
  const status=known?error.statusCode:invalid?400:(error as any).statusCode||500
  reply.code(status).send({code:known?error.code:invalid?'INVALID_REQUEST':'SERVICE_ERROR',message:invalid?error.issues.map(i=>`${i.path.join('.')}: ${i.message}`).join(';'):(error as Error).message,detail:known?error.detail:undefined})
})
app.get(base+'/health',async()=>({ok:true,version:'0.1.0',hypit:'0.2.16'}))
app.get(base+'/capabilities',async request=>{const actor=await authorize(request);return {userId:actor.userId,admin:actor.admin,planner:{model:config.llmModel},limits:{assetBytes:config.maxAssetBytes,projectSeconds:config.maxFrames/24,quotaBytes:config.quotaBytes,quotaCount:config.quotaCount},models:await modelHealth()}})
app.get(base+'/voices',async request=>{await authorize(request);return voices()})
app.get(base+'/projects',async(request:any)=>{
  const spaceId=request.query.spaceId?String(request.query.spaceId):null,actor=await authorize(request,{space_id:spaceId})
  return rows('SELECT id,name,revision,space_id,owner_id,updated_at FROM video_project WHERE '+(spaceId?'space_id=?':'space_id IS NULL AND owner_id=?')+' ORDER BY updated_at DESC LIMIT 100',[spaceId||actor.userId])
})
app.post(base+'/projects',async(request:any)=>{const input=z.object({name:z.string().max(200).default('未命名视频'),spaceId:z.string().regex(/^\d+$/).nullable().optional()}).parse(request.body);const actor=await authorize(request,{space_id:input.spaceId},'upload');return createProject(actor,input.name,input.spaceId)})
app.get(base+'/projects/:id',async(request:any)=>{const {value,actor}=await projectRequest(request),current=await revision(value.id,value.revision);return {project:value,serverTime:Number(value.snapshot_time),revision:current.revision,state:current.state,mode:current.source.mode||'timeline',assets:(await projectAssets(value.id,actor,value)).map(publicAsset),runs:await rows('SELECT id,kind,status,revision,result,error,created_at FROM video_run WHERE project_id=? ORDER BY created_at DESC LIMIT 30',[value.id])}})
app.get(base+'/projects/:id/versions',async(request:any)=>{const {value}=await projectRequest(request);return rows('SELECT revision,author_id,message,created_at FROM video_revision WHERE project_id=? ORDER BY revision DESC LIMIT 200',[value.id])})
app.post(base+'/projects/:id/restore',async(request:any)=>{const {value,actor}=await projectRequest(request,'edit'),ctx=context(request.body),target=await revision(value.id,z.number().int().positive().parse(request.body.revision)),assets=await projectAssets(value.id,actor,value);return commitSource(value.id,actor,ctx,assets,target.source,'恢复历史版本',{revision:target.revision})})
app.get(base+'/hypit/catalog',async request=>{await authorize(request);return catalog()})
app.get(base+'/hypit/templates',async request=>{await authorize(request);return templates})
app.get(base+'/projects/:id/sources',async(request:any)=>{const {value}=await projectRequest(request),current=await revision(value.id,request.query.revision?z.coerce.number().int().positive().parse(request.query.revision):undefined);return {revision:current.revision,source:current.source}})
app.post(base+'/projects/:id/variants',async(request:any)=>{const {value,actor}=await projectRequest(request,'edit');await authorize(request,value,'upload');return cloneVariant(value.id,actor,context(request.body),await projectAssets(value.id,actor,value),z.string().trim().min(1).max(200).parse(request.body.name))})
app.post(base+'/projects/:id/snapshots',async(request:any)=>{const {value,actor}=await projectRequest(request,'edit'),ctx=context(request.body),frames=z.array(z.number().int().nonnegative().max(config.maxFrames-1)).min(1).max(12).parse(request.body.frames),assets=await projectAssets(value.id,actor,value),run=await createRun(value.id,actor,ctx,'snapshot',{frames});enqueue(run.id,()=>snapshotProject(value,assets,actor,run.id,frames));return run})
app.put(base+'/projects/:id/sources',async(request:any)=>{const {value,actor}=await projectRequest(request,'edit'),assets=await projectAssets(value.id,actor,value);return commitSource(value.id,actor,context(request.body),assets,{...request.body.source,mode:'hypit'},'保存 Hypit 源工程',{source:request.body.source})})
app.post(base+'/projects/:id/sources/:analysis',async(request:any)=>{const {value,actor}=await projectRequest(request,'edit'),operation=z.enum(['check','plan','doctor']).parse(request.params.analysis),assets=await projectAssets(value.id,actor,value),source=request.body.source||(await revision(value.id)).source;return analyzeSource(value.id,source,assets,operation)})
app.post(base+'/projects/:id/hypit/template',async(request:any)=>{const {value,actor}=await projectRequest(request,'edit'),ctx=context(request.body),assets=await projectAssets(value.id,actor,value),args=z.object({text:z.string().max(4000).optional(),voiceId:z.string().uuid().optional()}).optional().parse(request.body.args),source=templateSource(z.string().parse(request.body.template),assets,(await revision(value.id,ctx.expectedRevision)).source,args);return commitSource(value.id,actor,ctx,assets,source,'应用 Hypit 工程模板',{template:request.body.template,args})})
app.get(base+'/projects/:id/hypit/vocabulary/:package',async(request:any)=>{const {value,actor}=await projectRequest(request),assets=await projectAssets(value.id,actor,value),workspace=await writeWorkspace(value.id,'vocabulary',(await revision(value.id)).source,assets);return vocabulary(request.params.package,workspace)})
app.get(base+'/projects/:id/hypit/builds/:build/:action',async(request:any)=>{const {value,actor}=await projectRequest(request);invariant((await rows('SELECT s.run_id FROM video_step s JOIN video_run r ON r.id=s.run_id WHERE r.project_id=? AND s.build_id=?',[value.id,request.params.build]))[0],'Build 不属于本项目');const action=z.enum(['status','inspect','logs']).parse(request.params.action),workspace=await writeWorkspace(value.id,'inspect',(await revision(value.id)).source,await projectAssets(value.id,actor,value));const {hypit}=await import('./hypit.js');const output=await hypit([action,request.params.build,...(action==='logs'?['--lines','100']:action==='status'?['--runtime',resolve(workspace,'hypit.runtime.json')]:[])],workspace);try{return JSON.parse(output)}catch{return {text:output}}})
app.post(base+'/projects/:id/operations',async(request:any)=>{const {value,actor}=await projectRequest(request,'edit'),ctx=context(request.body),operations=z.array(z.unknown()).min(1).max(100).parse(request.body.operations);return operate(value.id,actor,ctx,await projectAssets(value.id,actor,value),operations)})
app.post(base+'/projects/:id/lease',async(request:any)=>{
  const {value,actor}=await projectRequest(request,'edit'),input=z.object({action:z.enum(['acquire','renew','release','handoff']),sessionId:z.string().uuid(),epoch:z.number().int().nonnegative().optional(),targetId:z.string().regex(/^\d+$/).optional()}).parse(request.body)
  if(input.action==='handoff')await bridge(actor.cookie,'/handoff-target',{spaceId:value.space_id,ownerId:value.owner_id,targetId:input.targetId})
  return lease(value.id,actor,input.action,input.sessionId,input.epoch,input.targetId)
})
app.post(base+'/projects/:id/assets',async(request:any)=>{
  const {value,actor}=await projectRequest(request,'upload')
  const file=await request.file();invariant(file,'请选择文件')
  const path=resolve(dataRoot,`upload-${randomUUID()}.bin`),bytes=await file.toBuffer();await writeFile(path,bytes)
  return publicAsset(await importAsset(value,actor,path,file.filename))
})
app.post(base+'/projects/:id/assets/import',async(request:any)=>{const {value,actor}=await projectRequest(request,'upload');return publicAsset(await importPicture(value,actor,z.string().regex(/^\d+$/).parse(request.body.pictureId)))})
app.get(base+'/projects/:id/assets',async(request:any)=>{const {value,actor}=await projectRequest(request);return (await projectAssets(value.id,actor,value)).map(publicAsset)})
async function media(request:any,reply:any,scope:any,actor:Actor,key:string) {
  const {url}=await ticket(actor,scope,key,'GET')
  const headers:Record<string,string>={};if(request.headers.range)headers.Range=request.headers.range
  const response=await fetch(url,{headers,signal:AbortSignal.timeout(180_000)})
  for(const header of ['content-type','content-length','content-range','accept-ranges','etag','last-modified'])if(response.headers.has(header))reply.header(header,response.headers.get(header))
  reply.header('Cache-Control','private, no-store')
  if(request.query.download)reply.header('Content-Disposition','attachment; filename="gallery-video.mp4"')
  if(!response.ok&&response.status!==416)throw new ApiError(502,'COS_READ_FAILED',`媒体读取失败 (${response.status})`)
  reply.code(response.status);return reply.send(response.body?Readable.fromWeb(response.body as any):'')
}
app.get(base+'/projects/:id/assets/:assetId/media',async(request:any,reply)=>{const {value,actor}=await projectRequest(request),asset=(await projectAssets(value.id,actor,value)).find(a=>a.id===request.params.assetId);invariant(asset,'素材不存在');const key=request.query.kind==='poster'?asset.posterKey:request.query.kind==='audio'?asset.audioKey||asset.objectKey:request.query.kind==='original'?asset.originalKey:asset.browserObjectKey||asset.objectKey;invariant(key,'素材没有此媒体版本');return media(request,reply,value,actor,key)})
app.get(base+'/videos',async(request:any)=>{
  const spaceId=request.query.spaceId?String(request.query.spaceId):null,actor=await authorize(request,{space_id:spaceId})
  const result=await rows('SELECT * FROM video_record WHERE deleted=FALSE AND '+(spaceId?'space_id=?':'space_id IS NULL AND (review_status=1 OR owner_id=?'+(actor.admin?' OR TRUE':'')+')')+' ORDER BY created_at DESC LIMIT 100',[spaceId||actor.userId])
  return result.map(v=>({...v,metadata:publicAsset(json(v.metadata))}))
})
async function videoRequest(request:any,action='view') {
  const value=(await rows('SELECT * FROM video_record WHERE id=? AND deleted=FALSE',[idSchema.parse(request.params.id)]))[0]
  if(!value)throw new ApiError(404,'VIDEO_NOT_FOUND','视频不存在')
  const actor=await authorize(request,value,action)
  if(!value.space_id&&value.review_status!==1&&String(value.owner_id)!==actor.userId&&!actor.admin)throw new ApiError(403,'VIDEO_NOT_APPROVED','视频未通过审核')
  return {value,actor}
}
app.get(base+'/videos/:id',async(request:any)=>{const {value}=await videoRequest(request);return {...value,metadata:publicAsset(json(value.metadata))}})
app.get(base+'/videos/:id/media',async(request:any,reply)=>{const {value,actor}=await videoRequest(request);const metadata=json(value.metadata);return media(request,reply,value,actor,request.query.kind==='poster'?metadata.posterKey:metadata.browserObjectKey||metadata.objectKey)})
app.get(base+'/runs/:id/artifacts/:index/media',async(request:any,reply)=>{const {run,value,actor}=await protectedRun(request);invariant(run.status==='done','任务尚未完成');const result=run.kind==='agent'?run.result?.export:run.result,index=z.coerce.number().int().nonnegative().parse(request.params.index),artifacts=result?.artifacts||(result?.objectKey?[result]:[]),artifact=artifacts[index];invariant(artifact?.objectKey,'产物不存在');return media(request,reply,value,actor,artifact.objectKey)})
app.post(base+'/videos',async(request:any)=>{
  const input=z.object({projectId:z.string().uuid(),revision:z.number().int().positive(),name:z.string().min(1).max(200),runId:z.string().uuid().optional(),assetId:z.string().uuid().optional(),expectedRevision:z.number(),sessionId:z.string(),epoch:z.number(),idempotencyKey:z.string()}).parse(request.body)
  const value=await project(input.projectId),actor=await authorize(request,value,'upload'),ctx=context(input)
  let metadata:any
  if(input.runId){const run=await getRun(input.runId);invariant(run.project_id===value.id&&run.status==='done','导出任务尚未成功');metadata=run.kind==='agent'?run.result?.export:run.result;invariant(metadata?.revision===input.revision,'导出版本不匹配');invariant(metadata.objectKey&&metadata.width&&metadata.height,'该 Build 没有视频成片，请查看具体产物')}
  else{await revision(value.id,input.revision);const asset=(await projectAssets(value.id,actor,value)).find(a=>a.id===input.assetId);invariant(asset?.kind==='video','请选择视频素材');metadata={...asset,revision:input.revision}}
  const scope=value.space_id?`space-${value.space_id}`:`public-${value.owner_id}`,digest=createHash('sha256').update(JSON.stringify({user:actor.userId,input})).digest('hex')
  return transaction(async connection=>{
    const locked=(await rows('SELECT * FROM video_project WHERE id=? FOR UPDATE',[value.id],connection))[0]!
    const prior=(await rows('SELECT * FROM video_idempotency WHERE project_id=? AND request_key=? FOR UPDATE',[value.id,input.idempotencyKey],connection))[0];if(prior){if(prior.digest!==digest)throw new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键冲突');return json(prior.response)}
    assertWrite(locked,actor,ctx)
    await connection.query('INSERT IGNORE INTO video_quota(scope) VALUES (?)',[scope]);const quota=(await rows('SELECT * FROM video_quota WHERE scope=? FOR UPDATE',[scope],connection))[0]!
    if(quota.video_count>=config.quotaCount)throw new ApiError(413,'VIDEO_COUNT_EXCEEDED','视频数量配额不足')
    const id=randomUUID();await connection.query('INSERT INTO video_record(id,project_id,revision,space_id,owner_id,name,metadata,review_status) VALUES (?,?,?,?,?,?,?,?)',[id,value.id,input.revision,value.space_id,value.owner_id,input.name,JSON.stringify(metadata),value.space_id||actor.admin?1:0])
    await connection.query('UPDATE video_quota SET video_count=video_count+1 WHERE scope=?',[scope]);const result={id,reviewStatus:value.space_id||actor.admin?1:0}
    await connection.query('INSERT INTO video_idempotency(project_id,request_key,digest,response) VALUES (?,?,?,?)',[value.id,input.idempotencyKey,digest,JSON.stringify(result)]);return result
  })
})
app.delete(base+'/videos/:id',async(request:any)=>{const {value,actor}=await videoRequest(request,'delete'),ctx=context(request.body);await transaction(async connection=>{const owner=(await rows('SELECT * FROM video_project WHERE id=? FOR UPDATE',[value.project_id],connection))[0]!;assertWrite(owner,actor,ctx);const result=(await rows('SELECT deleted FROM video_record WHERE id=? FOR UPDATE',[value.id],connection))[0]!;if(!result.deleted){await connection.query('UPDATE video_record SET deleted=TRUE WHERE id=?',[value.id]);await connection.query('UPDATE video_quota SET video_count=GREATEST(0,video_count-1) WHERE scope=?',[value.space_id?`space-${value.space_id}`:`public-${value.owner_id}`])}});return {deleted:true,retainedProject:true}})
app.post(base+'/videos/:id/review',async(request:any)=>{const {value,actor}=await videoRequest(request);if(!actor.admin)throw new ApiError(403,'ADMIN_REQUIRED','仅管理员可以审核视频');const status=z.union([z.literal(1),z.literal(2)]).parse(request.body.reviewStatus);await db.query('UPDATE video_record SET review_status=? WHERE id=?',[status,value.id]);return {reviewStatus:status}})
app.post(base+'/projects/:id/messages',async(request:any)=>{
  const {value,actor}=await projectRequest(request,'edit'),ctx=context(request.body)
  const message=z.string().min(1).max(4000).parse(request.body.message),selection=request.body.selection||{}
  const run=await createRun(value.id,actor,ctx,'agent',{message,selection});enqueue(run.id,()=>agentRun(value,actor,ctx,message,selection,run.id));return run
})
app.post(base+'/projects/:id/exports',async(request:any)=>{const {value,actor}=await projectRequest(request,'edit'),ctx=context(request.body);const snapshot=await revision(value.id,ctx.expectedRevision),assets=await projectAssets(value.id,actor,value);const run=await createRun(value.id,actor,ctx,'export',{});enqueue(run.id,()=>renderProject(value,snapshot.revision,snapshot.source,assets,actor,run.id));return run})
app.post(base+'/projects/:id/generate',async(request:any)=>{const {value,actor}=await projectRequest(request,'edit'),ctx=context(request.body);const tool=z.enum(['generate_image','generate_video','synthesize_speech']).parse(request.body.tool),args=modelSchemas[tool].parse(request.body.args);const run=await createRun(value.id,actor,ctx,'generation',{tool,args});enqueue(run.id,async()=>publicAsset(await generateAsset(tool,args,value,actor,await projectAssets(value.id,actor,value),run.id,'generation')));return run})
app.post(base+'/projects/:id/transcribe',async(request:any)=>{
  const {value,actor}=await projectRequest(request,'edit'),ctx=context(request.body)
  const clipId=idSchema.parse(request.body.clipId)
  const run=await createRun(value.id,actor,ctx,'transcription',{clipId})
  enqueue(run.id,()=>transcribeTimelineClip(value,actor,ctx,clipId,run.id))
  return run
})
app.get(base+'/runs/:id',async(request:any)=>{const {run}=await protectedRun(request);return run})
app.get(base+'/runs/:id/media',async(request:any,reply)=>{const {run,value,actor}=await protectedRun(request);const output=run.kind==='agent'?run.result?.export:run.result;invariant(run.status==='done'&&output?.objectKey,'成片尚不可用');return media(request,reply,value,actor,output.objectKey)})
app.get(base+'/runs/:id/events',async(request:any,reply)=>{
  const {run,value}=await protectedRun(request);let cursor=Number(request.headers['last-event-id']||request.query.after||0);invariant(Number.isSafeInteger(cursor)&&cursor>=0,'无效事件游标')
  reply.hijack();reply.raw.writeHead(200,{'Content-Type':'text/event-stream; charset=utf-8','Cache-Control':'no-cache','Connection':'keep-alive','X-Accel-Buffering':'no'})
  let closed=false,replaying=true,delivery=Promise.resolve(),heartbeat:ReturnType<typeof setInterval>|undefined
  const buffered:any[]=[]
  const stop=()=>{if(closed)return;closed=true;if(heartbeat)clearInterval(heartbeat);events.off(run.id,listener)}
  // Recheck the Java identity/space permissions before every delivery. Serialize
  // authorization with replay/live events so delayed checks cannot reorder them.
  const deliver=(entry?:any)=>{delivery=delivery.then(async()=>{
    if(closed||entry&&Number(entry.id)<=cursor)return
    await projectActor(request,value)
    if(closed)return
    if(entry){cursor=Number(entry.id);reply.raw.write(`id: ${entry.id}\nevent: ${entry.type}\ndata: ${JSON.stringify(publicPayload(entry.data))}\n\n`)}
    else reply.raw.write(': heartbeat\n\n')
  }).catch(()=>{stop();reply.raw.end()});return delivery}
  const listener=(entry:any)=>{if(replaying)buffered.push(entry);else void deliver(entry)}
  reply.raw.on('close',stop);events.on(run.id,listener)
  try{
    const saved=await rows('SELECT * FROM video_event WHERE run_id=? AND id>? ORDER BY id',[run.id,cursor]);for(const entry of saved)await deliver({...entry,data:json(entry.data)})
    replaying=false;for(const entry of buffered.sort((a,b)=>Number(a.id)-Number(b.id)))void deliver(entry)
    if(!closed)heartbeat=setInterval(()=>void deliver(),15_000)
  }catch{stop();reply.raw.end()}
})
  app.post(base+'/runs/:id/cancel',async(request:any)=>{
    const {run,actor}=await protectedRun(request,'edit')
    if(await cancelRun(run.id,actor,context(request.body))){const state=await getRun(run.id);await event(run.id,state.status==='cancelled'?'cancelled':'cancel_requested',{remoteInferenceStopped:false})}
    return {cancelRequested:true,remoteInferenceStopped:false}
  })
app.post(base+'/runs/:id/retry',async(request:any)=>{
  const {run,value,actor}=await protectedRun(request,'edit'),ctx=context(request.body);assertWrite(value,actor,ctx)
  const fresh=request.body.regenerate===true
    if(!fresh&&await resumedRequest(run.id,actor,ctx))return getRun(run.id)
    if(['queued','running','done'].includes(run.status))throw new ApiError(409,'RUN_NOT_RETRYABLE','任务正在执行或已完成，请刷新任务状态')
    if((['unknown','lost','cancelled'].includes(run.status)||run.cancelled)&&!fresh)throw new ApiError(409,'EXPLICIT_REGENERATION_REQUIRED','结果未知、任务丢失或已取消，需要明确选择重新生成')
  if(!fresh&&['awaiting_authorization','error'].includes(run.status)) {
    invariant(String(run.user_id)===actor.userId,'只有发起者可以恢复任务')
    const requestContext={...ctx,expectedRevision:run.revision,idempotencyKey:run.request.context.idempotencyKey} as WriteContext
    if(run.kind==='agent'&&run.revision!==value.revision)throw new ApiError(409,'REVISION_CONFLICT','项目已更新，请选择重新规划以使用最新版本')
      if(!await resumeRun(run.id,actor,ctx))return getRun(run.id)
    enqueue(run.id,()=>run.kind==='generation'?projectAssets(value.id,actor,value).then(assets=>generateAsset(run.request.tool,run.request.args,value,actor,assets,run.id,'generation')):run.kind==='export'?revision(value.id,run.revision).then(async snapshot=>renderProject(value,snapshot.revision,snapshot.source,await projectAssets(value.id,actor,value),actor,run.id)):run.kind==='transcription'?transcribeTimelineClip(value,actor,requestContext,run.request.clipId,run.id):run.kind==='snapshot'?projectAssets(value.id,actor,value).then(assets=>snapshotProject(value,assets,actor,run.id,run.request.frames)):agentRun(value,actor,requestContext,run.request.message,run.request.selection,run.id));return getRun(run.id)
  }
  const reusable=run.steps.map((s:any)=>s.result?.assetId).filter(Boolean)
  const retryMessage=run.kind==='agent'&&reusable.length?`${run.request.message}\n恢复说明：此前已生成素材 ${reusable.join('、')}，先查看素材库并复用这些产物，避免重复生成。`:run.request.message
  const next=await createRun(value.id,actor,ctx,run.kind,{...run.request,message:retryMessage,context:ctx,retryOf:run.id})
  enqueue(next.id,()=>run.kind==='generation'?projectAssets(value.id,actor,value).then(assets=>generateAsset(run.request.tool,run.request.args,value,actor,assets,next.id,'generation')):run.kind==='export'?revision(value.id,ctx.expectedRevision).then(async snapshot=>renderProject(value,snapshot.revision,snapshot.source,await projectAssets(value.id,actor,value),actor,next.id)):run.kind==='transcription'?transcribeTimelineClip(value,actor,ctx,run.request.clipId,next.id):run.kind==='snapshot'?projectAssets(value.id,actor,value).then(assets=>snapshotProject(value,assets,actor,next.id,run.request.frames)):agentRun(value,actor,ctx,retryMessage,run.request.selection,next.id));return next
})
app.all(base+'/projects/:id/studio/*',async(request:any,reply)=>{const {value,actor}=await projectRequest(request,['GET','HEAD'].includes(request.method)?'view':'edit');return proxyStudio(request,reply,value,actor,await projectAssets(value.id,actor,value))})
app.server.on('upgrade',async(request,socket,head)=>{
  try{const match=request.url?.match(/^\/api\/video-editor\/projects\/([0-9a-f-]{36})\/studio\//);if(!match){socket.destroy();return}const value=await project(match[1]!);await projectActor({headers:request.headers} as any,value)
    const session=studioSession(value.id);if(!session){socket.destroy();return}
    const upstream=http.request({host:'127.0.0.1',port:session.port,path:request.url,headers:{...request.headers,host:`127.0.0.1:${session.port}`}})
    upstream.on('upgrade',(response,remote,remoteHead)=>{
      let closed=false,checking=false
      const stop=()=>{if(closed)return;closed=true;clearInterval(authorization);socket.destroy();remote.destroy()}
      const authorization=setInterval(async()=>{if(closed||checking)return;checking=true;try{await projectActor({headers:request.headers} as any,value)}catch{stop()}finally{checking=false}},15_000)
      socket.once('close',stop);remote.once('close',stop);socket.on('error',stop);remote.on('error',stop)
      socket.write(`HTTP/1.1 101 Switching Protocols\r\n${Object.entries(response.headers).map(([k,v])=>`${k}: ${v}`).join('\r\n')}\r\n\r\n`);if(remoteHead.length)socket.write(remoteHead);if(head.length)remote.write(head);socket.pipe(remote).pipe(socket)
    });upstream.on('error',()=>socket.destroy());upstream.end()
  }catch{socket.destroy()}
})
await migrate();await recoverRuns()
await app.listen({port:config.port,host:config.host})
const close=async()=>{closeStudios();await app.close();await db.end();process.exit(0)};process.on('SIGINT',close);process.on('SIGTERM',close)


