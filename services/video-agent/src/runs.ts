import { isDeepStrictEqual } from 'node:util'
import { EventEmitter } from 'node:events'
import { randomUUID } from 'node:crypto'
import { rows, db, json, transaction } from './db.js'
import { assertWrite } from './projects.js'
import { createHash } from 'node:crypto'
import { ApiError } from './errors.js'
import type { Actor } from './auth.js'
import type { WriteContext } from './projects.js'
import { FairRunQueue } from './fair-run-queue.js'
export const events = new EventEmitter();events.setMaxListeners(100)
const workerId=randomUUID(), leaseMinutes=30
export async function event(runId: string,type: string,data: unknown) {
  const [result]=await db.query<any>('INSERT INTO video_event(run_id,type,data) VALUES (?,?,?)',[runId,type,JSON.stringify(data)])
  events.emit(runId,{id:String(result.insertId),type,data})
}
export async function getRun(id: string): Promise<Record<string,any>> {
  const run=(await rows('SELECT * FROM video_run WHERE id=?',[id]))[0]
  if(!run)throw new ApiError(404,'RUN_NOT_FOUND','任务不存在')
  return {...run,request:json(run.request),result:run.result?json(run.result):null,steps:(await rows('SELECT * FROM video_step WHERE run_id=?',[id])).map(s=>({...s,request:json(s.request),result:s.result?json(s.result):null}))}
}
export async function createRun(projectId:string,actor:Actor,context:WriteContext,kind:string,request:unknown) {
  const id=randomUUID()
  const digest=createHash('sha256').update(JSON.stringify({userId:actor.userId,context,kind,request})).digest('hex')
  const accepted=await transaction(async connection=>{
    const record=(await rows('SELECT * FROM video_project WHERE id=? FOR UPDATE',[projectId],connection))[0]!
    const prior=(await rows('SELECT * FROM video_idempotency WHERE project_id=? AND request_key=?',[projectId,context.idempotencyKey],connection))[0]
    if(prior){if(prior.digest!==digest)throw new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键已经用于其他任务');return json(prior.response).id as string}
    assertWrite(record,actor,context)
    await connection.query('INSERT INTO video_run(id,project_id,user_id,revision,kind,status,request) VALUES (?,?,?,?,?,\'queued\',?)',[id,projectId,actor.userId,context.expectedRevision,kind,JSON.stringify({context,...request as object})])
    await connection.query('INSERT INTO video_idempotency(project_id,request_key,digest,response) VALUES (?,?,?,?)',[projectId,context.idempotencyKey,digest,JSON.stringify({id})])
    return id
  })
  if(accepted!==id)return getRun(accepted)
  await event(id,'queued',{kind,revision:context.expectedRevision})
  return getRun(id)
}
export async function setStatus(id:string,status:string,result?:unknown,error?:string,worker?:string,epoch?:number) {
  const values=[status,result?JSON.stringify(result):null,error||null,id,...(worker?[worker,epoch]:[])]
  const sql=worker
    ? 'UPDATE video_run SET status=?,result=?,error=?,worker_id=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND worker_id=? AND lease_epoch=?'
    : 'UPDATE video_run SET status=?,result=?,error=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND worker_id IS NULL'
  const [updated]=await db.query<any>(sql,values)
  if(updated.affectedRows)await event(id,status,{result,error})
  return updated.affectedRows===1
}
export async function resumeRun(id:string,actor:Actor,context:WriteContext):Promise<boolean> {
  const digest=createHash('sha256').update(JSON.stringify({action:'resume',id,userId:actor.userId,context})).digest('hex')
  const accepted=await transaction(async connection=>{
    const run=(await rows('SELECT * FROM video_run WHERE id=?',[id],connection))[0]!
    const record=(await rows('SELECT * FROM video_project WHERE id=? FOR UPDATE',[run.project_id],connection))[0]!
    // Current read: the run lookup above may establish an older RR snapshot.
    // A concurrent resume publishes its key while this transaction waits on the project.
    const prior=(await rows('SELECT * FROM video_idempotency WHERE project_id=? AND request_key=? FOR UPDATE',[run.project_id,context.idempotencyKey],connection))[0]
    if(prior){if(prior.digest!==digest)throw new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键已经用于其他请求');return false}
    assertWrite(record,actor,context)
    const [result]=await connection.query<any>("UPDATE video_run SET status='queued',error=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status IN ('error','awaiting_authorization') AND cancelled=FALSE",[id])
    if(!result.affectedRows)throw new ApiError(409,'RUN_NOT_RETRYABLE','任务状态已变化，请刷新后重试')
    await connection.query('INSERT INTO video_idempotency(project_id,request_key,digest,response) VALUES (?,?,?,?)',[run.project_id,context.idempotencyKey,digest,JSON.stringify({id})])
    return true
  })
  if(accepted)await event(id,'queued',{resumed:true})
  return accepted
}
export async function resumedRequest(id:string,actor:Actor,context:WriteContext):Promise<boolean> {
  const run=await getRun(id),prior=(await rows('SELECT * FROM video_idempotency WHERE project_id=? AND request_key=?',[run.project_id,context.idempotencyKey]))[0]
  if(!prior)return false
  const digest=createHash('sha256').update(JSON.stringify({action:'resume',id,userId:actor.userId,context})).digest('hex')
  if(prior.digest!==digest)throw new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键已经用于其他请求')
  return true
}
export async function cancelled(id:string) {return !!(await rows('SELECT cancelled FROM video_run WHERE id=?',[id]))[0]?.cancelled}
export async function cancelRun(id:string,actor:Actor,context:WriteContext):Promise<boolean> {
  const run=await getRun(id),digest=createHash('sha256').update(JSON.stringify({action:'cancel',id,userId:actor.userId,context})).digest('hex')
  return transaction(async connection=>{
    const record=(await rows('SELECT * FROM video_project WHERE id=? FOR UPDATE',[run.project_id],connection))[0]!
    const prior=(await rows('SELECT * FROM video_idempotency WHERE project_id=? AND request_key=? FOR UPDATE',[run.project_id,context.idempotencyKey],connection))[0]
    if(prior){if(prior.digest!==digest)throw new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键已用于其他请求');return false}
    assertWrite(record,actor,context)
    const [updated]=await connection.query<any>("UPDATE video_run SET cancelled=TRUE,status=CASE WHEN status IN ('awaiting_authorization','unknown','lost') THEN 'cancelled' ELSE status END WHERE id=? AND status IN ('queued','running','awaiting_authorization','unknown','lost')",[id])
    if(!updated.affectedRows)throw new ApiError(409,'RUN_NOT_CANCELLABLE','任务已经结束，请刷新任务状态')
    await connection.query('INSERT INTO video_idempotency(project_id,request_key,digest,response) VALUES (?,?,?,?)',[run.project_id,context.idempotencyKey,digest,JSON.stringify({id})])
    return true
  })
}
export async function step(runId:string,stepId:string,provider:string,request:unknown) {
  await db.query('INSERT IGNORE INTO video_step(run_id,step_id,provider,status,request) VALUES (?,?,?,\'queued\',?)',[runId,stepId,provider,JSON.stringify(request)])
  const stored=(await rows('SELECT * FROM video_step WHERE run_id=? AND step_id=?',[runId,stepId]))[0]!
  if(!isDeepStrictEqual(json(stored.request),request))throw new ApiError(409,'STEP_REQUEST_CONFLICT','恢复步骤与原请求不一致，不能复用其他生成结果')
  return stored
}
export async function stepUpdate(runId:string,stepId:string,fields:{status:string;remoteTaskId?:string;buildId?:string;result?:unknown;error?:string}) {
  await db.query('UPDATE video_step SET status=?, remote_task_id=COALESCE(?,remote_task_id),build_id=COALESCE(?,build_id),result=COALESCE(?,result),error=? WHERE run_id=? AND step_id=?',
    [fields.status,fields.remoteTaskId||null,fields.buildId||null,fields.result?JSON.stringify(fields.result):null,fields.error||null,runId,stepId])
  await event(runId,'step',{stepId,...fields})
}
const runQueue=new FairRunQueue({
  maxConcurrent:Math.max(1,Number(process.env.VIDEO_QUEUE_CONCURRENCY||1)),
  maxConcurrentPerUser:Math.max(1,Number(process.env.VIDEO_USER_CONCURRENCY||1)),
  maxQueuedPerUser:Math.max(0,Number(process.env.VIDEO_USER_QUEUE_LIMIT||4)),
})
async function executeQueued(runId:string,work:()=>Promise<unknown>) {
  const claimed=await claimRun(runId)
  if(!claimed)return
  const epoch=claimed.epoch
  try{
    const current=await getRun(runId)
    if(current.status==='done'){await setStatus(runId,'done',undefined,undefined,workerId,epoch);return}
    if(await cancelled(runId)){await setStatus(runId,'cancelled',undefined,undefined,workerId,epoch);return}
    const result=await work()
    await setStatus(runId,await cancelled(runId)?'cancelled':'done',result,undefined,workerId,epoch)
  }catch(error){
    const status=error instanceof ApiError&&['SUBMISSION_UNKNOWN','REMOTE_TASK_LOST'].includes(error.code)?error.code==='SUBMISSION_UNKNOWN'?'unknown':'lost':await cancelled(runId)?'cancelled':'error'
    await setStatus(runId,status,undefined,error instanceof Error?error.message:String(error),workerId,epoch)
  }
}
async function claimRun(id:string):Promise<{epoch:number}|undefined>{
  const [updated]=await db.query<any>("UPDATE video_run SET status='running',worker_id=?,lease_epoch=lease_epoch+1,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 MINUTE),updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='queued' AND (worker_id=? OR worker_id IS NULL OR lease_until IS NULL OR lease_until<UTC_TIMESTAMP(3))",[workerId,id,workerId])
  if(!updated.affectedRows)return undefined
  const record=(await rows<any>('SELECT lease_epoch FROM video_run WHERE id=? AND worker_id=?',[id,workerId]))[0]
  return record?{epoch:Number(record.lease_epoch)}:undefined
}
export async function enqueue(runId:string, work:()=>Promise<unknown>) {
  const current=await getRun(runId)
  if(current.status==='done')return
  const [reserved]=await db.query<any>("UPDATE video_run SET worker_id=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 MINUTE),updated_at=UTC_TIMESTAMP(3) WHERE id=? AND status='queued' AND (worker_id=? OR worker_id IS NULL OR lease_until IS NULL OR lease_until<UTC_TIMESTAMP(3))",[workerId,runId,workerId])
  if(!reserved.affectedRows)return
  const reservedRow=(await rows<any>('SELECT lease_epoch FROM video_run WHERE id=? AND worker_id=?',[runId,workerId]))[0]
  const result=runQueue.enqueue({id:runId,userId:String(current.user_id),work:()=>executeQueued(runId,work)})
  if(result==='rejected'){
    const message='该用户的任务排队已满，请等待已有任务完成后重试'
    await setStatus(runId,'error',undefined,message,workerId,Number(reservedRow?.lease_epoch||0))
    throw new ApiError(429,'RUN_QUEUE_LIMIT',message)
  }
}
export async function recoverRuns() {
  // Cookies are never persisted. Reauthorization by the initiating user is required to read/model-download/COS-save again.
  const [result]=await db.query<any>("UPDATE video_run SET status='awaiting_authorization',error='服务重启：重新打开项目后可恢复已回执步骤；未知提交需明确重试',worker_id=NULL,lease_until=NULL,lease_epoch=lease_epoch+1,updated_at=UTC_TIMESTAMP(3) WHERE status IN ('running','queued') AND (worker_id IS NULL OR lease_until IS NULL OR lease_until<UTC_TIMESTAMP(3))")
  for(const record of await rows('SELECT id FROM video_run WHERE status=\'awaiting_authorization\' AND error LIKE \'服务重启：%\' ORDER BY updated_at DESC LIMIT ?', [Number(result.affectedRows)||0]))await event(record.id,'awaiting_authorization',{recovered:true})
}




