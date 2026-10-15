import { revision, commitState, type WriteContext } from './projects.js'
import { projectAssets } from './assets.js'
import { transcribeClip } from './providers.js'
import { authorize, type Actor } from './auth.js'
import { cancelled } from './runs.js'
import { replaceSubtitles } from './timeline.js'
import { ApiError, invariant } from './errors.js'

const services={revision,commitState,projectAssets,transcribeClip,authorize,cancelled}

/** A direct subtitle action has no planning-model dependency. */
export async function transcribeTimelineClip(project:any,actor:Actor,context:WriteContext,clipId:string,runId:string,deps=services) {
  const snapshot=await deps.revision(project.id,context.expectedRevision)
  invariant(snapshot.source.mode!=='hypit','完整工程请使用语义字幕编排')
  const candidate=structuredClone(snapshot.state)
  const clip=candidate.clips.find(c=>c.id===clipId)
  invariant(clip,'所选片段不存在')
  const assets=await deps.projectAssets(project.id,actor,project)
  const transcript=await deps.transcribeClip(clip,assets,runId,'transcription')
  if(await deps.cancelled(runId))throw new ApiError(409,'RUN_CANCELLED','字幕任务已取消，不应用修改')
  const captions=replaceSubtitles(candidate,clip,transcript.segments||[])
  const currentActor=await deps.authorize({headers:{cookie:actor.cookie}} as any,project,'edit')
  const result=await deps.commitState(project.id,currentActor,{...context,idempotencyKey:`${runId}-commit`},assets,candidate,'生成中文字幕',{clipId},runId)
  return {applied:true,revision:result.revision,subtitleCount:captions.length,
    answer:`已保存项目 v${result.revision}，完成中文字幕转写，共 ${captions.length} 条字幕。${captions.length?'':'所选区间未识别到语音，已清除该来源的旧字幕。'}`}
}
