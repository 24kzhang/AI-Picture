import { randomUUID, createHash } from 'node:crypto'
import { db, rows, transaction, json } from './db.js'
import { ApiError, invariant } from './errors.js'
import { config } from './config.js'
import { applyOperations, emptyState, validateState, type State, type Asset } from './timeline.js'
import { sourceFor, readState, checkSource, type SourceBundle } from './hypit.js'
import type { Actor } from './auth.js'
import { normalizeSource } from './source-bundle.js'
export type WriteContext = { expectedRevision: number; sessionId: string; epoch: number; idempotencyKey: string }
export async function project(id: string) {
  invariant(/^[0-9a-f-]{36}$/i.test(id), '无效项目标识')
  const value = (await rows('SELECT *, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 snapshot_time, CAST(owner_id AS CHAR) owner_id, CAST(space_id AS CHAR) space_id, CAST(lease_user_id AS CHAR) lease_user_id FROM video_project WHERE id=?', [id]))[0]
  if (!value) throw new ApiError(404, 'PROJECT_NOT_FOUND', '项目不存在')
  return value
}
export async function revision(id: string, number?: number): Promise<{revision:number;source:SourceBundle;state:State;[key:string]:any}> {
  const records = await rows('SELECT * FROM video_revision WHERE project_id=?' + (number ? ' AND revision=?' : ' ORDER BY revision DESC LIMIT 1'), number ? [id,number] : [id])
  const record = records[0]; if (!record) throw new ApiError(404,'REVISION_NOT_FOUND','项目版本不存在')
  return { ...record, revision: Number(record.revision), source: json<SourceBundle>(record.source), state: readState(json(record.source)) }
}
export async function createProject(actor: Actor, name: string, spaceId?: string | null) {
  const id = randomUUID(), source = sourceFor(emptyState(), [])
  await checkSource(id,source,[])
  await transaction(async connection => {
    await connection.query('INSERT INTO video_project(id,space_id,owner_id,name) VALUES (?,?,?,?)',[id,spaceId||null,actor.userId,name.slice(0,200)||'未命名视频'])
    await connection.query('INSERT INTO video_revision(project_id,revision,source,author_id,message) VALUES (?,1,?,?,?)',[id,JSON.stringify(source),actor.userId,'创建项目'])
  })
  return project(id)
}
export function assertWrite(record: any, actor: Actor, context: WriteContext) {
  if (record.revision !== context.expectedRevision) throw new ApiError(409,'REVISION_CONFLICT','项目已经更新，请刷新后再提交',{ revision: record.revision })
  if (!context.sessionId || Number(record.lease_epoch) !== context.epoch || record.lease_session !== context.sessionId || String(record.lease_user_id) !== actor.userId || !record.lease_until || new Date(record.lease_until).getTime() <= Date.now())
    throw new ApiError(409,'LEASE_EXPIRED','编辑权已过期或交接，请重新获取编辑权')
}
export async function lease(id: string, actor: Actor, action: string, sessionId: string, epoch?: number, targetId?: string) {
  invariant(/^[0-9a-f-]{36}$/i.test(sessionId), '无效编辑会话')
  return transaction(async connection => {
    const record = (await rows('SELECT * FROM video_project WHERE id=? FOR UPDATE',[id],connection))[0]!
    const alive = record.lease_until && new Date(record.lease_until).getTime() > Date.now()
    if (action === 'acquire') {
      if (alive && (String(record.lease_user_id)!==actor.userId || record.lease_session && record.lease_session!==sessionId)) throw new ApiError(409,'LEASE_BUSY','其他会话正在编辑',{userId:String(record.lease_user_id),until:record.lease_until})
      if (!(alive && record.lease_session===sessionId)) record.lease_epoch=Number(record.lease_epoch)+1
      await connection.query('UPDATE video_project SET lease_user_id=?,lease_session=?,lease_epoch=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND) WHERE id=?',[actor.userId,sessionId,record.lease_epoch,id])
    } else {
      assertWrite(record,actor,{sessionId,epoch:epoch!,expectedRevision:record.revision,idempotencyKey:'lease'})
      if(action==='renew') await connection.query('UPDATE video_project SET lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND) WHERE id=?',[id])
      else if(action==='release') await connection.query('UPDATE video_project SET lease_user_id=NULL,lease_session=NULL,lease_until=NULL,lease_epoch=lease_epoch+1 WHERE id=?',[id])
      else if(action==='handoff') { invariant(targetId&&/^\d+$/.test(targetId),'请选择接收成员'); await connection.query('UPDATE video_project SET lease_user_id=?,lease_session=NULL,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND),lease_epoch=lease_epoch+1 WHERE id=?',[targetId,id]) }
      else throw new ApiError(400,'INVALID_LEASE_ACTION','无效租约操作')
    }
    const updated=(await rows('SELECT lease_user_id,lease_session,lease_epoch,lease_until FROM video_project WHERE id=?',[id],connection))[0]!
    return {userId:updated.lease_user_id?String(updated.lease_user_id):null,sessionId:updated.lease_session,epoch:Number(updated.lease_epoch),until:updated.lease_until,serverTime:Date.now()}
  })
}
export async function commitState(id: string, actor: Actor, context: WriteContext, assets: Asset[], candidate: State, message: string, payload: unknown, runId?: string) {
  validateState(candidate,assets,config.maxFrames)
  return commitSource(id,actor,context,assets,sourceFor(candidate,assets),message,payload,runId)
}
export async function commitSource(id: string, actor: Actor, context: WriteContext, assets: Asset[], candidate: SourceBundle, message: string, payload: unknown, runId?: string) {
  invariant(typeof context.idempotencyKey==='string'&&context.idempotencyKey.length>=8&&context.idempotencyKey.length<=100,'缺少幂等键')
  if(runId&&(await rows('SELECT cancelled FROM video_run WHERE id=?',[runId]))[0]?.cancelled)throw new ApiError(409,'RUN_CANCELLED','编辑任务已取消，不应用候选修改')
  const digest=createHash('sha256').update(JSON.stringify({user:actor.userId,payload,context})).digest('hex')
  const prior=(await rows('SELECT * FROM video_idempotency WHERE project_id=? AND request_key=?',[id,context.idempotencyKey]))[0]
  if(prior){if(prior.digest!==digest) throw new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键已经用于其他请求');return json(prior.response)}
  assertWrite(await project(id),actor,context)
  const source=normalizeSource(candidate,assets)
  const state=readState(source)
  if(source.mode!=='hypit')validateState(state,assets,config.maxFrames)
  await assertBuildReferences(id,source)
  await checkSource(id,source,assets)
  return transaction(async connection=>{
    const locked=(await rows('SELECT * FROM video_project WHERE id=? FOR UPDATE',[id],connection))[0]!
    const existing=(await rows('SELECT * FROM video_idempotency WHERE project_id=? AND request_key=?',[id,context.idempotencyKey],connection))[0]
    if(existing){if(existing.digest!==digest)throw new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键冲突');return json(existing.response)}
    assertWrite(locked,actor,context)
    if(runId&&(await rows('SELECT cancelled FROM video_run WHERE id=? FOR UPDATE',[runId],connection))[0]?.cancelled)throw new ApiError(409,'RUN_CANCELLED','编辑任务已取消，不应用候选修改')
    const next=locked.revision+1
    await connection.query('INSERT INTO video_revision(project_id,revision,source,author_id,message) VALUES (?,?,?,?,?)',[id,next,JSON.stringify(source),actor.userId,message.slice(0,300)])
    await connection.query('UPDATE video_project SET revision=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?',[next,id])
    const response={revision:next,state,mode:source.mode,source}
    await connection.query('INSERT INTO video_idempotency(project_id,request_key,digest,response) VALUES (?,?,?,?)',[id,context.idempotencyKey,digest,JSON.stringify(response)])
    return response
  })
}
export async function assertBuildReferences(id:string,source:SourceBundle){
  for(const text of Object.values(source.files||source))if(typeof text==='string')for(const match of text.matchAll(/<build-record\b[^>]*\bbuild=["']([^"']+)["']/g)){
    const owned=(await rows('SELECT s.run_id FROM video_step s JOIN video_run r ON r.id=s.run_id WHERE r.project_id=? AND s.build_id=?',[id,match[1]]))[0]
    invariant(owned,'只能复用本项目的 Build 产物')
  }
}
export async function operate(id: string, actor: Actor, context: WriteContext, assets: Asset[], operations: unknown[]) {
  const current=await revision(id,context.expectedRevision)
  invariant(current.source.mode!=='hypit','完整工程请通过 Hypit Studio 或源文件编辑，基础时间线无法表示所有组件')
  return commitState(id,actor,context,assets,applyOperations(current.state,operations,assets,config.maxFrames),'编辑时间线',{operations})
}

