/** Actual moving forest and human narration in VFR, rotated MOV and delayed-audio containers. */
import assert from 'node:assert/strict'
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root,dataRoot} from '../src/config.js'
import {db} from '../src/db.js'
import {command,ffmpegPath} from '../src/hypit.js'
import {probe} from '../src/assets.js'
const dir=resolve(root,'runtime-data/video-acceptance'),fixtures=resolve(dir,'continuous-timing'),cookie=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8'))[0].cookie,checks:string[]=[]
await mkdir(fixtures,{recursive:true})
async function api(path:string,body?:any){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.ok(response.ok,JSON.stringify(value));return value}
const p=await api('/projects',{name:'持续回归 · 变帧率、旋转与音频延迟'}),sessionId=randomUUID(),held=await api(`/projects/${p.id}/lease`,{action:'acquire',sessionId});let revision=1
const ctx=()=>({expectedRevision:revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()}),renew=setInterval(()=>void api(`/projects/${p.id}/lease`,{action:'renew',sessionId,epoch:held.epoch}).catch(()=>{}),20000),report:any={projectId:p.id,checks}
async function upload(path:string,name:string){const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(path))]),name);return api(`/projects/${p.id}/assets`,form)}
async function operate(operations:any[]){const v=await api(`/projects/${p.id}/operations`,{...ctx(),operations});revision=v.revision;return v.state}
async function wait(id:string){for(let n=0;n<300;n++){const run=await api(`/runs/${id}`);if(!['running','queued'].includes(run.status)){assert.equal(run.status,'done',run.error);return run}await new Promise(r=>setTimeout(r,1000))}throw Error('导出超时')}
try{
  const forest=resolve(dataRoot,'assets/99fe9dc9-aa21-4e02-ad63-957326366db8/video.mp4'),voice=resolve(dataRoot,'assets/1d44a615-f5ed-4eca-9fbb-5e63da55dc45/audio.wav'),vfr=resolve(fixtures,'forest-vfr.mp4'),rotated=resolve(fixtures,'forest-rotated.mov'),delayed=resolve(fixtures,'forest-delayed-voice.mp4')
  await command(ffmpegPath,['-y','-i',forest,'-vf',"select=lt(n\\,24)+gte(n\\,24)*not(mod(n\\,2))",'-fps_mode','vfr','-an','-c:v','libx264','-crf','18',vfr])
  await command(ffmpegPath,['-y','-display_rotation','90','-i',forest,'-c','copy',rotated])
  await command(ffmpegPath,['-y','-i',forest,'-itsoffset','1','-i',voice,'-map','0:v:0','-map','1:a:0','-c:v','copy','-c:a','aac',delayed])
  const vfrInput=await probe(vfr);assert.notEqual(vfrInput.streams[0].avg_frame_rate,vfrInput.streams[0].r_frame_rate)
  assert.equal(Math.abs((await probe(rotated)).streams[0].side_data_list.find((s:any)=>s.side_data_type==='Display Matrix').rotation),90)
  const imported=[];for(const [file,name] of [[vfr,'变帧率森林.mp4'],[rotated,'手机旋转森林.mov'],[delayed,'延迟口播森林.mp4']])imported.push(await upload(file!,name!))
  const [v,r,d]=imported;assert.equal((await probe(resolve(dataRoot,'assets',v.id,'video.mp4'))).streams[0].avg_frame_rate,'24/1');assert.ok(Math.abs(v.frames-97)<=1);checks.push('real VFR footage normalized to 24 fps with source duration retained')
  assert.equal(r.width,512);assert.equal(r.height,768);checks.push('MOV display rotation applied to normalized portrait pixels')
  const pcm=await readFile(resolve(dataRoot,'assets',d.id,'source.wav'));const start=pcm.indexOf(Buffer.from('data'))+8;assert.ok(start>=8);let firstEnergy=0,afterEnergy=0;for(let n=0;n<48000*2;n++){const value=pcm.readInt16LE(start+n*2);if(n<45000*2)firstEnergy+=value**2;else afterEnergy+=value**2}assert.ok(firstEnergy<afterEnergy*.001);checks.push('delayed narration retains initial silence rather than shifting voice to frame zero')
  const state=await operate([{type:'insert_clip',assetId:v.id,trackId:'visual',at:0,length:96},{type:'insert_clip',text:'冻结版本 · 原标题',trackId:'titles',at:0,length:96}]),title=state.clips.find((c:any)=>c.kind==='text')
  const request=ctx(),run=await api(`/projects/${p.id}/exports`,request),duplicate=await api(`/projects/${p.id}/exports`,request);assert.equal(run.id,duplicate.id);checks.push('repeated export key produces one export run')
  await operate([{type:'edit_subtitles',clipId:title.id,text:'导出期间修改后的标题'}])
  const done=await wait(run.id);assert.equal(done.result.revision,request.expectedRevision);const result=await probe(resolve(dataRoot,'exports',run.id,'final.mp4'));assert.equal(Number(result.streams.find((s:any)=>s.codec_type==='video').nb_frames),96);checks.push('editing during export preserves frozen revision and exact 96-frame output')
  report.assets=imported;report.runId=run.id;report.exportRevision=done.result.revision;report.currentRevision=revision;report.completedAt=new Date().toISOString();await writeFile(resolve(dir,'continuous-timing-report.json'),JSON.stringify(report,null,2),'utf8');console.log('真实时间与冻结导出验收通过',checks.length,p.id)
}catch(error){report.error=(error as Error).message;await writeFile(resolve(dir,'continuous-timing-report.json'),JSON.stringify(report,null,2),'utf8');throw error}
finally{clearInterval(renew);await api(`/projects/${p.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{});await db.end()}
