import {resolve} from 'node:path'
import {mkdir,stat} from 'node:fs/promises'
import {dataRoot} from './config.js'
import {hypit} from './hypit.js'
import {openStudio} from './studio.js'
import {project as getProject} from './projects.js'
import {storeObject} from './assets.js'
import {cancelled} from './runs.js'
import {ApiError} from './errors.js'
import type {Actor} from './auth.js'
import type {Asset} from './timeline.js'

export async function snapshotProject(project:any,assets:Asset[],actor:Actor,runId:string,frames:number[]){
  if(await cancelled(runId))throw new ApiError(409,'RUN_CANCELLED','截图已取消')
  const session=await openStudio(project,assets),directory=resolve(dataRoot,'snapshots',runId)
  await mkdir(resolve(dataRoot,'snapshots'),{recursive:true})
  const receipt=JSON.parse(await hypit(['snapshot','--studio',`http://127.0.0.1:${session.port}`,'--at-frame',frames.join(','),'--to',directory,'--runtime',resolve(session.workspace,'hypit.runtime.json')],session.workspace))
  if((await getProject(project.id)).revision!==project.revision)throw new ApiError(409,'SNAPSHOT_REVISION_CHANGED','截图期间工程已修改，请对当前版本重新截图')
  const artifacts=[]
  for(const frame of receipt.frames){if(await cancelled(runId))throw new ApiError(409,'RUN_CANCELLED','截图已取消');artifacts.push({name:`frame-${frame.frame}`,frame:frame.frame,mediaType:'image/png',objectKey:await storeObject(actor,project,frame.path,'png'),size:(await stat(frame.path)).size})}
  return {revision:project.revision,artifacts}
}
