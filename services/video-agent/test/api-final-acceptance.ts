/** Live API checks for cancellation, authorized gallery-image import and sanitized event replay. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root} from '../src/config.js'
const dir=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8')),cookie=users[0].cookie
async function api(path:string,body?:any){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.ok(response.ok,JSON.stringify(value));return value}
const project=await api('/projects',{name:'实际对话取消与提交保护验收'}),sessionId=randomUUID(),held=await api(`/projects/${project.id}/lease`,{action:'acquire',sessionId})
const context=()=>({expectedRevision:1,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()})
try{
 const run=await api(`/projects/${project.id}/messages`,{...context(),message:'先查看项目，然后在标题轨添加三个连续72帧的标题：森林、城市、咖啡。不要生成素材，也不要导出。'})
 await api(`/runs/${run.id}/cancel`,context())
 let result:any
 for(let attempt=0;attempt<120;attempt++){result=await api(`/runs/${run.id}`);if(!['queued','running'].includes(result.status))break;await new Promise(r=>setTimeout(r,500))}
 assert.equal(result.status,'cancelled',result.error)
 const latest=await api(`/projects/${project.id}`);assert.equal(latest.revision,1);assert.equal(latest.state.clips.length,0)
 const team=JSON.parse(await readFile(resolve(dir,'team-browser-report.json'),'utf8'))
 const imported=await api(`/projects/${team.projectId}/assets/import`,{pictureId:team.pictureId});assert.equal(imported.kind,'image');assert.ok(imported.objectKey.startsWith(`videos/space-${team.spaceId}/`))
 const response=await fetch('http://127.0.0.1:8091/api/video-editor/runs/2c63531d-a3f1-4c09-8a2e-5fa3ba03c565/events',{headers:{Cookie:cookie},signal:AbortSignal.timeout(10000)})
 assert.ok(response.ok);const reader=response.body!.getReader(),decoder=new TextDecoder();let events=''
 try{for(let chunk=0;chunk<100;chunk++){const part=await reader.read();if(part.done)break;events+=decoder.decode(part.value,{stream:true});if(events.includes('event: done'))break}}finally{await reader.cancel()}
 assert.ok(events.includes('event: done'));assert.ok(!events.includes('"path":'));assert.ok(!events.includes('"audioPath":'))
 await writeFile(resolve(dir,'api-final-report.json'),JSON.stringify({completedAt:new Date().toISOString(),projectId:project.id,runId:run.id,status:result.status,revision:latest.revision,importedPictureId:team.pictureId,importedAssetId:imported.id,checks:['actual API cancellation did not apply Agent candidate','authorized existing team image imported and persisted','completed export SSE replay omits local media paths']},null,2),'utf8')
 console.log('实际取消保护、图库图片授权导入与 SSE 回放验收通过')
}finally{await api(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{})}
