import { resolve, relative } from 'node:path'
import { randomUUID } from 'node:crypto'
import { mkdir, stat, readdir, readFile } from 'node:fs/promises'
import { extname } from 'node:path'
import {createWriteStream} from 'node:fs'
import {pipeline} from 'node:stream/promises'
import {ZipFile} from 'yazl'
import sharp, {type Metadata} from 'sharp'
import { runEntry, outputTargets } from './source-bundle.js'
import { config } from './config.js'
import { dataRoot } from './config.js'
import { writeWorkspace, hypit, command, ffmpegPath, patchMediaExecution, type SourceBundle } from './hypit.js'
import { probe, storeObject } from './assets.js'
import { step, stepUpdate, cancelled, event } from './runs.js'
import type { Actor } from './auth.js'
import type { Asset } from './timeline.js'
import { duration } from './timeline.js'
import { readState } from './hypit.js'
import { ApiError, invariant } from './errors.js'
import { json } from './db.js'
let prepared: Promise<void> | undefined
export async function renderProject(project:any,revision:number,source:SourceBundle,assets:Asset[],actor:Actor,runId:string) {
  const saved=await step(runId,'render','hypit',{revision,assetIds:source.assetIds})
  if(saved.result?.objectKey||saved.status==='done')return json(saved.result)
  await patchMediaExecution()
  const workspace=await writeWorkspace(project.id,revision,source,assets)
  const profile=resolve(workspace,'hypit.runtime.json')
  prepared??=hypit(['runtime','up','--runtime',profile],workspace,resolve(workspace,'prepare.log')).then(()=>{})
  try{await prepared}catch(error){prepared=undefined;throw error}
  let buildId=saved.build_id as string|undefined
  if(buildId) {
    // Completed Output may be collected. Active old Builds are inspected rather than silently resumed.
    const status=JSON.parse(await hypit(['status',buildId,'--runtime',profile],workspace)).build
    if(status?.work?.outcome!=='complete')throw new ApiError(409,'BUILD_INTERRUPTED','原渲染 Build 未完成。请明确重试创建新 Build；已有素材继续复用')
  } else {
    const accepted=JSON.parse(await hypit(['build',resolve(workspace,runEntry(source)),'--runtime',profile],workspace,resolve(workspace,`${runId}.submit.log`)))
    buildId=accepted.build.id
    await stepUpdate(runId,'render',{status:'acknowledged',buildId})
    const deadline=Date.now()+60*60_000
    let complete=false
    while(Date.now()<deadline) {
      if(await cancelled(runId)){await hypit(['cancel',buildId!,'--runtime',profile],workspace).catch(()=>{});throw new ApiError(409,'RUN_CANCELLED','导出已取消')}
      const report=JSON.parse(await hypit(['status',buildId!,'--runtime',profile],workspace))
      const status=report.build
      if(status?.work?.outcome==='complete'){complete=true;break}
      if(status?.work?.outcome==='failed'||status?.work?.outcome==='cancelled'||status?.attention || status?.failure){throw new ApiError(422,'HYPIT_RENDER_FAILED',JSON.stringify(status.attention||status.failure||status).slice(0,3000))}
      await event(runId,'render_progress',{buildId,work:status?.work,phase:status?.phase})
      await new Promise<void>(r=>setTimeout(r,3000))
    }
    if(!complete)throw new ApiError(504,'RENDER_TIMEOUT','导出等待超时，Build 回执已保留')
  }
  const directory=resolve(dataRoot,'exports',runId);await mkdir(directory,{recursive:true})
  if(source.mode==='hypit'){
    const artifacts:any[]=[]
    const targets=outputTargets(source);invariant(targets.length,'SVRun 没有可导出的 target')
    for(const [index,name] of targets.entries()){
      if(await cancelled(runId))throw new ApiError(409,'RUN_CANCELLED','产物收集已取消')
      const destination=resolve(directory,`output-${index}-${randomUUID()}`)
      const receipt=JSON.parse(await hypit(['get',buildId!,'--output',name,'--to',destination],workspace))
      const composite=(await stat(destination)).isDirectory(),types=new Map<string,string>()
      if(composite){const document=JSON.parse(await readFile(resolve(destination,'value.json'),'utf8'));for(const resource of document.resources||[])types.set(resource.file.path,resource.file.mediaType)}
      else if(receipt.kind==='resource'){const inspected=JSON.parse(await hypit(['inspect',buildId!,'--output',name],workspace));types.set('',inspected.build.outputs?.find((o:any)=>o.name===name)?.mediaType||'application/octet-stream')}
      const files=(await stat(destination)).isDirectory()?(await readdir(destination,{recursive:true,withFileTypes:true})).filter(d=>d.isFile()).map(d=>resolve(d.parentPath,d.name)):[destination]
      if(composite){
        const bundle=resolve(directory,`bundle-${index}.zip`),zip=new ZipFile()
        for(const file of files)zip.addFile(file,relative(destination,file).replaceAll('\\','/'))
        const publication=pipeline(zip.outputStream,createWriteStream(bundle));zip.end();await publication
        artifacts.push({name,part:'bundle',kind:'composite',mediaType:'application/zip',objectKey:await storeObject(actor,project,bundle,'zip'),size:(await stat(bundle)).size,revision})
      }
      for(const [part,path] of files.entries()){
        let output=path,mediaType='application/json',extension='json',frames=0,width=0,height=0,posterKey:string|undefined,fps=24
        const declaredType=types.get(composite?relative(destination,path).replaceAll('\\','/'):'')
        if(declaredType&&!/^(?:image|video|audio)\//.test(declaredType)){mediaType=declaredType;extension=({'font/ttf':'ttf','font/otf':'otf','font/woff':'woff','font/woff2':'woff2','application/json':'json'} as Record<string,string>)[declaredType]||'bin'}
        else if(receipt.kind==='resource'||receipt.export?.kind==='resource'||composite&&extname(path)!=='.json'){
          let image:Metadata|undefined;try{image=await sharp(path).metadata()}catch{}
          if(image?.width&&image.height){width=image.width;height=image.height;extension=image.format==='jpeg'?'jpg':image.format||'png';mediaType=`image/${image.format==='jpeg'?'jpeg':extension}`}
          else {
            const info=await probe(path),video=info.streams.find((s:any)=>s.codec_type==='video'),audio=info.streams.find((s:any)=>s.codec_type==='audio')
            invariant(video||audio,`Output ${name} 无法识别媒体类型`)
            const seconds=Number(video?.duration||audio?.duration||info.format.duration);invariant(seconds>0&&seconds<=config.maxFrames/24,'导出超过项目时长上限')
            if(video){
              const [numerator,denominator]=String(video.avg_frame_rate).split('/').map(Number);fps=numerator!/denominator!;invariant(Number.isFinite(fps)&&fps>0,'导出帧率无效')
              frames=Number(video.nb_frames||Math.round(seconds*fps));width=video.width;height=video.height;extension='mp4';mediaType='video/mp4'
              output=resolve(directory,`video-${index}-${part}.mp4`)
              await command(ffmpegPath,['-y','-i',path,'-map','0:v:0','-map','0:a?','-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p','-c:a','aac','-ar','48000','-ac','2','-movflags','+faststart',output])
              const exported=await probe(output),actual=exported.streams.find((s:any)=>s.codec_type==='video');invariant(Number(actual.nb_frames)===frames,'工程导出改变了原生帧数')
              const sound=exported.streams.find((s:any)=>s.codec_type==='audio');if(sound){invariant(Math.abs(Number(sound.start_time||0)-Number(actual.start_time||0))<=1/fps,'音画起点偏移超过一帧');invariant(Math.abs(Number(sound.duration)-Number(actual.duration))<=1/fps,'音频尾部与画面不一致')}
              const poster=resolve(directory,`poster-${index}-${part}.jpg`);await command(ffmpegPath,['-y','-i',output,'-frames:v','1','-vf','scale=480:-1',poster]);posterKey=await storeObject(actor,project,poster,'jpg')
            }else{frames=Math.ceil(seconds*24);extension='wav';mediaType='audio/wav';output=resolve(directory,`audio-${index}-${part}.wav`);await command(ffmpegPath,['-y','-i',path,'-map','0:a:0','-c:a','pcm_s16le','-ar','48000','-ac','2',output])}
          }
        }
        const objectKey=await storeObject(actor,project,output,extension)
        artifacts.push({name,part,relativePath:composite?relative(destination,path).replaceAll('\\','/'):undefined,kind:receipt.kind||receipt.export?.kind,mediaType,objectKey,posterKey,frames,fps,width,height,size:(await stat(output)).size,path:output})
      }
    }
    const primary=artifacts.find(a=>a.kind==='resource'&&a.mediaType==='video/mp4'),result={...primary,artifacts,buildId,revision}
    await stepUpdate(runId,'render',{status:'done',buildId,result});return result
  }
  const intermediate=resolve(directory,`hypit-${randomUUID()}.mp4`)
  await hypit(['get',buildId!,'--output','final.video','--to',intermediate],workspace,resolve(workspace,`${runId}.get.log`))
  const output=resolve(directory,'final.mp4'),frames=duration(readState(source))
  // AAC/H.264 export settings are explicit; no implicit duration or audio normalization.
  await command(ffmpegPath,['-y','-i',intermediate,'-map','0:v:0','-map','0:a?','-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p','-r','24','-frames:v',String(frames),'-c:a','aac','-ar','48000','-ac','2','-movflags','+faststart',output])
  const info=await probe(output),video=info.streams.find((s:any)=>s.codec_type==='video')
  invariant(Number(video.nb_frames)===frames,'导出帧数与项目时钟不一致')
  invariant(Math.abs(Number(video.duration)-frames/24)<=1/24,'导出时长与项目时钟不一致')
  const audio=info.streams.find((s:any)=>s.codec_type==='audio')
  if(audio){invariant(Math.abs(Number(audio.start_time||0)-Number(video.start_time||0))<=1/24,'音画起点偏移超过一帧');invariant(Math.abs(Number(audio.duration)-frames/24)<=1/24,'音频尾部与画面不一致')}
  const poster=resolve(directory,'poster.jpg');await command(ffmpegPath,['-y','-i',output,'-frames:v','1','-vf','scale=480:-1',poster])
  const objectKey=await storeObject(actor,project,output,'mp4'),posterKey=await storeObject(actor,project,poster,'jpg')
  const result={objectKey,posterKey,frames,width:video.width,height:video.height,size:(await stat(output)).size,buildId,revision,path:output}
  await stepUpdate(runId,'render',{status:'done',buildId,result})
  return result
}

