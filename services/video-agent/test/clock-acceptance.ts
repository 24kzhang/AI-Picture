import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root} from '../src/config.js'
import {db,rows,json} from '../src/db.js'
import {migrate} from '../src/migrate.js'
const directory=resolve(root,'runtime-data/video-acceptance'),cookie=JSON.parse(await readFile(resolve(directory,'accounts.json'),'utf8'))[0].cookie
async function api(path:string,body?:any,status=200){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.equal(response.status,status,JSON.stringify(value));return value}
try{
  const connections=await Promise.all(Array.from({length:4},()=>db.getConnection()))
  const sessions=[]
  try{for(const connection of connections){const row=(await rows('SELECT @@session.time_zone AS zone,TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW()) AS seconds',[],connection))[0]!;assert.equal(row.zone,'+00:00');assert.equal(Number(row.seconds),0);sessions.push(row)}}finally{for(const connection of connections)connection.release()}
  const old=await api('/runs/f0ea3ea2-5bc7-4231-aa92-39dd3306b17d');assert.ok(Math.abs(Date.now()-Date.parse(old.created_at))<3600_000)
  await migrate();const same=await api('/runs/'+old.id);assert.equal(same.created_at,old.created_at,'重复迁移再次移动了时间')
  const project=await api('/projects',{name:'UTC时钟与租约 · 验收'});assert.ok(Math.abs(Date.now()-Date.parse(project.created_at))<10_000)
  const sessionId=randomUUID(),lease=await api(`/projects/${project.id}/lease`,{action:'acquire',sessionId})
  const state=await api(`/projects/${project.id}`),remaining=Date.parse(state.project.lease_until)-Date.now();assert.ok(remaining>55_000&&remaining<=60_000)
  await api(`/projects/${project.id}/lease`,{action:'renew',sessionId,epoch:lease.epoch})
  await api(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:lease.epoch})
  await api(`/projects/${project.id}/lease`,{action:'renew',sessionId,epoch:lease.epoch},409)
  const marker=json((await rows("SELECT details FROM video_migration WHERE id='utc-clock@1'"))[0]!.details)
  await writeFile(resolve(directory,'utc-clock-report.json'),JSON.stringify({checkedAt:new Date().toISOString(),sessions,legacyRun:{id:old.id,createdAt:old.created_at},migration:marker,idempotent:true,projectId:project.id,createdAt:project.created_at,leaseRemainingMs:remaining,oldLeaseRejected:true},null,2),'utf8')
  console.log('四连接UTC时钟、旧记录一次校正、重复迁移幂等、新建时间与60秒租约通过')
}finally{await db.end()}
