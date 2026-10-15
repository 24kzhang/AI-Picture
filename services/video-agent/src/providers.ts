import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { z } from 'zod'
import { config, dataRoot } from './config.js'
import { ApiError, invariant } from './errors.js'
import { step, stepUpdate, cancelled, event } from './runs.js'
import { json } from './db.js'
import { importAsset } from './assets.js'
import type { Asset, Clip } from './timeline.js'
import type { Actor } from './auth.js'
import { command, ffmpegPath } from './hypit.js'
import { executeModelProvider,executeTranscriptProvider } from './model-sdk.js'

export const modelSchemas = {
  generate_image: z.object({ prompt:z.string().min(1).max(4000),width:z.number().int().min(256).max(2048).default(1024),height:z.number().int().min(256).max(2048).default(1024),steps:z.number().int().min(1).max(100).default(30),guidance_scale:z.number().min(0).max(20).default(4),seed:z.number().int().optional(),referenceAssetIds:z.array(z.string().uuid()).max(10).optional(),maskAssetId:z.string().uuid().optional(),mask_cue:z.boolean().default(true),mask_feather:z.number().int().min(0).max(128).default(12),keep_alpha:z.boolean().default(true) }).strict(),
  generate_video:z.object({prompt:z.string().min(1).max(4000),width:z.number().int().min(256).max(2048).multipleOf(32).default(768),height:z.number().int().min(256).max(2048).multipleOf(32).default(512),num_frames:z.number().int().min(9).max(2881).refine(n=>(n-1)%8===0,'帧数必须满足 (n-1)%8=0').default(49),frame_rate:z.literal(24).default(24),seed:z.number().int().default(10),referenceAssetId:z.string().uuid().optional(),enhance_prompt:z.boolean().default(false)}).strict(),
  synthesize_speech:z.object({text:z.string().min(1).max(5000),lang:z.enum(['ZH','EN','JA','KO','YUE','AUTO']).default('ZH'),spk_name:z.string().min(1).max(200).default('speaker1_a_cn_16k.wav'),referenceAssetId:z.string().uuid().optional(),duration_factor:z.number().min(.5).max(2).default(1),seed:z.number().int().default(-1),use_emo_text:z.boolean().default(false),emo_text:z.string().max(1000).optional(),emo_alpha:z.number().min(0).max(1).default(.5)}).strict(),
}
export type ModelTool = keyof typeof modelSchemas
const prefix = {generate_image:'/qwen-image',generate_video:'/ltx-video',synthesize_speech:'/indextts',transcribe:'/asr'}
const pause=(ms:number)=>new Promise<void>(r=>setTimeout(r,ms))
function resultUrl(raw:string,service:string) {
  const base=new URL(config.modelUrl)
  const url=new URL(raw,`${config.modelUrl}${service}/`)
  // Accept only this configured host. Loopback backend ports can appear in source responses; translate their service path through the gateway.
  invariant(url.hostname===base.hostname || ['127.0.0.1','localhost'].includes(url.hostname),'模型返回不可信下载地址')
  const pathname=url.pathname.startsWith(service+'/')?url.pathname:service+url.pathname
  return `${base.origin}${pathname}${url.search}`
}
export async function modelFetch(path:string,options:RequestInit,runId:string,submission=false) {
  for(let attempt=0;attempt<4;attempt++) {
    if(await cancelled(runId))throw new ApiError(409,'RUN_CANCELLED','任务已取消，后续步骤已停止')
    let response:Response
    try{response=await fetch(config.modelUrl+path,{...options,signal:AbortSignal.timeout(submission?30*60_000:30_000)})}
    catch(error){throw new ApiError(502,submission?'SUBMISSION_UNKNOWN':'MODEL_DISCONNECTED',submission?'提交未收到回执，结果未知；请检查后明确重试':'模型连接中断，已保留任务回执')}
    if(response.status===429&&attempt<3) {
      const header=response.headers.get('retry-after'); const numeric=Number(header)
      const delay=header?Number.isFinite(numeric)?numeric*1000:Math.max(1000,new Date(header).getTime()-Date.now()):5000
      await event(runId,'rate_limited',{retryAfterMs:delay});await pause(Math.min(delay,180_000));continue
    }
    if(response.status===404&&!submission)throw new ApiError(404,'REMOTE_TASK_LOST','远端任务丢失，可能因服务重启；可以明确重新生成')
    if(!response.ok){const error=(await response.text()).slice(0,2000);throw new ApiError(response.status,'MODEL_ERROR',`模型请求失败 (${response.status})：${error}`)}
    return response
  }
  throw new ApiError(429,'MODEL_BUSY','模型持续繁忙，请稍后重试')
}
export async function remoteJob(runId:string,stepId:string,service:string,requestSummary:unknown,submit:()=>Promise<Response>) {
  const saved=await step(runId,stepId,service,requestSummary)
  if(saved.result)return json(saved.result)
  let taskId=saved.remote_task_id as string|undefined
  if(!taskId) {
    if(saved.status==='submitting'||saved.status==='unknown')throw new ApiError(409,'SUBMISSION_UNKNOWN','此前提交没有回执，禁止自动重复提交；请明确重新生成')
    await stepUpdate(runId,stepId,{status:'submitting'})
    let response:Response
    try{response=await submit()}catch(error){await stepUpdate(runId,stepId,{status:error instanceof ApiError&&error.code==='SUBMISSION_UNKNOWN'?'unknown':'error',error:(error as Error).message});throw error}
    const value=await response.json() as any
    taskId=value.task_id||value.taskId
    if(!taskId){await stepUpdate(runId,stepId,{status:'done',result:value});return value}
    // Durable acknowledgement precedes all polling and collection.
    await stepUpdate(runId,stepId,{status:'acknowledged',remoteTaskId:taskId})
  }
  const deadline=Date.now()+90*60_000
  while(Date.now()<deadline) {
    const response=await modelFetch(`${service}/api/task/${encodeURIComponent(taskId!)}`,{},runId)
    const value=await response.json() as any
    if(value.status==='done'||value.status==='completed'){await stepUpdate(runId,stepId,{status:'done',result:value.result||value});return value.result||value}
    if(value.status==='error'||value.status==='failed'){await stepUpdate(runId,stepId,{status:'error',error:String(value.error||value.message)});throw new ApiError(422,'MODEL_EXECUTION_FAILED',String(value.error||value.message||'模型执行失败'))}
    await pause(2500)
  }
  throw new ApiError(504,'MODEL_POLL_TIMEOUT','轮询超时，远端任务回执仍然保留，可恢复查询')
}
const fileBlob=async(path:string,type='image/png')=>new Blob([new Uint8Array(await readFile(path))],{type})
export async function generateAsset(tool:ModelTool,raw:unknown,project:any,actor:Actor,assets:Asset[],runId:string,stepId:string):Promise<Asset> {
  const args=modelSchemas[tool].parse(raw)
  return executeModelProvider(tool,args,assets,()=>generateAssetWire(tool,args,project,actor,assets,runId,stepId))
}
async function generateAssetWire(tool:ModelTool,raw:unknown,project:any,actor:Actor,assets:Asset[],runId:string,stepId:string):Promise<Asset> {
  const args=modelSchemas[tool].parse(raw) as any, service=prefix[tool]
  const saved=await step(runId,stepId,service,args)
  const savedResult=saved.result?json(saved.result):null
  if(savedResult?.assetId){const existing=assets.find(a=>a.id===savedResult.assetId);if(existing)return existing}
  const result=await remoteJob(runId,stepId,service,args,async()=>{
    if(tool==='generate_image'&&!args.referenceAssetIds?.length) {
      const {referenceAssetIds,maskAssetId,mask_cue,mask_feather,keep_alpha,...fields}=args
      return modelFetch(service+'/api/generate',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(fields)},runId,true)
    }
    const form=new FormData()
    for(const [key,value]of Object.entries(args))if(!['referenceAssetId','referenceAssetIds','maskAssetId'].includes(key)&&value!==undefined)form.append(key,String(value))
    if(tool==='generate_image') {
      for(const id of args.referenceAssetIds){const asset=assets.find(a=>a.id===id);invariant(asset?.kind==='image','参考图必须是图像素材');form.append('image',await fileBlob(asset.path),`${asset.id}.png`)}
      if(args.maskAssetId){const mask=assets.find(a=>a.id===args.maskAssetId);invariant(mask?.kind==='image','掩码素材不存在');form.append('mask',await fileBlob(mask.path),'mask.png')}
      return modelFetch(service+'/api/edit',{method:'POST',body:form},runId,true)
    }
    form.set('wait','false')
    if(args.referenceAssetId){
      const asset=assets.find(a=>a.id===args.referenceAssetId);invariant(asset,'参考素材不存在')
      if(tool==='generate_video'){
        invariant(asset.kind==='image','LTX 参考素材必须是图像');const ref=new FormData(),name=`gallery-${randomUUID()}.png`
        ref.append('file',await fileBlob(asset.path),name);ref.append('name',name)
        await modelFetch(service+'/api/refs',{method:'POST',body:ref},runId)
        form.set('images',`${name}:0:1`)
      }else{invariant(asset.hasAudio,'音色参考必须含音频');form.delete('spk_name');form.append('spk_audio',await fileBlob(asset.audioPath||asset.path,'audio/wav'),'reference.wav')}
    }
    return modelFetch(service+(tool==='generate_video'?'/api/generate':'/api/synthesize'),{method:'POST',body:form},runId,true)
  })
  const rawUrl=result.url||result.video||result.audio
  invariant(typeof rawUrl==='string','模型没有返回产物地址')
  const response=await fetch(resultUrl(rawUrl,service),{signal:AbortSignal.timeout(180_000)})
  if(!response.ok)throw new ApiError(502,'MODEL_OUTPUT_UNAVAILABLE','生成结果无法下载；已保留远端回执')
  const extension=tool==='generate_image'?'png':tool==='generate_video'?'mp4':'wav'
  const path=resolve(dataRoot,`model-${randomUUID()}.${extension}`)
  await writeFile(path,Buffer.from(await response.arrayBuffer()))
  const asset=await importAsset(project,actor,path,`${tool==='generate_image'?'生成图像':tool==='generate_video'?'生成视频':'配音'}.${extension}`,tool==='synthesize_speech'?'speech':undefined)
  await stepUpdate(runId,stepId,{status:'collected',result:{...result,assetId:asset.id}})
  return asset
}
export async function transcribeClip(clip:Clip,assets:Asset[],runId:string,stepId:string) {
  const asset=assets.find(a=>a.id===clip.assetId);invariant(asset?.hasAudio,'所选片段没有音频')
  const path=resolve(dataRoot,`asr-${runId}-${stepId}.wav`)
  await command(ffmpegPath,['-y','-i',asset.audioPath||asset.path,'-vn','-af',`atrim=start=${clip.in/24}:end=${(clip.in+clip.length)/24},asetpts=PTS-STARTPTS`,'-ar','48000','-ac','2','-c:a','pcm_s16le',path])
  const form=new FormData();form.append('file',await fileBlob(path,'audio/wav'),'used-audio.wav');form.append('language','zh');form.append('char_alignments','true');form.append('word_timestamps','true');form.append('wait','false')
  return executeTranscriptProvider(path,()=>remoteJob(runId,stepId,'/asr',{clipId:clip.id,in:clip.in,length:clip.length},()=>modelFetch('/asr/api/transcribe',{method:'POST',body:form},runId,true)))
}
export async function voices() { const response=await fetch(config.modelUrl+'/indextts/api/voices',{signal:AbortSignal.timeout(15_000)});return response.json() }
export async function modelHealth() {
  return Promise.all(Object.values(prefix).map(async service=>{try{const response=await fetch(config.modelUrl+service+'/health',{signal:AbortSignal.timeout(5000)});return {service,ok:response.ok,data:await response.json()}}catch{return {service,ok:false}}}))
}
