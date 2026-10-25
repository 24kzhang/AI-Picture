/** Explicit live acceptance: uses existing model services; all receipts/media stay in the ignored runtime directory. */
import { readFile,writeFile,mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { root } from '../src/config.js'
const directory=resolve(root,'runtime-data/video-acceptance'),accounts=JSON.parse(await readFile(resolve(directory,'accounts.json'),'utf8'))
const cookie=accounts[0].cookie,base='http://127.0.0.1:8091/api/video-editor'
const api=async(path:string,body?:any)=>{const response=await fetch(base+path,{method:body?'POST':'GET',headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const result=await response.json();if(!response.ok)throw new Error(`${path} ${response.status}: ${JSON.stringify(result)}`);return result as any}
const previous=process.env.RESUME_VIDEO_ACCEPTANCE?JSON.parse(await readFile(resolve(directory,'model-report.json'),'utf8')):undefined
const loaded=previous?await api(`/projects/${previous.projectId}`):undefined
const project=loaded?loaded.project:await api('/projects',{name:'真实模型串联验收'}),sessionId=randomUUID(),lease=await api(`/projects/${project.id}/lease`,{action:'acquire',sessionId})
let revision=loaded?.revision||1
const context=()=>({expectedRevision:revision,sessionId,epoch:lease.epoch,idempotencyKey:randomUUID()})
const report:any=previous||{projectId:project.id,startedAt:new Date().toISOString(),models:[]}
delete report.failure
const persist=()=>writeFile(resolve(directory,'model-report.json'),JSON.stringify(report,null,2),'utf8')
const renewal=setInterval(()=>api(`/projects/${project.id}/lease`,{action:'renew',sessionId,epoch:lease.epoch}).catch(e=>console.error('续租失败',e.message)),20000)
async function wait(run:any){const deadline=Date.now()+45*60_000;while(Date.now()<deadline){const state=await api(`/runs/${run.id}`);if(!['queued','running'].includes(state.status)){report.models.push({runId:run.id,kind:run.kind,status:state.status,steps:state.steps.map((s:any)=>({stepId:s.step_id,remoteTaskId:s.remote_task_id,buildId:s.build_id,status:s.status})),result:state.result,error:state.error});await persist();console.log(run.kind,run.id,state.status);if(state.status!=='done')throw Error(state.error||state.status);return state}await new Promise(r=>setTimeout(r,4000))}throw Error('验收等待超时')}
async function generate(tool:string,args:any){return (await wait(await api(`/projects/${project.id}/generate`,{...context(),tool,args}))).result}
try{
 await persist()
 if(process.env.EXPORT_ONLY){
 const output=await wait(await api(`/projects/${project.id}/exports`,context()))
 const video=await api('/videos',{...context(),projectId:project.id,revision:output.result.revision,runId:output.id,name:'真实模型串联验收'})
 report.videoId=video.id;report.completedAt=new Date().toISOString();await persist();console.log('真实模型串联成片完成',video.id)
 }else{
 const image=report.models.find((m:any)=>m.status==='done'&&m.result?.kind==='image')?.result||await generate('generate_image',{prompt:'一个清晰的红色立方体，白色背景，用于视频剪辑接口验收',width:256,height:256,steps:4,seed:17})
 const edited=report.models.filter((m:any)=>m.status==='done'&&m.result?.kind==='image')[1]?.result||await generate('generate_image',{prompt:'将红色立方体改成蓝色，保持白色背景',referenceAssetIds:[image.id],steps:4,seed:18})
 const textVideo=await generate('generate_video',{prompt:'A tracking shot through a lush forest beside a flowing river, sunlight shines through moving leaves, detailed realistic scenery.',width:768,height:512,num_frames:97,seed:17})
 const imageVideo=await generate('generate_video',{prompt:'The blue cube rotates smoothly in a full circle, cinematic camera moving around it, detailed surface and shadows.',width:768,height:512,num_frames:97,seed:18,referenceAssetId:edited.id})
 const voice=await generate('synthesize_speech',{text:'视频剪辑测试，现在开始。',spk_name:'speaker1_a_cn_16k.wav',lang:'ZH',seed:17})
 const edit=await api(`/projects/${project.id}/operations`,{...context(),operations:[{type:'insert_clip',assetId:textVideo.id,trackId:'visual',at:0},{type:'insert_clip',assetId:imageVideo.id,trackId:'visual',at:textVideo.frames},{type:'insert_clip',assetId:voice.id,trackId:'voice',at:0},{type:'insert_clip',trackId:'titles',at:0,length:textVideo.frames+imageVideo.frames,text:'真实模型串联验收'}]});revision=edit.revision
 const voiceClip=edit.state.clips.find((c:any)=>c.assetId===voice.id&&c.kind==='audio')
 const originals=edit.state.clips.filter((c:any)=>c.trackId==='original').map((c:any)=>({type:'set_audio',clipId:c.id,muted:true}))
 if(originals.length){revision=(await api(`/projects/${project.id}/operations`,{...context(),operations:originals})).revision}
 const agent=await wait(await api(`/projects/${project.id}/messages`,{...context(),message:`只对配音片段 ${voiceClip.id} 调用 transcribe，添加中文字幕。不要移动、裁剪或生成其他素材。`,selection:{clipId:voiceClip.id,playhead:0}}));revision=agent.result.revision
 const output=await wait(await api(`/projects/${project.id}/exports`,context()))
 const video=await api('/videos',{...context(),projectId:project.id,revision:output.result.revision,runId:output.id,name:'真实模型串联验收'})
 report.videoId=video.id;report.completedAt=new Date().toISOString();await persist();console.log('真实模型串联成片完成',video.id)
 }
}catch(error){report.failure=(error as Error).message;await persist();console.error(report.failure);process.exitCode=1}
finally{clearInterval(renewal);await api(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:lease.epoch}).catch(()=>{})}
