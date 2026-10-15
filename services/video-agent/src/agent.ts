import { z } from 'zod'
import { config } from './config.js'
import { revision, project as getProject, operate, commitState, commitSource, assertBuildReferences, type WriteContext } from './projects.js'
import { catalog, vocabulary } from './hypit-engine.js'
import { normalizeSource, sourceFiles } from './source-bundle.js'
import { checkSource, writeWorkspace, type SourceBundle } from './hypit.js'
import { projectAssets } from './assets.js'
import { event, cancelled, step, stepUpdate } from './runs.js'
import { operationSchema, applyOperations, replaceSubtitles, clipSchema, validateState, type State } from './timeline.js'
import { generateAsset, modelSchemas, transcribeClip, type ModelTool } from './providers.js'
import { renderProject } from './render.js'
import {authorize,type Actor} from './auth.js'
import { ApiError, invariant } from './errors.js'
import { json } from './db.js'
import { executionReceipt } from './agent-result.js'

const tools = [
  {name:'inspect_project',description:'查看项目、素材、整数帧时间线，24fps',parameters:{type:'object',properties:{},additionalProperties:false}},
  {name:'inspect_sources',description:'读取完整 Hypit 源文件集合、入口和工程模式。工程模式必须通过 update_sources 编辑，不能使用基础 clip 操作。',parameters:{type:'object',properties:{},additionalProperties:false}},
  {name:'component_vocabulary',description:'读取固定版本 Hypit 组件的真实语法、属性、输出和示例，禁止猜测组件属性。',parameters:{type:'object',properties:{package:{type:'string'}},required:['package'],additionalProperties:false}},
  {name:'update_sources',description:'候选替换完整工程中指定源文件。先编译验证，全部成功后才提交一个版本；保留未修改文件和元数据。禁止任意脚本、外部 URL 或商业模型调用。',parameters:{type:'object',properties:{files:{type:'array',items:{type:'object',properties:{path:{type:'string'},text:{type:'string'}},required:['path','text'],additionalProperties:false}},entry:{type:'string'}},required:['files'],additionalProperties:false}},
  ...operationSchema.options.map(schema=>{const full=z.toJSONSchema(schema) as any;const name=full.properties.type.const;delete full.properties.type;full.required=full.required.filter((k:string)=>k!=='type');delete full.$schema;return {name,description:`确定性剪辑操作 ${name}。时间单位全部为整数帧，24帧=1秒；轨道ID和片段ID必须来自项目。`,parameters:full}}),
  ...Object.entries(modelSchemas).map(([name,schema])=>{const parameters=z.toJSONSchema(schema) as any;delete parameters.$schema;return{name,description:'调用已部署的自建媒体模型生成素材。返回素材ID后再用insert_clip插入，不会直接覆盖已有时间线。',parameters}}),
  {name:'transcribe',description:'转写所选片段实际使用的音频，一次调用自动移除该来源的旧字幕并插入新字幕，返回实际字幕片段；不需要再调用删除或插入。其他来源字幕及音视频保持原样，静音不影响转写。',parameters:{type:'object',properties:{clipId:{type:'string'}},required:['clipId'],additionalProperties:false}},
  {name:'transcribe_asset',description:'准备完整工程的真实语音证据。转写素材指定区间，返回真实文本/词级时间戳，供 Script 和语义字幕编排使用；不会压平或改写已有工程。时间单位为24fps整数帧。',parameters:{type:'object',properties:{assetId:{type:'string'},in:{type:'integer',minimum:0},frames:{type:'integer',minimum:1}},required:['assetId'],additionalProperties:false}},
  {name:'render',description:'导出当前不可变项目版本',parameters:{type:'object',properties:{},additionalProperties:false}},
].map(tool=>({type:'function',function:tool}))
export async function agentRun(project:any,actor:Actor,context:WriteContext,message:string,selection:any,runId:string) {
  invariant(config.llmKey,'未配置视频规划模型密钥','LLM_NOT_CONFIGURED')
  let current=await revision(project.id,context.expectedRevision),assets=await projectAssets(project.id,actor,project)
  let candidate:State=current.state,changed=false,hasGenerated=false
  let nativeSource:SourceBundle|undefined=current.source.mode==='hypit'?current.source:undefined
  const chat:any[]=[{role:'system',content:'你是云图库的视频剪辑助手。用给定工具完成用户要求。全部时间使用24fps整数帧、左闭右开。只能引用项目已有素材、轨道和片段ID；不要编造。视频原声已独立导入且默认联动，降低音乐只修改音乐轨。保留前3秒是trim_clip(at=原位置,length=72)，不能忽略素材入点。生成任务返回新素材，之后可插入。配音使用实际素材时长，覆盖原声区间需明确静音对应原声音轨。无必要不要生成素材。解释失败，不能声称未执行的操作完成。'},
    {role:'user',content:JSON.stringify({message,project:{id:project.id,revision:current.revision,mode:current.source.mode||'timeline',state:candidate,assets:assets.map(({path,audioPath,...a})=>a)},selection})}]
  if(nativeSource)chat[0].content+=' 当前是完整 Hypit 工程，先 inspect_sources；按真实源码修改，使用 component_vocabulary 查询结构。禁止基础 clip 操作或转写扁平片段，因为它们不能表达当前完整工程。需要 ASR 时保留现有素材和语义证据，明确说明限制。update_sources 保留所有未改文件，图像视频配音生成素材后在源码中引用 ./assets/<id>.<png/mp4/wav>。'
  const executed:any[]=[],generated:string[]=[]
  let renderRequested=false,repairs=0,answer='',attempt=0,finished=false
  for(let round=0;round<config.agentMaxRounds;round++) {
    if(await cancelled(runId))throw new ApiError(409,'RUN_CANCELLED','编辑任务已取消')
    const response=await fetch(`${config.llmUrl.replace(/\/$/,'')}/chat/completions`,{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${config.llmKey}`},body:JSON.stringify({model:config.llmModel,messages:chat,tools,tool_choice:'auto',temperature:.1,parallel_tool_calls:false}),signal:AbortSignal.timeout(120_000)})
    if(!response.ok)throw new ApiError(502,'LLM_FAILED',`对话规划失败 (${response.status})`)
    const envelope=await response.json() as any,turn=envelope.choices?.[0]?.message
    invariant(turn,'对话模型没有返回消息');chat.push(turn)
    if(!turn.tool_calls?.length){answer=turn.content||'编辑完成';finished=true;break}
    for(const call of turn.tool_calls) {
      const stepId=`agent-${attempt++}`,name=call.function.name
      try {
        const args=JSON.parse(call.function.arguments||'{}');let result:any
        if(name==='inspect_project')result={state:candidate,assets:assets.map(({path,audioPath,...a})=>a),revision:current.revision}
        else if(name==='inspect_sources')result={source:nativeSource||current.source,revision:current.revision,availablePackages:(await catalog()).packages.map(p=>p.name)}
        else if(name==='component_vocabulary'){const workspace=await writeWorkspace(project.id,`agent-${runId}`,nativeSource||current.source,assets);result=await vocabulary(z.string().parse(args.package),workspace)}
        else if(name==='update_sources'){
          invariant(!changed||nativeSource,'请先提交基础剪辑后再转换完整工程')
          const edits=z.array(z.object({path:z.string(),text:z.string()})).min(1).max(32).parse(args.files),original=nativeSource||current.source
          const proposed=normalizeSource({...original,mode:'hypit',state:candidate,entry:args.entry||original.entry,files:{...sourceFiles(original),...Object.fromEntries(edits.map(f=>[f.path,f.text]))}},assets)
          await assertBuildReferences(project.id,proposed);await checkSource(project.id,proposed,assets);nativeSource=proposed;changed=true;result={validated:true,files:edits.map(f=>f.path),pendingCommit:true}
        }
        else if(name in modelSchemas) {
          const asset=await generateAsset(name as ModelTool,args,project,actor,assets,runId,stepId)
          assets.push(asset);hasGenerated=true;generated.push(asset.id);const {path,audioPath,...view}=asset;result=view
        } else if(name==='transcribe_asset') {
          const input=z.object({assetId:z.string().uuid(),in:z.number().int().nonnegative().default(0),frames:z.number().int().positive().optional()}).strict().parse(args),asset=assets.find(a=>a.id===input.assetId)
          invariant(asset?.hasAudio,'素材没有可转写的音频')
            const temporary=validateState({fps:24,width:1280,height:720,tracks:[{id:'audio',kind:'audio',name:'语音证据'}],clips:[clipSchema.parse({id:crypto.randomUUID(),assetId:asset.id,kind:'audio',name:asset.name,trackId:'audio',at:0,length:input.frames??asset.frames-input.in,in:input.in})]},assets,config.maxFrames)
          result=await transcribeClip(temporary.clips[0]!,assets,runId,stepId)
        } else if(name==='transcribe') {
          invariant(!nativeSource,'完整工程请先准备实际音频的语义对齐，不能自动改写扁平字幕')
          const clip=candidate.clips.find(c=>c.id===args.clipId);invariant(clip,'所选片段不存在')
          const asr=await transcribeClip(clip,assets,runId,stepId)
          const previousCount=candidate.clips.length
          const additions=replaceSubtitles(candidate,clip,asr.segments||[])
          changed=changed||previousCount!==candidate.clips.length||additions.length>0;result={completed:true,replacedPreviousSubtitles:true,message:'此来源的旧字幕已替换，无需再删除或插入字幕；片段数量、时间和内容以subtitles为准。',text:asr.text,subtitleCount:additions.length,precision:additions[0]?.precision||'none',subtitles:additions}
        } else if(name==='render'){renderRequested=true;result={queuedAfterCommit:true}}
        else {
          invariant(!nativeSource,'完整工程请使用 update_sources 编辑原生组件')
          const operation=operationSchema.parse({type:name,...args})
          candidate=applyOperations(candidate,[operation],assets,config.maxFrames);changed=true;result={state:candidate}
        }
        executed.push({name,args});chat.push({role:'tool',tool_call_id:call.id,content:JSON.stringify(result)})
        await event(runId,'tool',{name,args,result})
      } catch(error) {
        if(error instanceof ApiError&&['SUBMISSION_UNKNOWN','REMOTE_TASK_LOST','RUN_CANCELLED'].includes(error.code))throw error
        repairs++;chat.push({role:'tool',tool_call_id:call.id,content:JSON.stringify({error:(error as Error).message})});await event(runId,'tool_error',{name,message:(error as Error).message})
        if(repairs>2)throw new ApiError(422,'AGENT_INVALID_OPERATION',`参数修正两次仍失败：${(error as Error).message}`)
      }
    }
  }
  if(!finished)throw new ApiError(422,'AGENT_STEP_LIMIT',`规划超过 ${config.agentMaxRounds} 轮，未提交候选编辑；已生成素材仍在素材库中`)
  if(await cancelled(runId))throw new ApiError(409,'RUN_CANCELLED','编辑任务已取消，不应用候选修改')
  let applied=false,pending=false
  if(changed) {
    actor=await authorize({headers:{cookie:actor.cookie}} as any,project,'edit')
    try {const ctx={...context,idempotencyKey:`${runId}-commit`};const result=nativeSource?await commitSource(project.id,actor,ctx,assets,nativeSource,'Hypit Agent 工程编辑',{message,executed},runId):await commitState(project.id,actor,ctx,assets,candidate,'Agent 编辑',{message,executed},runId);current=await revision(project.id,result.revision);applied=true}
    catch(error){if(hasGenerated&&error instanceof ApiError&&error.statusCode===409&&error.code!=='RUN_CANCELLED'){pending=true;await event(runId,'pending_assets',{assetIds:generated,reason:error.message})}else throw error}
  }
  let output
  if(renderRequested&&!pending)output=await renderProject(project,current.revision,current.source,assets,actor,runId)
  return {answer:executionReceipt({modelAnswer:answer,applied,pending,generatedCount:generated.length,revision:current.revision,tools:executed.map(tool=>tool.name),exportFrames:output?.frames,exportFps:output?.fps,toolErrors:repairs}),applied,pending,generatedAssetIds:generated,revision:current.revision,operations:executed,export:output}
}
