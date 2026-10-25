/** Deterministic SQL contention: a blocked cancellation must recheck the lease after the lock releases. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root} from '../src/config.js'
import {db,rows,transaction} from '../src/db.js'
import {authorize} from '../src/auth.js'
import {createProject,lease} from '../src/projects.js'
import {createRun,cancelRun,getRun} from '../src/runs.js'
const dir=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8')),{spaceId}=JSON.parse(await readFile(resolve(dir,'integration-report.json'),'utf8'))
const actor=await authorize({headers:{cookie:users[0].cookie}} as any,{space_id:spaceId},'edit'),p=await createProject(actor,'锁竞争中取消 · 多人协同事务验收',spaceId),sessionId=randomUUID(),held=await lease(p.id,actor,'acquire',sessionId)
const ctx={expectedRevision:1,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()},run=await createRun(p.id,actor,ctx,'export',{})
const report:any={startedAt:new Date().toISOString(),projectId:p.id,runId:run.id,checks:[]}
try{
 let pending:Promise<any>|undefined
 await transaction(async connection=>{
  await rows('SELECT id FROM video_project WHERE id=? FOR UPDATE',[p.id],connection)
  // Capture the rejection immediately so there is no unhandled promise while this transaction owns the lock.
  pending=cancelRun(run.id,actor,{...ctx,idempotencyKey:randomUUID()}).then(value=>({value}),error=>({error}))
  await new Promise(r=>setTimeout(r,250))
  await connection.query('UPDATE video_project SET lease_user_id=?,lease_session=NULL,lease_epoch=lease_epoch+1,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND) WHERE id=?',[users[1].id,p.id])
 })
 const rejected=await pending!;assert.equal(rejected.error?.code,'LEASE_EXPIRED');assert.equal((await getRun(run.id)).cancelled,0)
 report.checks.push('数据库锁等待期间模拟真实交接状态变化；旧会话取消返回 LEASE_EXPIRED，任务没有被取消')
 const editor=await authorize({headers:{cookie:users[1].cookie}} as any,{space_id:spaceId},'edit'),otherSession=randomUUID(),other=await lease(p.id,editor,'acquire',otherSession),cancelContext={expectedRevision:1,sessionId:otherSession,epoch:other.epoch,idempotencyKey:randomUUID()}
 const duplicates=await Promise.all(Array.from({length:16},()=>cancelRun(run.id,editor,cancelContext)))
 assert.equal(duplicates.filter(Boolean).length,1);assert.equal((await getRun(run.id)).cancelled,1)
 assert.equal(Number((await rows('SELECT COUNT(*) count FROM video_idempotency WHERE project_id=? AND request_key=?',[p.id,cancelContext.idempotencyKey]))[0]!.count),1)
 await assert.rejects(cancelRun(run.id,editor,{...cancelContext,expectedRevision:2}),{code:'IDEMPOTENCY_CONFLICT'})
 report.checks.push('16 次相同取消请求只有一次状态修改；复用幂等键改变参数被拒绝')
 await lease(p.id,editor,'release',otherSession,other.epoch)
 await db.query("UPDATE video_run SET status='cancelled' WHERE id=?",[run.id])
 report.completedAt=new Date().toISOString();await writeFile(resolve(dir,'collaboration-fencing-report-20261004.json'),JSON.stringify(report,null,2),'utf8');console.log('协同事务复查与取消幂等验收通过')
}finally{await db.end()}
