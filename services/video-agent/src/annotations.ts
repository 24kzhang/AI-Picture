import { createHash } from 'node:crypto'
import { rows, transaction, json } from './db.js'
import { ApiError, invariant } from './errors.js'
import { config } from './config.js'
import { readState, sourceFor } from './hypit.js'
import { mergeAnnotations } from './annotation-operations.js'
import type { Actor } from './auth.js'
import type { WriteContext } from './projects.js'
import type { Asset } from './timeline.js'

export const annotationLimit = 5
export async function annotationMembers(id: string) {
  return rows('SELECT CAST(m.user_id AS CHAR) userId,m.session_id sessionId,m.epoch,m.lease_until AS `until`,u.userName name FROM video_annotation_member m LEFT JOIN user u ON u.id=m.user_id WHERE m.project_id=? AND m.lease_until>UTC_TIMESTAMP(3)',[id])
}
export async function annotationLease(id: string, actor: Actor, action: string, sessionId: string, epoch?: number) {
  return transaction(async connection => {
    const record = (await rows('SELECT * FROM video_project WHERE id=? FOR UPDATE',[id],connection))[0]!
    if (!record.space_id) throw new ApiError(400,'TEAM_REQUIRED','多人字幕与贴图仅用于团队工程')
    const current = (await rows('SELECT * FROM video_annotation_member WHERE project_id=? AND session_id=?',[id,sessionId],connection))[0]
    const alive = current?.lease_until && new Date(current.lease_until).getTime() > Date.now()
    if (action === 'acquire') {
      if (record.lease_until && new Date(record.lease_until).getTime()>Date.now()) throw new ApiError(409,'LEASE_BUSY','有人正在剪辑视频，暂时只能观看')
      const members = await rows('SELECT session_id FROM video_annotation_member WHERE project_id=? AND lease_until>UTC_TIMESTAMP(3)',[id],connection)
      if (!alive && members.length >= annotationLimit) throw new ApiError(409,'ROOM_FULL',`已有 ${annotationLimit} 个窗口同时编辑，当前可继续观看`)
      const next = alive ? Number(current.epoch) : Number(current?.epoch || 0)+1
      invariant(!current || String(current.user_id)===actor.userId, '编辑会话不属于当前用户')
      await connection.query('INSERT INTO video_annotation_member(project_id,session_id,user_id,epoch,lease_until) VALUES (?,?,?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND)) ON DUPLICATE KEY UPDATE epoch=VALUES(epoch),lease_until=VALUES(lease_until)',[id,sessionId,actor.userId,next])
      return {sessionId,epoch:next,userId:actor.userId,until:new Date(Date.now()+60_000).toISOString(),serverTime:Date.now()}
    }
    if (!alive || String(current.user_id)!==actor.userId || Number(current.epoch)!==epoch) throw new ApiError(409,'LEASE_EXPIRED','多人编辑资格已过期，请重新加入')
    if (action === 'renew') await connection.query('UPDATE video_annotation_member SET lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND) WHERE project_id=? AND session_id=?',[id,sessionId])
    else if (action === 'release') await connection.query('UPDATE video_annotation_member SET lease_until=NULL,epoch=epoch+1 WHERE project_id=? AND session_id=?',[id,sessionId])
    else throw new ApiError(400,'INVALID_ACTION','无效多人编辑操作')
    return {sessionId,epoch:action==='release'?epoch!+1:epoch,userId:actor.userId,until:action==='release'?null:new Date(Date.now()+60_000).toISOString(),serverTime:Date.now()}
  })
}
export async function operateAnnotations(id: string, actor: Actor, context: WriteContext, assets: Asset[], operations: unknown[]) {
  const digest=createHash('sha256').update(JSON.stringify({actor:actor.userId,context,operations})).digest('hex')
  return transaction(async connection => {
    const record=(await rows('SELECT * FROM video_project WHERE id=? FOR UPDATE',[id],connection))[0]!
    const previous=(await rows('SELECT * FROM video_idempotency WHERE project_id=? AND request_key=?',[id,context.idempotencyKey],connection))[0]
    if(previous){if(previous.digest!==digest)throw new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键已用于其他操作');return json(previous.response)}
    const member=(await rows('SELECT * FROM video_annotation_member WHERE project_id=? AND session_id=?',[id,context.sessionId],connection))[0]
    if(!member || String(member.user_id)!==actor.userId || Number(member.epoch)!==context.epoch || !member.lease_until || new Date(member.lease_until).getTime()<=Date.now())throw new ApiError(409,'LEASE_EXPIRED','多人编辑资格已过期，请重新加入')
    if(record.lease_until&&new Date(record.lease_until).getTime()>Date.now())throw new ApiError(409,'LEASE_BUSY','有人正在剪辑视频')
    const base=(await rows('SELECT source FROM video_revision WHERE project_id=? AND revision=?',[id,context.expectedRevision],connection))[0]
    const latest=(await rows('SELECT source FROM video_revision WHERE project_id=? AND revision=?',[id,record.revision],connection))[0]!
    invariant(base,'输入版本不存在')
    invariant(json(latest.source).mode!=='hypit','完整 Hypit 工程请使用独占编辑')
    const state=mergeAnnotations(readState(json(base.source)),readState(json(latest.source)),operations,assets,config.maxFrames)
    const source=sourceFor(state,assets),next=Number(record.revision)+1,response={revision:next,state,mode:'timeline',source}
    await connection.query('INSERT INTO video_revision(project_id,revision,source,author_id,message) VALUES (?,?,?,?,?)',[id,next,JSON.stringify(source),actor.userId,'多人字幕 / 贴图'])
    await connection.query('UPDATE video_project SET revision=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?',[next,id])
    await connection.query('INSERT INTO video_idempotency(project_id,request_key,digest,response) VALUES (?,?,?,?)',[id,context.idempotencyKey,digest,JSON.stringify(response)])
    return response
  })
}
