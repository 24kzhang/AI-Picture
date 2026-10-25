import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {root,dataRoot} from '../src/config.js'
import {probe} from '../src/assets.js'
const directory=resolve(root,'runtime-data/video-acceptance'),cookie=JSON.parse(await readFile(resolve(directory,'accounts.json'),'utf8'))[0].cookie
async function api(path:string){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{headers:{Cookie:cookie}});assert.equal(response.status,200);return response.json() as Promise<any>}
// These IDs belong to the acceptance account. Browser actions are performed
// separately in the actual UI; this script verifies their persisted results.
const id='2449cb8e-b122-40cb-8bc7-6143ccf34fbf',source=await api(`/projects/${id}/sources`)
assert.equal(source.revision,15);assert.equal(source.source.mode,'hypit')
assert.ok(source.source.files['章节/配色.svs'])
assert.match(source.source.files['main.svml'],/id="frame-15f7b6f6-9e67-4c24-8fa3-bddc54a4e429"[^>]+x="361px"[^>]+y="641px"/)
assert.match(source.source.files['main.svml'],/原生源码编辑 · 真实旅程/)
assert.ok(source.source.feedback.comments.some((c:any)=>c.text==='真实森林预览、源码保存与评论恢复已验证；接下来检查城市转场。'&&!c.resolved))
const composite=JSON.parse(await readFile(resolve(directory,'hypit-composite-report.json'),'utf8')),video=await api(`/videos/${composite.gallery.id}`)
assert.equal(video.metadata.fps,30);assert.equal(video.metadata.frames,96)
const components=JSON.parse(await readFile(resolve(directory,'hypit-components-report.json'),'utf8')),semantic=components.cases.find((c:any)=>c.template==='semantic'),info=await probe(resolve(dataRoot,'exports',semantic.runId,'video-0-0.mp4'))
const visual=info.streams.find((s:any)=>s.codec_type==='video'),audio=info.streams.find((s:any)=>s.codec_type==='audio')
assert.equal(Number(visual.nb_frames),200);assert.equal(audio.sample_rate,'48000');assert.equal(audio.channels,2)
const difference=Math.abs(Number(visual.duration)-Number(audio.duration));assert.ok(difference<=1/24)
await writeFile(resolve(directory,'hypit-browser-state-report.json'),JSON.stringify({projectId:id,revision:source.revision,sourceSaved:true,nestedSourcePreserved:true,commentsRestored:true,layout:{x:361,y:641},gallery:{id:video.id,metadata:video.metadata},semantic:{runId:semantic.runId,frames:Number(visual.nb_frames),audioVideoDurationDifference:difference},checkedAt:new Date().toISOString()},null,2),'utf8')
console.log('浏览器源码、评论和连续属性修改的持久化结果，以及30fps图库和字幕成片时序通过')
