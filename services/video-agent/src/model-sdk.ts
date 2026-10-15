import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import { stat } from 'node:fs/promises'
import { hypitRoot } from './hypit.js'
import type { Asset } from './timeline.js'
import { invariant } from './errors.js'
import { z } from 'zod'
export const transcriptSchema=z.object({text:z.string().default(''),language:z.string().default('zh'),segments:z.array(z.object({start:z.number().nullable().optional(),end:z.number().nullable().optional(),text:z.string().default('')}).passthrough()).default([])}).passthrough()
const asrModule={name:'@gallery/whisper-large-v3-whisperx',version:'1'}
export const asrContract={module:asrModule,capability:{module:asrModule,name:'transcribe'},returns:{module:asrModule,name:'Transcript'},manifest:{format:'hypit.module@1',...asrModule,dependencies:[],types:[{name:'AudioRequest'},{name:'Transcript'}],capabilities:[{name:'transcribe',returns:{module:asrModule,name:'Transcript'}}],producers:[{name:'transcribe',inputs:[{name:'request',type:{module:asrModule,name:'AudioRequest'}}],outputs:[],needs:[{name:'transcript',capability:{module:asrModule,name:'transcribe'},returns:{module:asrModule,name:'Transcript'}}]}]}}
let sdkPromise:Promise<any>|undefined
export function modelSdk() {
  return sdkPromise??=(async()=>{
    const loader=await import(pathToFileURL(resolve(hypitRoot,'packages/package-loader-node/src/distribution-resolution.ts')).href)
    loader.installDistributionPackageResolution([hypitRoot])
    const {defineExactModelModule}=await import('@hypit/hypit/model-kit')
    const {sealGenerationPortTable}=await import('@hypit/hypit/generation')
    const {defineEndpointPackage}=await import('@hypit/hypit/endpoint-kit')
    const scalar=(name:string,kind:any,required=false)=>({name,value:kind,minItems:required?1:0,maxItems:1})
    const number=(name:string,min:number,max:number,integer=true)=>scalar(name,{kind:'number',minimum:min,maximum:max,integer})
    const media=(name:string,role:string,count=1)=>({name,value:{kind:'media',accepts:[role]},minItems:0,maxItems:count})
    const definitions:any={
      generate_image:{name:'qwen-image-2.1',result:'image',ports:[scalar('prompt',{kind:'text',maxChars:4000},true),number('width',256,2048),number('height',256,2048),number('steps',1,100),number('guidanceScale',0,20,false),number('seed',-2147483648,2147483647),media('images','image',10),media('mask','image'),scalar('maskCue',{kind:'boolean'}),number('maskFeather',0,128),scalar('keepAlpha',{kind:'boolean'})]},
      generate_video:{name:'ltx-2.5',result:'video',ports:[scalar('prompt',{kind:'text',maxChars:4000},true),number('width',256,2048),number('height',256,2048),number('numFrames',9,2881),number('frameRate',24,24),number('seed',-2147483648,2147483647),media('image','image'),scalar('enhancePrompt',{kind:'boolean'})]},
      synthesize_speech:{name:'indextts-2.5',result:'audio',ports:[scalar('text',{kind:'text',maxChars:5000},true),scalar('lang',{kind:'enum',values:['ZH','EN','JA','KO','YUE','AUTO']}),scalar('speaker',{kind:'text',maxChars:200}),media('referenceAudio','audio'),number('durationFactor',.5,2,false),number('seed',-1,2147483647),scalar('useEmotionText',{kind:'boolean'}),scalar('emotionText',{kind:'text',maxChars:1000}),number('emotionAlpha',0,1,false)]},
    }
    const models:any={}
    for(const [tool,spec]of Object.entries(definitions) as [string,any][])models[tool]=defineExactModelModule({module:{name:`@gallery/${spec.name}`,version:'1'},endpoints:[{key:'generate',requestTypeName:'Request',producerName:'generate',ports:sealGenerationPortTable({model:spec.name,result:spec.result,ports:spec.ports,requires:[]}),validateRequest(request:any){if(tool==='generate_video'){const p=request.ports;invariant(Number(p.width?.[0])%32===0&&Number(p.height?.[0])%32===0&&(Number(p.numFrames?.[0])-1)%8===0,'LTX 尺寸或帧数不满足模型约束')}}}]})
    return {models,definitions,defineEndpointPackage}
  })()
}
export async function executeModelProvider(tool:string,args:any,assets:Asset[],execute:()=>Promise<Asset>) {
  const sdk=await modelSdk(),model=sdk.models[tool],spec=sdk.definitions[tool],ports:Record<string,any[]>={}
  const mapping:Record<string,string>={guidance_scale:'guidanceScale',mask_cue:'maskCue',mask_feather:'maskFeather',keep_alpha:'keepAlpha',num_frames:'numFrames',frame_rate:'frameRate',enhance_prompt:'enhancePrompt',spk_name:'speaker',duration_factor:'durationFactor',use_emo_text:'useEmotionText',emo_text:'emotionText',emo_alpha:'emotionAlpha'}
  for(const [name,value]of Object.entries(args))if(!['referenceAssetId','referenceAssetIds','maskAssetId'].includes(name)&&value!==undefined)ports[mapping[name]||name]=[value]
  const ref=async(id:string,role:string)=>{const asset=assets.find(a=>a.id===id);invariant(asset,'SDK 媒体引用不存在');return {role,artifact:{kind:'blob',resource:`res_gallery_${asset.id}`,size:(await stat(role==='audio'?asset.audioPath||asset.path:asset.path)).size,mediaType:role==='audio'?'audio/wav':'image/png'}}}
  if(args.referenceAssetIds?.length)ports.images=await Promise.all(args.referenceAssetIds.map((id:string)=>ref(id,'image')))
  if(args.maskAssetId)ports.mask=[await ref(args.maskAssetId,'image')]
  if(args.referenceAssetId)ports[tool==='synthesize_speech'?'referenceAudio':'image']=[await ref(args.referenceAssetId,tool==='synthesize_speech'?'audio':'image')]
  const endpoint=model.endpoints.generate,request=endpoint.sealRequest(ports)
  let output:Asset|undefined,handler:any
  // Install the public Provider facet into the sidecar's durable task executor. Hypit rendering uses its own local Runtime.
  const provider=sdk.defineEndpointPackage({module:{name:'@gallery/provider-self-hosted',version:'1'},facet:spec.name,instance:`gallery.${spec.name}`,pool:'gallery-models',defaultConcurrency:1,
    pricing:{kind:'local'},credentials:{},capabilities:[{capability:endpoint.capability,returns:endpoint.returns,lifecycle:'immediate',supports(need:any){try{endpoint.validateRequest(need.constraints);return {status:'supported'}}catch(e){return {status:'unsupported',reason:(e as Error).message}}},async handler(context:any){endpoint.validateRequest(context.need.constraints);output=await execute();const blob={kind:'blob',resource:`res_gallery_${output.id}`,size:(await stat(output.path)).size,mediaType:spec.result==='audio'?'audio/wav':spec.result==='video'?'video/mp4':'image/png'};return {value:{kind:'inline',value:{[spec.result==='audio'?'audios':spec.result==='video'?'videos':'images']:[blob]}}}}}]})
  provider.install({registerImmediateEndpoint(_instance:any,_capability:any,_returns:any,installed:any){handler=installed}})
  await handler({need:{capability:endpoint.capability,returns:endpoint.returns,constraints:request},credentials:{}})
  return output!
}
export async function executeTranscriptProvider(path:string,execute:()=>Promise<unknown>) {
  const sdk=await modelSdk(),constraints={audio:{kind:'blob',resource:'res_gallery_used_audio',size:(await stat(path)).size,mediaType:'audio/wav'},language:'zh',wordTimestamps:true,charAlignments:true}
  let handler:any,result:any
  const provider=sdk.defineEndpointPackage({module:{name:'@gallery/provider-self-hosted',version:'1'},facet:'whisper-large-v3-whisperx',instance:'gallery.asr',pool:'gallery-models',defaultConcurrency:1,pricing:{kind:'local'},credentials:{},capabilities:[{capability:asrContract.capability,returns:asrContract.returns,lifecycle:'immediate',supports:()=>({status:'supported'}),async handler(){result=transcriptSchema.parse(await execute());return {value:{kind:'inline',value:JSON.parse(JSON.stringify(result))}}}}]})
  provider.install({registerImmediateEndpoint(_id:any,_cap:any,_type:any,installed:any){handler=installed}})
  await handler({need:{capability:asrContract.capability,returns:asrContract.returns,constraints},credentials:{}})
  return result
}
