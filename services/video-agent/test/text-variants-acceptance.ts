import assert from 'node:assert/strict'
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import sharp from 'sharp'
import {root,dataRoot} from '../src/config.js'
import {db} from '../src/db.js'
import {command,ffmpegPath} from '../src/hypit.js'
import {probe} from '../src/assets.js'
const dir=resolve(root,'runtime-data/video-acceptance'),frames=resolve(dir,'continuous-text-variants'),cookie=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8'))[0].cookie
await mkdir(frames,{recursive:true})
async function api(path:string,body?:any){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const v=await response.json() as any;assert.ok(response.ok,JSON.stringify(v));return v}
const p=await api('/projects',{name:'持续回归 · 竖屏空行与长文字'}),sessionId=randomUUID(),held=await api(`/projects/${p.id}/lease`,{action:'acquire',sessionId});let revision=1
const ctx=()=>({expectedRevision:revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()}),renew=setInterval(()=>void api(`/projects/${p.id}/lease`,{action:'renew',sessionId,epoch:held.epoch}).catch(()=>{}),20000),report:any={projectId:p.id,checks:[]}
async function operate(operations:any[]){const v=await api(`/projects/${p.id}/operations`,{...ctx(),operations});revision=v.revision;return v.state}
try{
  const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(resolve(dataRoot,'assets/99fe9dc9-aa21-4e02-ad63-957326366db8/video.mp4')))]),'真实森林.mp4');const forest=await api(`/projects/${p.id}/assets`,form)
  let state=await operate([{type:'set_layout',width:720,height:1280},{type:'insert_clip',assetId:forest.id,trackId:'visual',at:0,length:48},{type:'insert_clip',trackId:'titles',text:'森林清晨\r\n\r\n<旅程> & 留念\r\n\r\n咖啡时光',at:0,length:24},{type:'insert_clip',trackId:'titles',text:'沿着森林溪流前行，穿过清晨的街巷，坐在咖啡馆窗边，看路人慢慢走过。'.repeat(4),at:24,length:24}])
  const titles=state.clips.filter((c:any)=>c.kind==='text');state=await operate(titles.map((c:any)=>({type:'edit_subtitles',clipId:c.id,fontSize:32,color:'#ff00ff'})))
  const run=await api(`/projects/${p.id}/exports`,ctx());let done:any
  for(let n=0;n<300;n++){done=await api(`/runs/${run.id}`);if(!['running','queued'].includes(done.status))break;await new Promise(r=>setTimeout(r,1000))}assert.equal(done.status,'done',done.error)
  const file=resolve(dataRoot,'exports',run.id,'final.mp4'),info=await probe(file),video=info.streams.find((s:any)=>s.codec_type==='video');assert.equal(video.width,720);assert.equal(video.height,1280);assert.equal(Number(video.nb_frames),48)
  const measurements=[]
  for(const frame of [12,36]){
    const path=resolve(frames,`output-${frame}.png`);await command(ffmpegPath,['-y','-i',file,'-vf',`select=eq(n\\,${frame})`,'-vsync','0','-frames:v','1',path]);const bytes=await sharp(path).removeAlpha().raw().toBuffer(),rows:number[]=[]
    for(let y=0;y<1280;y++){let count=0;for(let x=20;x<700;x++){const i=(y*720+x)*3;if(bytes[i]!>170&&bytes[i+2]!>170&&bytes[i+1]!<110)count++}if(count>10)rows.push(y)}
    assert.ok(rows.length>40,'导出文字缺失');assert.ok(Math.abs((rows[0]!+rows.at(-1)!)/2-640)<15,'竖屏文字垂直居中错误')
    if(frame===12){assert.ok(rows.some(y=>y>540&&y<590));assert.ok(rows.some(y=>y>620&&y<660));assert.ok(rows.some(y=>y>690&&y<740));assert.ok(!rows.some(y=>y>595&&y<610));assert.ok(!rows.some(y=>y>670&&y<680));report.checks.push('CRLF, literal markup characters and two authored blank lines retained')}
    else{assert.ok(rows.at(-1)!-rows[0]!>150,'长中文未换行');report.checks.push('long Chinese text wraps inside portrait canvas and remains centered')}
    measurements.push({frame,firstRow:rows[0],lastRow:rows.at(-1),paintedRows:rows.length,path})
  }
  report.checks.push('portrait render is exactly 720×1280 and 48 frames');report.runId=run.id;report.state=state;report.measurements=measurements;report.completedAt=new Date().toISOString();await writeFile(resolve(dir,'continuous-text-variants-report.json'),JSON.stringify(report,null,2),'utf8');console.log('真实竖屏文字验收通过',report.checks.length,p.id)
}catch(error){report.error=(error as Error).message;await writeFile(resolve(dir,'continuous-text-variants-report.json'),JSON.stringify(report,null,2),'utf8');throw error}
finally{clearInterval(renew);await api(`/projects/${p.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{});await db.end()}
