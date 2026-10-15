import {randomUUID,createHash} from 'node:crypto'
import {project,revision,assertWrite,type WriteContext} from './projects.js'
import {rows,transaction,json} from './db.js'
import {sourceFiles,normalizeSource} from './source-bundle.js'
import {sourceFor,readState,checkSource} from './hypit.js'
import type {Asset} from './timeline.js'
import type {Actor} from './auth.js'
import {ApiError} from './errors.js'

// Variants stay in the same storage/permission scope. Bytes are shared by digest;
// asset identities, revisions, feedback and edit leases are independent.
export async function cloneVariant(id:string,actor:Actor,context:WriteContext,assets:Asset[],name:string){
  const digest=createHash('sha256').update(JSON.stringify({actor:actor.userId,context,name,operation:'variant'})).digest('hex')
  const previous=(await rows('SELECT * FROM video_idempotency WHERE project_id=? AND request_key=?',[id,context.idempotencyKey]))[0]
  if(previous){if(previous.digest!==digest)throw new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键已用于其他请求');return json(previous.response)}
  const original=await project(id);assertWrite(original,actor,context)
  const snapshot=await revision(id,context.expectedRevision),mapping=new Map(assets.map(a=>[a.id,randomUUID()]))
  const cloned=assets.map(a=>({...a,id:mapping.get(a.id)!})),nextId=randomUUID()
  const replace=(text:string)=>{for(const [oldId,newId] of mapping)text=text.replaceAll(oldId,newId);return text}
  const state=JSON.parse(replace(JSON.stringify(snapshot.state)))
  const source=snapshot.source.mode==='hypit'?normalizeSource({...snapshot.source,files:Object.fromEntries(Object.entries(sourceFiles(snapshot.source)).map(([path,text])=>[path,replace(text)])),state,feedback:{comments:[]}},cloned):sourceFor(state,cloned)
  await checkSource(nextId,source,cloned)
  return transaction(async connection=>{
    const locked=(await rows('SELECT * FROM video_project WHERE id=? FOR UPDATE',[id],connection))[0]!
    const duplicate=(await rows('SELECT * FROM video_idempotency WHERE project_id=? AND request_key=?',[id,context.idempotencyKey],connection))[0]
    if(duplicate){if(duplicate.digest!==digest)throw new ApiError(409,'IDEMPOTENCY_CONFLICT','幂等键已用于其他请求');return json(duplicate.response)}
    assertWrite(locked,actor,context)
    await connection.query('INSERT INTO video_project(id,space_id,owner_id,name) VALUES (?,?,?,?)',[nextId,original.space_id,actor.userId,name])
    await connection.query('INSERT INTO video_revision(project_id,revision,source,author_id,message) VALUES (?,1,?,?,?)',[nextId,JSON.stringify(source),actor.userId,`工程变体：来自 ${id} v${snapshot.revision}`])
    for(const asset of cloned)await connection.query('INSERT INTO video_asset(id,project_id,name,kind,metadata) VALUES (?,?,?,?,?)',[asset.id,nextId,asset.name,asset.kind,JSON.stringify(asset)])
    // Explicitly carry access to historical immutable Outputs already authorized
    // in the parent. A fresh unrelated project receives no such ownership.
    const builds=await rows('SELECT DISTINCT s.build_id FROM video_step s JOIN video_run r ON r.id=s.run_id WHERE r.project_id=? AND s.build_id IS NOT NULL',[id],connection)
    if(builds.length){const runId=randomUUID();await connection.query('INSERT INTO video_run(id,project_id,user_id,revision,kind,status,request,result) VALUES (?,?,?,1,?,\'done\',?,?)',[runId,nextId,actor.userId,'variant',JSON.stringify({parent:id,revision:snapshot.revision}),JSON.stringify({inheritedBuilds:builds.map(b=>b.build_id)})]);for(const [index,build] of builds.entries())await connection.query('INSERT INTO video_step(run_id,step_id,provider,status,build_id,request) VALUES (?,?,?,\'done\',?,?)',[runId,`inherited-${index}`,'hypit',build.build_id,'{}'])}
    const result={id:nextId,name,revision:1,mode:source.mode||'timeline'}
    await connection.query('INSERT INTO video_idempotency(project_id,request_key,digest,response) VALUES (?,?,?,?)',[id,context.idempotencyKey,digest,JSON.stringify(result)])
    return result
  })
}
