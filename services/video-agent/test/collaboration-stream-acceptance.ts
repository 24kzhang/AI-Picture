/** Real Java logout revokes an already-open task stream and native Studio socket. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {createRequire} from 'node:module'
import {root,config} from '../src/config.js'
import {db} from '../src/db.js'
import {authorize} from '../src/auth.js'
import {createRun} from '../src/runs.js'
const {WebSocket}=createRequire(import.meta.url)('ws')
const dir=resolve(root,'runtime-data/video-acceptance'),accountsPath=resolve(dir,'accounts.json'),users=JSON.parse(await readFile(accountsPath,'utf8'))
const proof=JSON.parse(await readFile(resolve(dir,'collaboration-report-20261004.json'),'utf8')),base='http://127.0.0.1:8091/api/video-editor',report:any={startedAt:new Date().toISOString(),checks:[]}
async function api(path:string,body?:any,user=0){const response=await fetch(base+path,{method:body?'POST':'GET',headers:{Cookie:users[user].cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const value=await response.json();assert.equal(response.status,200,JSON.stringify(value));return value as any}
async function java(path:string,body?:any){const response=await fetch(config.javaUrl+'/user/'+path,{method:'POST',headers:{...(path==='login'?{}:{Cookie:users[2].cookie}),'Content-Type':'application/json'},body:JSON.stringify(body||{})});const result=await response.json() as any;assert.equal(result.code,0,result.message);return response}
async function loginViewer(){const response=await java('login',{userAccount:users[2].account,userPassword:users[2].password});const cookie=response.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ');assert.ok(cookie);users[2].cookie=cookie;await writeFile(accountsPath,JSON.stringify(users,null,2),'utf8')}
const project=await api('/projects/'+proof.projectId),sessionId=randomUUID(),held=await api(`/projects/${proof.projectId}/lease`,{action:'acquire',sessionId}),ctx={expectedRevision:project.revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()}
let run:any,socket:any,reader:ReadableStreamDefaultReader<Uint8Array>|undefined,controlReader:ReadableStreamDefaultReader<Uint8Array>|undefined,replayReader:ReadableStreamDefaultReader<Uint8Array>|undefined,loggedOut=false
const abort=new AbortController(),watchdog=setTimeout(()=>abort.abort(),35_000)
try{
 await loginViewer()
 const actor=await authorize({headers:{cookie:users[0].cookie}} as any,project.project,'edit')
 run=await createRun(proof.projectId,actor,ctx,'export',{testFixture:'collaboration-stream-20261004'}) // Never enqueue GPU/render work.
 report.runId=run.id;report.projectId=proof.projectId
 const response=await fetch(`${base}/runs/${run.id}/events`,{headers:{Cookie:users[2].cookie},signal:abort.signal});assert.equal(response.status,200)
 reader=response.body!.getReader();const first=await reader.read();assert.ok(new TextDecoder().decode(first.value).includes('event: queued'))
 const control=await fetch(`${base}/runs/${run.id}/events`,{headers:{Cookie:users[0].cookie},signal:abort.signal});assert.equal(control.status,200)
 controlReader=control.body!.getReader();const initialControl=new TextDecoder().decode((await controlReader.read()).value),queuedId=Number(initialControl.match(/id: (\d+)/)?.[1]);assert.ok(queuedId>0&&initialControl.includes('event: queued'))
 const studio=`/projects/${proof.projectId}/studio/`
 assert.equal((await fetch(base+studio,{headers:{Cookie:users[2].cookie}})).status,200)
 const client=await fetch(base+studio+'@vite/client',{headers:{Cookie:users[2].cookie}});assert.equal(client.status,200)
 const script=await client.text(),token=script.match(/const wsToken\s*=\s*"([^"]+)"/)?.[1];assert.ok(token,'Vite websocket bootstrap token')
 socket=new WebSocket(`ws://127.0.0.1:8091/api/video-editor${studio}?token=${encodeURIComponent(token!)}`,'vite-hmr',{headers:{Cookie:users[2].cookie,Origin:'http://127.0.0.1:8091'}})
 const socketClosed=new Promise<void>(resolve=>socket.once('close',()=>resolve()))
 await new Promise<void>((resolve,reject)=>{socket.once('open',()=>resolve());socket.once('error',reject)})
 report.checks.push('查看成员已建立真实 SSE 和受保护的原生 Studio WebSocket')
 await java('logout');loggedOut=true;const revokedAt=Date.now()
 await api(`/runs/${run.id}/cancel`,{...ctx,idempotencyKey:randomUUID()})
 let controlEvents='';while(!controlEvents.includes('event: cancel_requested')){const chunk=await controlReader.read();assert.ok(!chunk.done);controlEvents+=new TextDecoder().decode(chunk.value)}
 assert.ok(Number(controlEvents.match(/id: (\d+)/)?.[1])>queuedId)
 const replay=await fetch(`${base}/runs/${run.id}/events`,{headers:{Cookie:users[0].cookie,'Last-Event-ID':String(queuedId)},signal:abort.signal});assert.equal(replay.status,200)
 replayReader=replay.body!.getReader();const replayed=new TextDecoder().decode((await replayReader.read()).value);assert.ok(replayed.includes('event: cancel_requested')&&!replayed.includes('event: queued'))
 report.checks.push('正常成员实时收到取消事件；Last-Event-ID 重连只补发后续事件')
 let received='';while(true){const chunk=await reader.read();if(chunk.done)break;received+=new TextDecoder().decode(chunk.value)}
 assert.ok(!received.includes('cancel_requested')&&!received.includes('remoteInferenceStopped'),'撤销后不发送新的任务内容')
 report.checks.push('退出登录后，已有 SSE 关闭且未泄露后续取消事件')
 assert.equal((await fetch(`${base}/runs/${run.id}/events`,{headers:{Cookie:users[2].cookie}})).status,401)
 let socketDeadline:ReturnType<typeof setTimeout>|undefined
 try{await Promise.race([socketClosed,new Promise((_,reject)=>{socketDeadline=setTimeout(()=>reject(Error('原生 WebSocket 未在限时内关闭')),25_000)})])}finally{if(socketDeadline)clearTimeout(socketDeadline)}
 report.socketRevocationMs=Date.now()-revokedAt;assert.ok(report.socketRevocationMs<25_000)
 report.checks.push('原生 Studio WebSocket 在周期权限复查后关闭；旧凭证无法重新订阅任务')
 report.completedAt=new Date().toISOString();await writeFile(resolve(dir,'collaboration-stream-report-20261004.json'),JSON.stringify(report,null,2),'utf8');console.log('真实退出登录：SSE 和原生 Studio 长连接权限撤销通过',report.socketRevocationMs+'ms')
}finally{
 clearTimeout(watchdog);abort.abort();socket?.terminate();await reader?.cancel().catch(()=>{});await controlReader?.cancel().catch(()=>{});await replayReader?.cancel().catch(()=>{})
 if(run)await db.query("UPDATE video_run SET cancelled=TRUE,status='cancelled' WHERE id=?",[run.id])
 await api(`/projects/${proof.projectId}/lease`,{action:'release',sessionId,epoch:held.epoch})
 if(loggedOut)await loginViewer()
 await db.end()
}
