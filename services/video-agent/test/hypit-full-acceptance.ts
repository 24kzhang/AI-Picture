import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {root,dataRoot} from '../src/config.js'
import {probe} from '../src/assets.js'
const directory=resolve(root,'runtime-data/video-acceptance'),users=JSON.parse(await readFile(resolve(directory,'accounts.json'),'utf8')),cookie=users[0].cookie
const report:any={startedAt:new Date().toISOString(),cases:[]},save=()=>writeFile(resolve(directory,'hypit-full-report.json'),JSON.stringify(report,null,2),'utf8')
async function request(path:string,body?:any,method?:string){const response=await fetch('http://127.0.0.1:8091/api/video-editor'+path,{method:method||(body?'POST':'GET'),headers:{Cookie:cookie,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined});return {response,value:await response.json() as any}}
async function api(path:string,body?:any,method?:string){const {response,value}=await request(path,body,method);assert.ok(response.ok,JSON.stringify(value));return value}
const project=await api('/projects',{name:'完整 Hypit · 森林街景真实场景验收'}),sessionId=randomUUID(),held=await api(`/projects/${project.id}/lease`,{action:'acquire',sessionId});let revision=1
report.projectId=project.id
const context=()=>({expectedRevision:revision,sessionId,epoch:held.epoch,idempotencyKey:randomUUID()}),renewal=setInterval(()=>api(`/projects/${project.id}/lease`,{action:'renew',sessionId,epoch:held.epoch}).catch(()=>{}),20000)
async function wait(run:any){report.cases.push({runId:run.id,kind:run.kind});await save();for(let n=0;n<900;n++){const fresh=await api(`/runs/${run.id}`);if(!['queued','running'].includes(fresh.status)){Object.assign(report.cases.at(-1),{status:fresh.status,result:fresh.result,error:fresh.error});await save();assert.equal(fresh.status,'done',fresh.error);return fresh}await new Promise(r=>setTimeout(r,2000))}throw Error('任务等待超时')}
try{
  for(const [id,name] of [['99fe9dc9-aa21-4e02-ad63-957326366db8','森林溪流实景.mp4'],['e5ab8998-ac0b-493e-b2b0-2bf41c6b6e7d','城市人行道实景.mp4']]){const form=new FormData();form.append('file',new Blob([new Uint8Array(await readFile(resolve(dataRoot,'assets',id!,'video.mp4')))]),name!);await api(`/projects/${project.id}/assets`,form)}
  for(const template of ['travel','split-screen','portrait']){
    const result=await api(`/projects/${project.id}/hypit/template`,{...context(),template});revision=result.revision
    const check=await api(`/projects/${project.id}/sources/check`,{source:result.source});assert.ok(check)
    const run=await wait(await api(`/projects/${project.id}/exports`,context())),output=run.result
    assert.ok(output.artifacts.length);assert.equal(output.artifacts[0].mediaType,'video/mp4')
    const info=await probe(resolve(dataRoot,'exports',run.id,'video-0-0.mp4')),video=info.streams.find((s:any)=>s.codec_type==='video')
    assert.equal(Number(video.nb_frames),template==='travel'?192:96);assert.equal(video.width,template==='portrait'?720:1280)
    console.log(`真实 ${template} 工程渲染通过：${video.width}×${video.height} ${video.nb_frames}帧`)
  }
  const before=await api(`/projects/${project.id}/sources`),bundle=before.source,files={...bundle.files,'章节/配色.svs':'<?svml using="@hypit/svs@1"?><sheet version="1">text.extra { fill: #ffffff; }</sheet>'}
  files['main.svml']=files['main.svml'].replace('<svml>','<svml><import as="extra" source="./章节/配色.svs"/>')
  const ctx=context(),saved=await api(`/projects/${project.id}/sources`,{...ctx,source:{...bundle,files}},'PUT');revision=saved.revision
  const duplicate=await api(`/projects/${project.id}/sources`,{...ctx,source:{...bundle,files}},'PUT');assert.equal(duplicate.revision,revision)
  const invalid=await request(`/projects/${project.id}/sources`,{...context(),source:{...bundle,files:{...files,'main.svml':files['main.svml'].replace('</svml>','<unknown:Broken/></svml>')}}},'PUT');assert.equal(invalid.response.status,422);assert.equal((await api(`/projects/${project.id}/sources`)).revision,revision)
  const traversal=await request(`/projects/${project.id}/sources`,{...context(),source:{...bundle,files:{...files,'../private.json':'{}'}}},'PUT');assert.equal(traversal.response.status,400)
  const restored=await api(`/projects/${project.id}/restore`,{...context(),revision:before.revision});revision=restored.revision;assert.equal(restored.mode,'hypit');assert.ok(!restored.source.files['章节/配色.svs'])
  const restoredAgain=await api(`/projects/${project.id}/restore`,{...context(),revision:saved.revision});revision=restoredAgain.revision;assert.equal(restoredAgain.source.files['章节/配色.svs'],files['章节/配色.svs'])
  report.source={revision,duplicateRevision:duplicate.revision,invalidStatus:invalid.response.status,traversalStatus:traversal.response.status,nestedFileRestored:true};await save()
  const current=await api(`/projects/${project.id}/sources`),multi={...current.source,files:{...current.source.files,'main.svml':current.source.files['main.svml'].replace('</svml>','<render:Video id="preview" composition={main.composition} timeline={program.timeline} start-frame="0" end-frame-exclusive="48"/></svml>'),'build.svrun':current.source.files['build.svrun'].replace('</svrun>','<target output="preview.video"/></svrun>')}}
  const update=await api(`/projects/${project.id}/sources`,{...context(),source:multi},'PUT');revision=update.revision
  const multiple=await wait(await api(`/projects/${project.id}/exports`,context()));assert.equal(multiple.result.artifacts.length,2);report.multiOutput=multiple.result
  const agent=await wait(await api(`/projects/${project.id}/messages`,{...context(),message:'读取完整源工程，把当前中文标题改成“森林与城市 · 完整工程验收”，保持其他编排、素材、时序、字幕位置不变。通过 update_sources 保存，不要导出，不要生成。',selection:{}}));assert.equal(agent.result.applied,true);revision=agent.result.revision;const agentSource=await api(`/projects/${project.id}/sources`);assert.ok(agentSource.source.files['main.svml'].includes('森林与城市'));report.agent=agent.result
  report.completedAt=new Date().toISOString();await save();console.log('源工程、多文件恢复、编译原子回滚、多 Output 和真实规划模型验收通过')
}catch(error){report.error=(error as Error).message;await save();throw error}
finally{clearInterval(renewal);await api(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:held.epoch}).catch(()=>{})}
