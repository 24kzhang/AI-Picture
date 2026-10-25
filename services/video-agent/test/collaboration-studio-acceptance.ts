/** Real cold native Studios with simultaneous viewers: every bootstrap must be valid JSON with actual tracks. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root} from '../src/config.js'
const dir=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8')),proof=JSON.parse(await readFile(resolve(dir,'collaboration-report-20261004.json'),'utf8'))
async function get(path:string,user=0,body?:any){const r=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:users[user].cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const text=await r.text();assert.equal(r.status,200,text.slice(0,100));return text}
const parent=JSON.parse(await get(`/projects/${proof.projectId}`)),sessionId=randomUUID(),held=JSON.parse(await get(`/projects/${proof.projectId}/lease`,0,{action:'acquire',sessionId})),report:any={startedAt:new Date().toISOString(),cases:[]}
try{
 for(let round=0;round<3;round++){
  const cloned=JSON.parse(await get(`/projects/${proof.projectId}/variants`,0,{expectedRevision:parent.revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID(),name:`多人同时打开实景 Studio · 第 ${round+1} 轮`}))
  const base=`/projects/${cloned.id}/studio/`
  await Promise.all(Array.from({length:6},(_,n)=>get(base,n%3)))
  const snapshots=await Promise.all(Array.from({length:12},(_,n)=>get(base+'__studio/session',n%3)))
  const parsed=snapshots.map(text=>JSON.parse(text));assert.ok(parsed.every(value=>Array.isArray(value.tracks)&&value.tracks.length>0))
  assert.equal(new Set(parsed.map(value=>value.revision)).size,1)
  const visual=await get(base+'__studio/visual.html',2);assert.ok(visual.includes('<html'))
  report.cases.push({projectId:cloned.id,viewers:3,simultaneousRequests:12,nativeRevision:parsed[0].revision,trackCount:parsed[0].tracks.length,previewHtmlValid:true})
  console.log('原生多人冷启动通过',round+1,cloned.id)
 }
 report.completedAt=new Date().toISOString();await writeFile(resolve(dir,'collaboration-studio-report-20261004.json'),JSON.stringify(report,null,2),'utf8')
}finally{await get(`/projects/${proof.projectId}/lease`,0,{action:'release',sessionId,epoch:held.epoch})}
