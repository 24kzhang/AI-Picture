/** Owned local fixture runs, deliberately invalid local tool: no remote inference is submitted. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root} from '../src/config.js'
import {db,rows} from '../src/db.js'
const dir=resolve(root,'runtime-data/video-acceptance'),cookie=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8'))[0].cookie
async function api(path:string,body?:any,expected=200){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.equal(response.status,expected,JSON.stringify(value));return value}
const p=await api('/projects',{name:'持续回归 · 任务状态与重复恢复'}),sessionId=randomUUID(),held=await api(`/projects/${p.id}/lease`,{action:'acquire',sessionId}),checks:string[]=[]
const ctx=()=>({expectedRevision:1,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()})
async function fixture(status:string,cancelled=false){const id=randomUUID();await db.query('INSERT INTO video_run(id,project_id,user_id,revision,kind,status,request,cancelled) VALUES (?,?,?,1,?,?,?,?)',[id,p.id,'2105494801052676097','generation',status,JSON.stringify({tool:'invalid-local-test-tool',args:{},context:ctx()}),cancelled]);return id}
try{
  for(const status of ['done','queued','running']){
    const id=await fixture(status),before=(await rows('SELECT COUNT(*) AS count FROM video_run WHERE project_id=?',[p.id]))[0]!.count
    await api(`/runs/${id}/retry`,ctx(),409)
    assert.equal((await rows('SELECT COUNT(*) AS count FROM video_run WHERE project_id=?',[p.id]))[0]!.count,before)
    checks.push(`${status}: rejects retry without another run`)
  }
  const done=await fixture('done');await api(`/runs/${done}/cancel`,ctx(),409);assert.equal((await rows('SELECT cancelled FROM video_run WHERE id=?',[done]))[0]!.cancelled,0);checks.push('completed run cannot be marked cancelled')
  for(const status of ['awaiting_authorization','unknown','lost']){const id=await fixture(status);await api(`/runs/${id}/cancel`,ctx());assert.equal((await api(`/runs/${id}`)).status,'cancelled');checks.push(`${status}: inactive cancellation completes without a nonexistent worker`)}
  for(const status of ['unknown','lost','cancelled']){const id=await fixture(status,status==='cancelled');await api(`/runs/${id}/retry`,ctx(),409);checks.push(`${status}: requires explicit regeneration`)}
  for(let round=0;round<20;round++){
    const failed=await fixture('error'),request=ctx()
    const replies=await Promise.all(Array.from({length:8},()=>api(`/runs/${failed}/retry`,request)))
    assert.ok(replies.every(v=>v.id===failed))
  }
  checks.push('20 rounds × eight concurrent retries with one key reuse the same run')
  const ids=(await rows('SELECT id FROM video_run WHERE project_id=?',[p.id])).map(r=>r.id);assert.equal(new Set(ids).size,30)
  await writeFile(resolve(dir,'continuous-lifecycle-report.json'),JSON.stringify({projectId:p.id,completedAt:new Date().toISOString(),checks},null,2),'utf8');console.log('任务状态回归通过',checks.length)
}catch(error){await writeFile(resolve(dir,'continuous-lifecycle-failure.json'),JSON.stringify({projectId:p.id,error:(error as Error).message,runs:await rows('SELECT id,status,cancelled FROM video_run WHERE project_id=?',[p.id]),keys:await rows('SELECT request_key,digest,response FROM video_idempotency WHERE project_id=?',[p.id])},null,2),'utf8');throw error}
finally{await api(`/projects/${p.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{});await db.end()}
