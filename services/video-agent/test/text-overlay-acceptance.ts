/** Real moving footage + café overlay + multiline Chinese text, export and independent spatial checks. */
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {resolve} from 'node:path'
import sharp from 'sharp'
import {root,dataRoot} from '../src/config.js'
import {db} from '../src/db.js'
import {command,ffmpegPath} from '../src/hypit.js'
import {probe} from '../src/assets.js'
const dir=resolve(root,'runtime-data/video-acceptance'),cookie=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8'))[0].cookie
async function api(path:string,body?:any){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.ok(response.ok,JSON.stringify(value));return value}
const created=await api('/projects',{name:'持续回归 · 多行中文与真实画中画'}),sessionId=randomUUID(),held=await api(`/projects/${created.id}/lease`,{action:'acquire',sessionId});let revision=1
const context=()=>({expectedRevision:revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()}),report:any={startedAt:new Date().toISOString(),projectId:created.id,checks:[],exports:[]}
const save=()=>writeFile(resolve(dir,'continuous-text-report.json'),JSON.stringify(report,null,2),'utf8'),renew=setInterval(()=>void api(`/projects/${created.id}/lease`,{action:'renew',sessionId,epoch:held.epoch}).catch(()=>{}),20000)
async function upload(path:string,name:string){const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(path))]),name);return api(`/projects/${created.id}/assets`,form)}
async function operate(operations:any[]){const result=await api(`/projects/${created.id}/operations`,{...context(),operations});revision=result.revision;return result.state}
async function wait(id:string){for(let n=0;n<400;n++){const value=await api(`/runs/${id}`);if(!['queued','running'].includes(value.status)){assert.equal(value.status,'done',value.error);return value}await new Promise(r=>setTimeout(r,1500))}throw Error('导出等待超时')}
try{
  const forest=await upload(resolve(dataRoot,'assets/99fe9dc9-aa21-4e02-ad63-957326366db8/video.mp4'),'真实森林.mp4'),cafe=await upload(resolve(dataRoot,'assets/c61d754f-1688-4c64-9c91-1b30057057e6/image.png'),'咖啡馆摄影.png'),voice=await upload(resolve(dataRoot,'assets/1d44a615-f5ed-4eca-9fbb-5e63da55dc45/audio.wav'),'中文口播.wav')
  let state=await operate([{type:'insert_clip',assetId:forest.id,trackId:'visual',at:0,length:96},{type:'insert_clip',assetId:voice.id,trackId:'voice',at:0,length:96},{type:'add_track',kind:'visual',name:'画中画'}]);const overlayTrack=state.tracks.at(-1).id
  state=await operate([{type:'insert_clip',assetId:cafe.id,trackId:overlayTrack,at:24,length:48},{type:'insert_clip',text:'第一行：森林清晨\n第二行：街角旅途\n第三行：咖啡时光\n第四行：回味风景\n第五行：<旅程> & 留念',trackId:'titles',at:0,length:96}]);const overlay=state.clips.find((c:any)=>c.assetId===cafe.id),title=state.clips.find((c:any)=>c.kind==='text')
  state=await operate([{type:'set_layout',clipId:overlay.id,scale:.35,x:360,y:-180,fit:'cover'},{type:'edit_subtitles',clipId:title.id,y:70,fontSize:32}]);report.assets={forest,cafe,voice};report.titleId=title.id;report.overlayId=overlay.id;report.state=state;await save()
  const exportRun=await api(`/projects/${created.id}/exports`,context()),done=await wait(exportRun.id),file=resolve(dataRoot,'exports',exportRun.id,'final.mp4'),info=await probe(file);assert.equal(Number(info.streams.find((s:any)=>s.codec_type==='video').nb_frames),96);report.exports.push({id:exportRun.id,revision:done.result.revision,output:file})
  const frames=resolve(dir,'continuous-text-frames');await mkdir(frames,{recursive:true});for(const frame of [0,23,24,48,71,72,95])await command(ffmpegPath,['-y','-i',file,'-vf',`select=eq(n\\,${frame})`,'-vsync','0','-frames:v','1',resolve(frames,`output-${frame}.png`)])
  // Verify the independently resized photograph in a part of the overlay without text.
  const expected=await sharp(resolve(dataRoot,'assets',cafe.id,'image.png')).resize(448,252,{fit:'cover'}).removeAlpha().extract({left:90,top:35,width:120,height:80}).raw().toBuffer(),actual=await sharp(resolve(frames,'output-48.png')).removeAlpha().extract({left:866,top:89,width:120,height:80}).raw().toBuffer();let mse=0;for(let n=0;n<actual.length;n++)mse+=(actual[n]!-expected[n]!)**2;const psnr=10*Math.log10(255**2/(mse/actual.length));assert.ok(psnr>22,`画中画缩放/位置/适配不一致: ${psnr}`);report.overlayPsnr=psnr;report.checks.push('real café picture-in-picture matches independent position/scale/cover crop')
  const textPixels=await sharp(resolve(frames,'output-48.png')).removeAlpha().extract({left:500,top:300,width:240,height:260}).raw().toBuffer(),rows:number[]=[]
  for(let y=0;y<260;y++){let whites=0;for(let x=0;x<240;x++){const i=(y*240+x)*3,r=textPixels[i]!,g=textPixels[i+1]!,b=textPixels[i+2]!;if(Math.min(r,g,b)>220&&Math.max(r,g,b)-Math.min(r,g,b)<12)whites++}rows.push(whites)}
  // CJK glyphs have disconnected horizontal strokes. Test each independently
  // predicted line cell rather than mistaking glyph strokes for separate lines.
  report.textBands=Array.from({length:5},(_,line)=>{const center=430+(line-2)*32*1.2,start=Math.round(center-15),end=Math.round(center+15),pixels=rows.slice(start-300,end-300+1).reduce((a,b)=>a+b,0);assert.ok(pixels>200,`第${line+1}行在指定垂直位置缺失: ${pixels}`);return {center,start,end,pixels}});report.checks.push('five rendered Chinese lines remain centered at authored y=70')
  report.completedAt=new Date().toISOString();await save();console.log('真实画中画导出通过；待浏览器检查五行文字',created.id)
}catch(error){report.error=(error as Error).message;await save();throw error}
finally{clearInterval(renew);await api(`/projects/${created.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{});await db.end()}
