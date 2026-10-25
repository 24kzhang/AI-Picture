/** Explicit maintenance for the acceptance user's own old imports; never rewrites source revisions. */
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {root,dataRoot} from '../src/config.js'
import {rows,db} from '../src/db.js'
import {authorize} from '../src/auth.js'
import {repairVideoPlayback,projectAssets,probe} from '../src/assets.js'
import {project,revision} from '../src/projects.js'
const dir=resolve(root,'runtime-data/video-acceptance'),accounts=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8')),cookie=accounts[0].cookie
const projects=await rows('SELECT id FROM video_project WHERE owner_id=?',[accounts[0].id]),report:any={startedAt:new Date().toISOString(),projects:[],checks:['immutable source and media keys retained','browser rendition and formal imported video duration corrected']}
try{
  for(const row of projects){
    const value=await project(row.id),actor=await authorize({headers:{cookie}} as any,value,'edit'),before=await revision(row.id),prior=await projectAssets(row.id,actor,value)
    const repaired=await repairVideoPlayback(value,actor)
    if(!repaired.length)continue
    const current=await revision(row.id),assets=await projectAssets(row.id,actor,value)
    assert.equal(current.revision,before.revision);assert.deepEqual(current.source,before.source)
    for(const id of repaired){const asset=assets.find(a=>a.id===id)!,old=prior.find(a=>a.id===id)!;assert.equal(asset.objectKey,old.objectKey);assert.equal(asset.audioKey,old.audioKey);assert.equal(asset.path,old.path);assert.ok(asset.browserObjectKey);const info=await probe(resolve(dataRoot,'assets',id,'browser.mp4'));assert.ok(Math.abs(Number(info.format.duration)-asset.frames/24)<=1/24)}
    report.projects.push({id:row.id,revision:current.revision,repaired})
  }
  report.completedAt=new Date().toISOString();await writeFile(resolve(dir,'repair-playback-report.json'),JSON.stringify(report,null,2),'utf8');console.log('旧素材浏览器播放版本修复通过',report.projects.length,'个验收项目；源版本和渲染素材保持原样')
}finally{await db.end()}
