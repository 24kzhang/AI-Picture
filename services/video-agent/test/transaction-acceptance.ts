import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root} from '../src/config.js'
import {db,rows,transaction} from '../src/db.js'
const dir=resolve(root,'runtime-data/video-acceptance'),cookie=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8'))[0].cookie,checks:string[]=[]
async function api(path:string,body:any){const r=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:'POST',headers:{Cookie:cookie,'Content-Type':'application/json'},body:JSON.stringify(body)});return {status:r.status,value:await r.json() as any}}
const p=(await api('/projects',{name:'持续回归 · 并发版本与事务回滚'})).value,sessionId=randomUUID(),held=(await api(`/projects/${p.id}/lease`,{action:'acquire',sessionId})).value
const ctx=(revision:number)=>({expectedRevision:revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()})
try{
  const edits=await Promise.all(Array.from({length:12},(_,n)=>api(`/projects/${p.id}/operations`,{...ctx(1),operations:[{type:'insert_clip',trackId:'titles',text:`并发提交 ${n}`,at:0,length:24}]})))
  assert.equal(edits.filter(v=>v.status===200).length,1);assert.equal(edits.filter(v=>v.status===409).length,11);assert.equal(Number((await rows('SELECT COUNT(*) AS count FROM video_revision WHERE project_id=?',[p.id]))[0]!.count),2);checks.push('12 concurrent edits of revision 1: exactly one commit, eleven conflicts, no orphan versions')
  const operation={...ctx(2),operations:[{type:'insert_clip',trackId:'titles',text:'同一请求重复二十次',at:24,length:24}]},duplicates=await Promise.all(Array.from({length:20},()=>api(`/projects/${p.id}/operations`,operation)))
  assert.ok(duplicates.every(v=>v.status===200&&v.value.revision===3));assert.equal(Number((await rows('SELECT COUNT(*) AS count FROM video_revision WHERE project_id=?',[p.id]))[0]!.count),3);checks.push('20 concurrent identical edit requests produce exactly one new revision')
  const scope='rollback-'+randomUUID();await db.query('INSERT INTO video_quota(scope,bytes) VALUES (?,17)',[scope])
  await assert.rejects(transaction(async connection=>{
    await connection.query('UPDATE video_project SET revision=4 WHERE id=?',[p.id])
    await connection.query('INSERT INTO video_revision(project_id,revision,source,author_id,message) SELECT project_id,4,source,author_id,? FROM video_revision WHERE project_id=? AND revision=3',['rollback fixture',p.id])
    await connection.query('UPDATE video_quota SET bytes=99 WHERE scope=?',[scope]);throw Error('injected failure after real writes')
  }))
  assert.equal((await rows('SELECT revision FROM video_project WHERE id=?',[p.id]))[0]!.revision,3);assert.equal(Number((await rows('SELECT COUNT(*) AS count FROM video_revision WHERE project_id=?',[p.id]))[0]!.count),3);assert.equal(Number((await rows('SELECT bytes FROM video_quota WHERE scope=?',[scope]))[0]!.bytes),17);checks.push('failure after three actual SQL writes rolls back project, version and quota together')
  await db.query('DELETE FROM video_quota WHERE scope=?',[scope])
  await writeFile(resolve(dir,'continuous-transaction-report.json'),JSON.stringify({completedAt:new Date().toISOString(),projectId:p.id,checks},null,2),'utf8');console.log('事务与并发版本验收通过',checks.length)
}finally{await api(`/projects/${p.id}/lease`,{action:'release',sessionId,epoch:held.epoch});await db.end()}
