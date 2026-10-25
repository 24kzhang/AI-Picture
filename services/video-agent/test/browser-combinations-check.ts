/** API state assertions complement actual clicks in the authorized in-app browser. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {root} from '../src/config.js'
const dir=resolve(root,'runtime-data/video-acceptance'),cookie=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8'))[0].cookie,combination=JSON.parse(await readFile(resolve(dir,'combinations-report.json'),'utf8')),file=resolve(dir,'combinations-browser-report.json'),phase=process.argv[2]
const response=await fetch(`http://127.0.0.1:8091/api/video-editor/projects/${combination.projectId}`,{headers:{Cookie:cookie}});assert.ok(response.ok);const view=await response.json() as any
const report:any=phase==='baseline'?{projectId:combination.projectId,baseline:view.state,baselineRevision:view.revision,checks:[]}:JSON.parse(await readFile(file,'utf8'))
const picture=report.baseline.clips.find((c:any)=>c.kind==='video'&&c.at===24),caption=report.baseline.clips.find((c:any)=>c.sourceClipId===picture.id),original=report.baseline.clips.find((c:any)=>c.trackId==='original'&&c.assetId===picture.assetId)
if(phase==='moved'){
  assert.equal(view.state.clips.find((c:any)=>c.id===picture.id).at,36);assert.equal(view.state.clips.find((c:any)=>c.id===caption.id).at,36);assert.equal(view.state.clips.find((c:any)=>c.id===original.id).at,0)
  assert.deepEqual(view.state.clips.filter((c:any)=>c.id!==picture.id&&c.id!==caption.id),report.baseline.clips.filter((c:any)=>c.id!==picture.id&&c.id!==caption.id));report.checks.push('browser property apply moves detached picture + source caption, preserves original and all other clips')
}
if(phase==='undo'){assert.deepEqual(view.state,report.baseline);report.checks.push('browser undo restores the complete state')}
if(phase==='transcribed'){
  assert.deepEqual(view.state.clips.filter((c:any)=>c.kind!=='text'),report.baseline.clips.filter((c:any)=>c.kind!=='text'))
  const speech=report.baseline.clips.find((c:any)=>c.trackId==='voice'),before=report.baseline.clips.filter((c:any)=>c.sourceClipId===speech.id),after=view.state.clips.filter((c:any)=>c.sourceClipId===speech.id);assert.equal(after.length,before.length)
  const run=view.runs.find((r:any)=>r.kind==='agent'&&r.status==='done'),result=typeof run.result==='string'?JSON.parse(run.result):run.result;assert.ok(result.revision>report.baselineRevision);assert.match(result.answer,/已保存项目/);assert.match(result.answer,/中文字幕转写/);assert.doesNotMatch(result.answer,/无法完成|请确认/)
  report.agent={runId:run.id,revision:result.revision,answer:result.answer};report.checks.push('browser subtitle button performs real ASR replacement and displays the persisted execution receipt')
}
if(phase==='corrected'){
  assert.deepEqual(view.state.clips.filter((c:any)=>c.kind!=='text'),report.baseline.clips.filter((c:any)=>c.kind!=='text'))
  const speech=report.baseline.clips.find((c:any)=>c.trackId==='voice'),captions=view.state.clips.filter((c:any)=>c.sourceClipId===speech.id)
  assert.ok(captions.some((c:any)=>c.text==='转过街角，感受城市集市的热闹。'&&c.at===64&&c.length===56));assert.ok(captions.some((c:any)=>c.text==='最后，在咖啡香中，回味这段旅程。'&&c.at===136&&c.length===53))
  assert.deepEqual(view.state.clips.filter((c:any)=>c.subtitle&&c.sourceClipId!==speech.id),report.baseline.clips.filter((c:any)=>c.subtitle&&c.sourceClipId!==speech.id));report.checks.push('overlapping caption mouse/keyboard selection and manual ASR correction preserve all timings, source anchors and other clips')
}
if(phase==='exported'){
  const run=view.runs.find((r:any)=>r.kind==='export'&&r.status==='done'),result=typeof run.result==='string'?JSON.parse(run.result):run.result;assert.equal(result.revision,view.revision);assert.equal(result.frames,240);report.export={runId:run.id,revision:result.revision,frames:result.frames};report.checks.push('browser export contains the manually corrected caption version')
}
report.currentRevision=view.revision;report.updatedAt=new Date().toISOString();await writeFile(file,JSON.stringify(report,null,2),'utf8');console.log('浏览器操作状态校验通过',phase,view.revision)
