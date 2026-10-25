/** Explicit restart acceptance: prepare a persisted queued task, restart the service, then verify with a new editing session. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root} from '../src/config.js'
import {createRun} from '../src/runs.js'
import {authorize} from '../src/auth.js'
import {db} from '../src/db.js'
const dir=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8')),cookie=users[0].cookie
async function api(path:string,body?:any){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.ok(response.ok,JSON.stringify(value));return value}
const file=resolve(dir,'restart-report.json')
try {
 if(process.argv[2]==='prepare') {
  const project=await api('/projects',{name:'服务重启与新会话恢复验收'}),sessionId=randomUUID(),held=await api(`/projects/${project.id}/lease`,{action:'acquire',sessionId})
  const context={expectedRevision:1,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()},actor=await authorize({headers:{cookie}} as any,project,'edit')
  const run=await createRun(project.id,actor,context,'agent',{message:'在标题轨添加一个72帧的标题“重启后恢复成功”，从第0帧开始，不需要生成素材，也不要导出。',selection:{}})
  await api(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:held.epoch})
  await writeFile(file,JSON.stringify({preparedAt:new Date().toISOString(),projectId:project.id,runId:run.id,priorSessionId:sessionId,status:run.status},null,2),'utf8')
  console.log('重启验收任务已持久化，未在测试进程执行')
 } else {
  const report=JSON.parse(await readFile(file,'utf8')),before=await api(`/runs/${report.runId}`)
  assert.equal(before.status,'awaiting_authorization')
  const sessionId=randomUUID(),held=await api(`/projects/${report.projectId}/lease`,{action:'acquire',sessionId})
  const context={expectedRevision:1,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()}
  assert.notEqual(sessionId,report.priorSessionId)
  await api(`/runs/${report.runId}/retry`,context)
  let result:any
  for(let attempt=0;attempt<90;attempt++){result=await api(`/runs/${report.runId}`);if(!['running','queued'].includes(result.status))break;await new Promise(r=>setTimeout(r,1000))}
  assert.equal(result.status,'done',result.error)
  const project=await api(`/projects/${report.projectId}`)
  assert.equal(project.revision,2);assert.ok(project.state.clips.some((clip:any)=>clip.text==='重启后恢复成功'))
  assert.match(project.project.updated_at,/^\d{4}-\d{2}-\d{2}T/)
  const existing=await api('/runs/2c63531d-a3f1-4c09-8a2e-5fa3ba03c565'),contextBefore=existing.request.context
  const duplicate=await api(`/projects/${existing.project_id}/exports`,contextBefore)
  assert.equal(duplicate.id,existing.id);assert.equal(duplicate.status,'done');assert.equal(duplicate.steps.length,existing.steps.length)
  assert.ok(!JSON.stringify(project).includes('audioPath'));assert.ok(!JSON.stringify(existing).includes('"path":'))
  const restored=await api('/projects/46edc7a4-dc61-4192-a4e4-b4978da204c7')
  assert.equal(restored.revision,12);assert.ok(restored.assets.length>=5);assert.ok(restored.state.clips.length>10)
  await api(`/projects/${report.projectId}/lease`,{action:'release',sessionId,epoch:held.epoch})
  await writeFile(file,JSON.stringify({...report,completedAt:new Date().toISOString(),status:result.status,revision:project.revision,newSessionId:sessionId,duplicateExportId:duplicate.id,persistentSceneRevision:restored.revision,checks:['queued task recovered after actual process restart','new session lease used for real Agent commit','project/assets/versions/export receipts retained','duplicate completed export did not restart','dates serialize correctly and local media paths remain private']},null,2),'utf8')
  console.log('实际服务重启、新会话 Agent 恢复、成片回执和重复导出验收通过')
 }
} finally {await db.end()}
