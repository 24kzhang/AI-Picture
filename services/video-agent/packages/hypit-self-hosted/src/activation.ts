import {createRuntimeEndpointAdapterFacet} from '@hypit/hypit/runtime-kit'
import {defineEndpointPackage,wakeAfter,canonicalize} from '@hypit/hypit/endpoint-kit'
// 0.2.16 does not expose these two domain packages as public SDK subpaths.
// Keep this pinned adapter with the same Distribution, rather than claiming a
// nonexistent public export or replacing the WhisperX evidence contract.
import {verifyWhisperXAlignmentRequest,whisperXCapabilities,assertWhisperXEvidenceWav,interpretWhisperXTranscript} from '../../../node_modules/@hypit/hypit/packages/whisperx/src/index.ts'
import {sealAlignedTranscriptEvidence,speechEvidenceTypes} from '../../../node_modules/@hypit/hypit/packages/speech-evidence/src/index.ts'

const module={name:'@gallery/provider-self-hosted',version:'1'} as const
function assert(value:unknown,message:string):asserts value{if(!value)throw Error(message)}
export default {
  format:'hypit.node-package@1' as const,
  hostFacets:[createRuntimeEndpointAdapterFacet({use:module.name,activate(context){
    assert(context.pool,'自建 ASR 需要执行池')
    const config=context.config as {baseUrl?:string},baseUrl=config.baseUrl?.replace(/\/$/,'')
    assert(baseUrl&&new URL(baseUrl).protocol==='http:'&&new URL(baseUrl).hostname==='172.18.20.44','自建模型仅使用已配置的服务器网关')
    async function request(path:string,init:RequestInit={}) {
      for(let attempt=0;attempt<4;attempt++){
        let response:Response
        try{response=await fetch(baseUrl+path,{...init,signal:AbortSignal.timeout(120000)})}catch{throw Error(init.method==='POST'?'ASR 提交未收到回执，结果未知，禁止自动重复生成':'ASR 连接中断，远端任务回执仍保留')}
        if(response.status===429&&attempt<3){const seconds=Number(response.headers.get('retry-after')||'5');await new Promise(r=>setTimeout(r,Math.min(180000,Math.max(1000,Number.isFinite(seconds)?seconds*1000:5000))));continue}
        if(response.status===404)throw Error('远端 ASR 任务丢失，请明确重新执行 Build')
        if(!response.ok)throw Error(`ASR 请求失败 ${response.status}: ${(await response.text()).slice(0,1500)}`)
        return await response.json() as any
      }
      throw Error('ASR 持续繁忙')
    }
    return {endpoint:defineEndpointPackage({module,facet:'asr',instance:context.instance,pool:context.pool,pricing:{kind:'local'},defaultConcurrency:1,actionLimits:{submit:{concurrency:1},poll:{concurrency:2}},capabilities:[{
      capability:whisperXCapabilities.alignment,returns:speechEvidenceTypes.alignedTranscript,lifecycle:'asynchronous',
      // Preflight constraints may contain unresolved upstream media. Validate the
      // complete evidence only at start, as the bundled WhisperX Provider does.
      endpoint:{
        async start(ctx){
          const input=verifyWhisperXAlignmentRequest(ctx.need.constraints),audio=await ctx.resources.get(input.audio.resource)
          assert(audio&&audio.byteLength===input.audio.size,'语音证据资源缺失或改变');assertWhisperXEvidenceWav(audio,input.sampleFrames)
          const form=new FormData();form.append('file',new Blob([new Uint8Array(audio)],{type:'audio/wav'}),'semantic-audio.wav');form.append('language',input.language);form.append('word_timestamps','true');form.append('char_alignments','true');form.append('wait','false')
          const response=await request('/asr/api/transcribe',{method:'POST',body:form}),taskId=response.task_id||response.taskId
          assert(taskId,'ASR 未返回异步任务回执')
          const handle={taskId,sampleFrames:input.sampleFrames},receipt={id:taskId}
          await ctx.checkpoint?.({handle,receipt})
          return {...wakeAfter(handle,2000),receipt}
        },
        async poll(ctx){
          const handle=ctx.handle as {taskId:string;sampleFrames:number},response=await request(`/asr/api/task/${encodeURIComponent(handle.taskId)}`)
          if(['error','failed'].includes(response.status))return {status:'failed' as const,receipt:{id:handle.taskId},failure:{code:'ASR_FAILED',message:String(response.error||response.message||'自建转写失败')}}
          if(['done','completed'].includes(response.status)){
            const raw=response.result||response
            // The service supplies WhisperX segments/words in seconds. Hypit's public
            // adapter preserves missing timestamps and maps them to exact evidence samples.
            const evidence=sealAlignedTranscriptEvidence({passages:interpretWhisperXTranscript(raw,handle.sampleFrames)})
            return {status:'completed' as const,receipt:{id:handle.taskId},result:{value:{kind:'inline' as const,value:canonicalize(evidence)}}}
          }
          return wakeAfter(handle,2000,Date.now(),{phase:String(response.status||'ASR 运行中')})
        },
        async cancel(ctx){return {status:'unsupported' as const,receipt:{id:(ctx.handle as any).taskId}}},
      },
    }]})}
  }})],
}
