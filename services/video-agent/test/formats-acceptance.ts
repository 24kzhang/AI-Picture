/** Real forest footage / café photograph / Chinese speech in alternative containers. */
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {mkdir,readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import sharp from 'sharp'
import {root,dataRoot} from '../src/config.js'
import {db} from '../src/db.js'
import {command,ffmpegPath} from '../src/hypit.js'
import {probe} from '../src/assets.js'
const dir=resolve(root,'runtime-data/video-acceptance'),fixtures=resolve(dir,'formats');await mkdir(fixtures,{recursive:true})
const accounts=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8')),cookie=accounts[0].cookie
async function api(path:string,body?:any,expected=200){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:body?'POST':'GET',headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const value=await response.json() as any;assert.equal(response.status,expected,JSON.stringify(value));return value}
const project=await api('/projects',{name:'真实场景 · 不同媒体格式导入回归'}),sessionId=randomUUID(),lease=await api(`/projects/${project.id}/lease`,{action:'acquire',sessionId})
let revision=1
const context=()=>({expectedRevision:revision,sessionId,epoch:lease.epoch,idempotencyKey:randomUUID()})
const renew=setInterval(()=>void api(`/projects/${project.id}/lease`,{action:'renew',sessionId,epoch:lease.epoch}).catch(()=>{}),20_000)
const report:any={projectId:project.id,startedAt:new Date().toISOString(),checks:[],assets:{}}
const persist=()=>writeFile(resolve(dir,'formats-report.json'),JSON.stringify(report,null,2),'utf8')
async function upload(file:string,expected=200){const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(file))]),file.split(/[\\/]/).at(-1)!);return api(`/projects/${project.id}/assets`,form,expected)}
async function operate(operations:any[]){const result=await api(`/projects/${project.id}/operations`,{...context(),operations});revision=result.revision;return result.state}
async function waitRun(id:string){for(let n=0;n<400;n++){const run=await api(`/runs/${id}`);if(!['running','queued'].includes(run.status))return run;await new Promise(r=>setTimeout(r,1500))}throw Error('渲染超时')}
try{
  const cafe=resolve(dataRoot,'assets/c61d754f-1688-4c64-9c91-1b30057057e6/image.png'),forest=resolve(dataRoot,'assets/99fe9dc9-aa21-4e02-ad63-957326366db8/video.mp4'),voice=resolve(dataRoot,'assets/1d44a615-f5ed-4eca-9fbb-5e63da55dc45/audio.wav')
  const tiff=resolve(fixtures,'café.tiff');await sharp(cafe).tiff().toFile(tiff);report.assets.tiff=await upload(tiff);assert.equal(report.assets.tiff.kind,'image');assert.equal(report.assets.tiff.width,1024);report.checks.push('single-page TIFF imports as an image')
  const portrait=resolve(fixtures,'café-orientation6.jpg');await sharp(cafe).withMetadata({orientation:6}).jpeg().toFile(portrait);report.assets.portrait=await upload(portrait);assert.equal(report.assets.portrait.kind,'image');assert.equal(report.assets.portrait.width,768);assert.equal(report.assets.portrait.height,1024)
  const expectedPixels=await sharp(portrait).rotate().png().toBuffer(),actualPixels=await readFile(resolve(dataRoot,'assets',report.assets.portrait.id,'image.png'));assert.deepEqual(actualPixels,expectedPixels);report.checks.push('EXIF-rotated JPEG uses oriented pixels and portrait dimensions')
  const truncated=resolve(fixtures,'truncated-café.jpg');await writeFile(truncated,(await readFile(portrait)).subarray(0,2048));const beforeImages=(await api(`/projects/${project.id}/assets`)).length,truncatedResult=await upload(truncated,400);assert.equal(truncatedResult.code,'INVALID_MEDIA');assert.equal((await api(`/projects/${project.id}/assets`)).length,beforeImages);report.checks.push('JPEG with a readable header but corrupt pixel data gives a clean 400 without an asset')
  const gif=resolve(fixtures,'forest.gif');await command(ffmpegPath,['-y','-i',forest,'-t','1','-vf','fps=6,scale=320:-1','-an',gif]);report.assets.gif=await upload(gif);assert.equal(report.assets.gif.kind,'video');assert.equal(report.assets.gif.frames,24);report.checks.push('animated forest GIF becomes a 24-fps moving clip')
  const mp3=resolve(fixtures,'speech-with-café-cover.mp3');await command(ffmpegPath,['-y','-i',voice,'-i',cafe,'-map','0:a','-map','1:v','-c:a','libmp3lame','-ar','44100','-ac','1','-c:v','mjpeg','-id3v2_version','3','-disposition:v','attached_pic',mp3]);report.assets.mp3=await upload(mp3);assert.equal(report.assets.mp3.kind,'audio');const audio=(await probe(resolve(dataRoot,'assets',report.assets.mp3.id,'audio.wav'))).streams[0];assert.equal(audio.sample_rate,'48000');assert.equal(audio.channels,2);report.checks.push('44.1-kHz mono MP3 with cover art remains audio and normalizes to stereo 48 kHz')
  const before=(await api(`/projects/${project.id}/assets`)).length,broken=resolve(fixtures,'corrupt.mp4');await writeFile(broken,'damaged media','utf8');const rejected=await upload(broken,400);assert.equal(rejected.code,'INVALID_MEDIA');assert.equal((await api(`/projects/${project.id}/assets`)).length,before);report.checks.push('corrupt media gives a clear 400 and creates no asset record')
  const single=resolve(dataRoot,'exports/7a382f96-6214-4af9-a4b5-7203ea2e6837/final.mp4');report.assets.single=await upload(single);assert.equal(report.assets.single.frames,1);await operate([{type:'insert_clip',assetId:report.assets.single.id,trackId:'visual',at:0}]);const render=await api(`/projects/${project.id}/exports`,context()),done=await waitRun(render.id);report.singleExport={runId:render.id,status:done.status,error:done.error,result:done.result};await persist();assert.equal(done.status,'done',done.error);assert.equal(done.result.frames,1);report.checks.push('one-frame real-scene MP4 reimports and renders again as exactly one frame')
  report.completedAt=new Date().toISOString();await persist();console.log('真实媒体格式验收通过',project.id,report.checks.length)
}catch(error){report.error=(error as Error).message;await persist();throw error}
finally{clearInterval(renew);await api(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:lease.epoch}).catch(()=>{});await db.end()}
