/** Explicit live scenario: a narrated travel montage with realistic moving scenery. */
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root,dataRoot} from '../src/config.js'
const folder=resolve(root,'runtime-data/video-acceptance'),accounts=JSON.parse(await readFile(resolve(folder,'accounts.json'),'utf8'))
const base='http://127.0.0.1:8091/api/video-editor',cookie=accounts[0].cookie
async function api(path:string,body?:any){const r=await fetch(base+path,{method:body?'POST':'GET',headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const v=await r.json() as any;if(!r.ok)throw Error(`${path}: ${r.status} ${JSON.stringify(v)}`);return v}
const report:any={startedAt:new Date().toISOString(),scenario:'自然与城市：镜头切换、导览图片、完整中文口播、字幕、原声控制及音乐',runs:[]}
const project=await api('/projects',{name:'自然与城市 · 真实场景剪辑验收'}),sessionId=randomUUID(),lease=await api(`/projects/${project.id}/lease`,{action:'acquire',sessionId});report.projectId=project.id
let revision=1
const ctx=()=>({expectedRevision:revision,sessionId,epoch:lease.epoch,idempotencyKey:randomUUID()})
const save=()=>writeFile(resolve(folder,'scene-report.json'),JSON.stringify(report,null,2),'utf8')
const renew=setInterval(()=>api(`/projects/${project.id}/lease`,{action:'renew',sessionId,epoch:lease.epoch}).catch(()=>{}),20000)
async function generate(tool:string,args:any){const run=await api(`/projects/${project.id}/generate`,{...ctx(),tool,args});report.runs.push({id:run.id,tool,args});await save();for(let n=0;n<600;n++){const r=await api(`/runs/${run.id}`);if(!['queued','running'].includes(r.status)){Object.assign(report.runs.at(-1),{status:r.status,result:r.result,steps:r.steps.map((s:any)=>({id:s.step_id,taskId:s.remote_task_id,buildId:s.build_id})),error:r.error});await save();if(r.status!=='done')throw Error(r.error);return r.result}await new Promise(r=>setTimeout(r,4000))}throw Error('场景生成超时')}
async function upload(path:string,name:string){const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(path))]),name);return api(`/projects/${project.id}/assets`,form)}
try{
 await mkdir(folder,{recursive:true});await save()
 const forest=await upload(resolve(dataRoot,'assets/a0d621ca-1ff4-4497-9164-c04eb4a50588/video.mp4'),'森林溪流实景.mp4')
 const city=await generate('generate_video',{prompt:'Documentary travel footage, a busy traditional Chinese street market in the morning, real adult pedestrians walking naturally past fruit stalls and shop fronts, a cyclist passes the camera, sunlight, detailed architecture, realistic live action, slow forward camera tracking, natural motion.',width:768,height:512,num_frames:97,seed:29})
 const image=await generate('generate_image',{prompt:'纪实摄影风格，一家临街咖啡店，木桌上放着咖啡和旅行手帐，窗外可见繁忙的街道和行人，温暖自然光，真实材质丰富细节，横向构图，不要文字不要水印',width:1024,height:768,steps:20,seed:29})
 const voice=await generate('synthesize_speech',{text:'沿着溪流，走进清晨的森林。转过街角，感受城市集市的热闹。最后，在咖啡香中，回味这段旅程。',spk_name:'speaker1_a_cn_16k.wav',lang:'ZH',seed:29})
 const music=await upload(resolve(folder,'media/travel-music.wav'),'旅行背景音乐.wav')
 const result=await api(`/projects/${project.id}/operations`,{...ctx(),operations:[{type:'insert_clip',assetId:forest.id,trackId:'visual',at:0},{type:'insert_clip',assetId:city.id,trackId:'visual',at:97},{type:'insert_clip',assetId:image.id,trackId:'visual',at:194,length:Math.max(72,voice.frames-194)},{type:'insert_clip',assetId:voice.id,trackId:'voice',at:0},{type:'insert_clip',assetId:music.id,trackId:'music',at:0,length:Math.min(music.frames,Math.max(266,voice.frames))},{type:'insert_clip',trackId:'titles',at:0,length:72,text:'自然与城市 · 一段旅程'}]});revision=result.revision
 const audio=result.state.clips.filter((c:any)=>c.trackId==='original').map((c:any)=>({type:'set_audio',clipId:c.id,muted:true}))
 audio.push(...result.state.clips.filter((c:any)=>c.trackId==='music').map((c:any)=>({type:'set_audio',clipId:c.id,volume:.15,fadeIn:24,fadeOut:24})))
 revision=(await api(`/projects/${project.id}/operations`,{...ctx(),operations:audio})).revision
 report.assets={forest,city,image,voice,music};report.revision=revision;report.preparedAt=new Date().toISOString();await save();console.log('真实场景素材和项目准备完成',project.id,revision)
}catch(e){report.error=(e as Error).message;await save();console.error(report.error);process.exitCode=1}
finally{clearInterval(renew);await api(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:lease.epoch}).catch(()=>{})}
