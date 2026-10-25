/** Probe and compare actual exported scene frames/audio against its immutable source revision. */
import assert from 'node:assert/strict'
import {execFileSync} from 'node:child_process'
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {resolve} from 'node:path'
import sharp from 'sharp'
import {root,dataRoot} from '../src/config.js'
import {revision} from '../src/projects.js'
import {projectAssets,probe} from '../src/assets.js'
import {command,ffmpegPath} from '../src/hypit.js'
import {db} from '../src/db.js'
const dir=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8'))
const projectId=process.env.VIDEO_ACCEPTANCE_PROJECT_ID||'46edc7a4-dc61-4192-a4e4-b4978da204c7'
const api=async(path:string)=>{const r=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{headers:{Cookie:users[0].cookie}});assert.ok(r.ok);return r.json() as Promise<any>}
try{
 const current=await api(`/projects/${projectId}`),runId=process.env.VIDEO_ACCEPTANCE_RUN_ID||current.runs.find((r:any)=>r.kind==='export'&&r.status==='done')?.id
 assert.ok(runId,'尚无成功导出');const run=await api(`/runs/${runId}`),saved=await revision(projectId,run.result.revision),state=saved.state,assets=await projectAssets(projectId)
 const output=resolve(dataRoot,'exports',runId,'final.mp4'),info=await probe(output),video=info.streams.find((s:any)=>s.codec_type==='video'),audio=info.streams.find((s:any)=>s.codec_type==='audio')
 assert.equal(video.width,state.width);assert.equal(video.height,state.height);assert.equal(Number(video.nb_frames),run.result.frames);assert.equal(video.codec_name,'h264');assert.equal(video.pix_fmt,'yuv420p');assert.equal(audio.codec_name,'aac');assert.equal(audio.sample_rate,'48000');assert.equal(audio.channels,2)
 assert.ok(Math.abs(Number(audio.duration)-Number(video.duration))<=1/24)
 const evidence=resolve(dir,'scene-frames',runId);await mkdir(evidence,{recursive:true});const frames:any[]=[]
 const checkpoints=process.env.VIDEO_ACCEPTANCE_FRAMES?process.env.VIDEO_ACCEPTANCE_FRAMES.split(',').map(Number):[0,35,36,71,72,143,144,180,199]
 for(const frame of checkpoints){
  assert.ok(Number.isInteger(frame)&&frame>=0&&frame<run.result.frames,'抽帧检查点必须位于成片内')
  const clip=state.clips.filter(c=>c.kind==='image'||c.kind==='video').filter(c=>frame>=c.at&&frame<c.at+c.length).sort((a,b)=>state.tracks.findIndex(t=>t.id===b.trackId)-state.tracks.findIndex(t=>t.id===a.trackId))[0]!
  assert.ok(clip,`第${frame}帧有画面`);const asset=assets.find(a=>a.id===clip.assetId)!,actual=resolve(evidence,`output-${frame}.png`),reference=resolve(evidence,`source-${frame}.png`)
  await command(ffmpegPath,['-hide_banner','-loglevel','error','-y','-i',output,'-vf',`select=eq(n\\,${frame})`,'-vsync','0','-frames:v','1',actual])
  if(clip.kind==='video')await command(ffmpegPath,['-hide_banner','-loglevel','error','-y','-i',asset.path,'-vf',`select=eq(n\\,${clip.in+frame-clip.at})`,'-vsync','0','-frames:v','1',reference])
  else await sharp(asset.path).png().toFile(reference)
  // Compare the central scene region; titles/subtitles are checked separately from source-frame identity.
  const crop={left:Math.round(state.width*.28),top:Math.round(state.height*.40),width:Math.round(state.width*.44),height:Math.round(state.height*.20)}
  const a=await sharp(actual).removeAlpha().extract(crop).raw().toBuffer(),b=await sharp(reference).resize(state.width,state.height,{fit:clip.fit==='stretch'?'fill':clip.fit,background:'#000000'}).removeAlpha().extract(crop).raw().toBuffer()
  let mse=0;for(let n=0;n<a.length;n++)mse+=(a[n]!-b[n]!)**2;mse/=a.length;const psnr=10*Math.log10(255**2/Math.max(mse,1e-6));assert.ok(psnr>22,`第${frame}帧素材入点错误或画面不同，PSNR=${psnr}`)
  frames.push({frame,sourceFrame:clip.in+frame-clip.at,assetId:asset.id,psnr,path:actual})
 }
 const tracks=state.clips.filter(c=>c.kind==='audio'&&!c.muted&&c.volume>0),filter:string[]=[],args=['-y','-hide_banner','-loglevel','error']
 for(const c of tracks){const asset=assets.find(a=>a.id===c.assetId)!;args.push('-i',asset.audioPath||asset.path)}
 tracks.forEach((c,n)=>filter.push(`[${n}:a]atrim=start=${c.in/24}:duration=${c.length/24},asetpts=PTS-STARTPTS,volume=${c.volume}${c.fadeIn?`,afade=t=in:st=0:d=${c.fadeIn/24}`:''}${c.fadeOut?`,afade=t=out:st=${(c.length-c.fadeOut)/24}:d=${c.fadeOut/24}`:''},adelay=${Math.round(c.at/24*1000)}|${Math.round(c.at/24*1000)}[a${n}]`))
 filter.push(`${tracks.map((_,n)=>`[a${n}]`).join('')}amix=inputs=${tracks.length}:normalize=0,apad,atrim=duration=${run.result.frames/24}[mix]`)
 const wave=resolve(evidence,'expected-mix.wav');await command(ffmpegPath,[...args,'-filter_complex',filter.join(';'),'-map','[mix]','-ac','2','-ar','48000','-c:a','pcm_s16le',wave])
 function pcm(path:string){const bytes=execFileSync(ffmpegPath,['-v','error','-i',path,'-map','0:a:0','-ac','1','-ar','48000','-f','f32le','pipe:1'],{maxBuffer:32*1024**2});return new Float32Array(bytes.buffer,bytes.byteOffset,bytes.length/4)}
 const actual=pcm(output),expected=pcm(wave);let best={correlation:0,shiftSamples:0,gain:0}
 const sampleCount=Math.min(expected.length,actual.length),margin=Math.min(2000,Math.floor(sampleCount/10))
 for(let shift=Math.ceil(-margin/16)*16;shift<=margin;shift+=16){let ab=0,aa=0,bb=0;for(let n=margin;n<sampleCount-margin;n+=16){const a=actual[n+shift]!,b=expected[n]!;ab+=a*b;aa+=a*a;bb+=b*b}const correlation=ab/Math.sqrt(aa*bb);if(correlation>best.correlation)best={correlation,shiftSamples:shift,gain:ab/bb}}
 assert.ok(best.correlation>.97,`口播/音乐与预期不一致：${best.correlation}`);assert.ok(Math.abs(best.shiftSamples)<=2000,'音画误差超过一帧');assert.ok(Math.abs(best.gain-1)<.08,`音量与预期不符：${best.gain}`)
 const report={completedAt:new Date().toISOString(),projectId,runId,revision:saved.revision,currentRevision:current.revision,output,streams:info.streams,frames,audio:best}
 await writeFile(resolve(dir,process.env.VIDEO_ACCEPTANCE_REPORT||'scene-media-report.json'),JSON.stringify(report,null,2),'utf8');console.log(`真实场景成片通过：${frames.length}个边界/镜头帧、完整口播/混音、编码时长检查`,runId)
}finally{await db.end()}
