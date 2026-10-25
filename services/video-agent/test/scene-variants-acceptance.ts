/** Realistic reference editing/image-to-video and narration replacement, using existing scene material. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root,dataRoot} from '../src/config.js'
const directory=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(directory,'accounts.json'),'utf8')),cookie=users[0].cookie
async function api(path:string,body?:any){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.ok(response.ok,JSON.stringify(value));return value}
const project=await api('/projects',{name:'咖啡旅行实景 · 参考图编辑与图生视频验收'}),sessionId=randomUUID(),held=await api(`/projects/${project.id}/lease`,{action:'acquire',sessionId})
let revision=1
const context=()=>({expectedRevision:revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()}),report:any={startedAt:new Date().toISOString(),projectId:project.id,runs:[]}
const save=()=>writeFile(resolve(directory,'scene-variants-report.json'),JSON.stringify(report,null,2),'utf8')
const renewal=setInterval(()=>api(`/projects/${project.id}/lease`,{action:'renew',sessionId,epoch:held.epoch}).catch(()=>{}),20000)
async function wait(run:any){report.runs.push({id:run.id,kind:run.kind});await save();for(let attempt=0;attempt<600;attempt++){const result=await api(`/runs/${run.id}`);if(!['queued','running'].includes(result.status)){Object.assign(report.runs.at(-1),{status:result.status,result:result.result,error:result.error,steps:result.steps.map((step:any)=>({id:step.step_id,taskId:step.remote_task_id,buildId:step.build_id}))});await save();assert.equal(result.status,'done',result.error);return result.result}await new Promise(r=>setTimeout(r,2000))}throw Error('真实模型验收超时')}
try{
 const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(resolve(dataRoot,'assets/c61d754f-1688-4c64-9c91-1b30057057e6/image.png')))]),'旅行咖啡店实景.png')
 const reference=await api(`/projects/${project.id}/assets`,form)
 const edited=await wait(await api(`/projects/${project.id}/generate`,{...context(),tool:'generate_image',args:{prompt:'保持咖啡店的真实摄影场景、窗外街道和木桌布局，在桌上的咖啡旁加入一只烘烤金黄的牛角包，以及一本打开的旅行手帐。自然光，丰富材质细节，不要文字水印。',referenceAssetIds:[reference.id],steps:12,seed:42}}))
 const video=await wait(await api(`/projects/${project.id}/generate`,{...context(),tool:'generate_video',args:{prompt:'Realistic documentary travel footage inside this cafe. Slow camera push toward the cup and golden croissant. Gentle steam rises from the coffee. Outside the window adult pedestrians walk naturally along the street. Preserve the detailed wooden table, notebook and cafe architecture. Natural sunlight, subtle realistic motion.',referenceAssetId:edited.id,width:768,height:512,num_frames:97,seed:42}}))
 const speech=await wait(await api(`/projects/${project.id}/generate`,{...context(),tool:'synthesize_speech',args:{text:'窗边的咖啡，记录旅途的片刻安静。',spk_name:'speaker1_a_cn_16k.wav',lang:'ZH',seed:42}}))
 assert.equal(speech.purpose,'speech')
 const result=await api(`/projects/${project.id}/operations`,{...context(),operations:[{type:'insert_clip',assetId:video.id,trackId:'visual',at:0},{type:'insert_clip',assetId:speech.id,trackId:'voice',at:12}]});revision=result.revision
 const originals=result.state.clips.filter((clip:any)=>clip.trackId==='original').sort((a:any,b:any)=>a.at-b.at)
 assert.ok(originals.length>=2);assert.equal(originals[0].at,0);assert.equal(originals[0].length,12);assert.equal(originals[0].muted,false)
 assert.ok(originals.some((clip:any)=>clip.at===12&&clip.muted));assert.equal(result.state.clips.find((clip:any)=>clip.assetId===speech.id).length,speech.frames)
 report.assets={reference:reference.id,edited:edited.id,video:video.id,speech:speech.id};report.originals=originals.map(({at,length,in:sourceIn,muted}:any)=>({at,length,sourceIn,muted}));await save()
 const output=await wait(await api(`/projects/${project.id}/exports`,context()));report.export=output
 report.completedAt=new Date().toISOString();await save();console.log('真实摄影场景图像编辑、图生视频、默认配音替换和 Hypit 导出验收通过')
}catch(error){report.error=(error as Error).message;await save();throw error}
finally{clearInterval(renewal);await api(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{})}
