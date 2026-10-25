/** Prepare real team media for browser checks; relay as a separate authenticated editor after the UI hands off. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root,dataRoot} from '../src/config.js'
const dir=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8'))
const file=resolve(dir,'team-browser-report.json')
async function request(base:string,path:string,body?:any,user=0){const response=await fetch(base+path,{method:body?'POST':'GET',headers:{Cookie:users[user].cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.ok(response.ok,JSON.stringify(value));return value}
const api=(path:string,body?:any,user=0)=>request('http://127.0.0.1:8091/api/video-editor',path,body,user)
if(process.argv[2]==='prepare'){
 const {spaceId}=JSON.parse(await readFile(resolve(dir,'integration-report.json'),'utf8')),project=await api('/projects',{name:'团队真实场景 · 浏览器交接验收',spaceId}),sessionId=randomUUID(),held=await api(`/projects/${project.id}/lease`,{action:'acquire',sessionId})
 const context={expectedRevision:1,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()}
 const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(resolve(dir,'media/森林溪流-上传验收.mov')))]),'团队森林溪流.mov')
 const asset=await api(`/projects/${project.id}/assets`,form)
 const result=await api(`/projects/${project.id}/operations`,{...context,operations:[{type:'insert_clip',assetId:asset.id,trackId:'visual',at:0}]})
 await api(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:held.epoch})
 const image=new FormData();image.append('file',new Blob([new Uint8Array(await readFile(resolve(dataRoot,'assets/b2e94c19-c322-47b3-9302-b3b9523ef9db/image.png')))],{type:'image/png'}),'团队旅行咖啡照片.png');image.append('spaceId',spaceId)
 const picture=await request('http://127.0.0.1:8090/api','/picture/upload',image);assert.equal(picture.code,0,picture.message)
 await writeFile(file,JSON.stringify({preparedAt:new Date().toISOString(),projectId:project.id,revision:result.revision,spaceId,pictureId:String(picture.data.id),editorId:users[1].id},null,2),'utf8');console.log('真实团队素材已准备')
}else{
 const report=JSON.parse(await readFile(file,'utf8')),sessionId=randomUUID(),held=await api(`/projects/${report.projectId}/lease`,{action:'acquire',sessionId},1),current=await api(`/projects/${report.projectId}`,undefined,1)
 const result=await api(`/projects/${report.projectId}/operations`,{expectedRevision:current.revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID(),operations:[{type:'insert_clip',trackId:'titles',at:0,length:72,text:'交接后由新成员编辑'}]},1)
 await api(`/projects/${report.projectId}/lease`,{action:'release',sessionId,epoch:held.epoch},1)
 await writeFile(file,JSON.stringify({...report,relayedAt:new Date().toISOString(),revision:result.revision,relaySessionId:sessionId},null,2),'utf8');console.log('独立成员账号完成交接后的写入')
}
