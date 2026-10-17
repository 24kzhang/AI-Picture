export const videoEnabled = import.meta.env.VITE_VIDEO_ENABLED !== 'false'
export const videoBase = '/api/video-editor'
export interface VideoAsset { id:string;name:string;kind:'video'|'image'|'audio';frames:number;width:number;height:number;hasAudio:boolean;size:number;posterKey?:string;purpose?:'speech' }
export interface VideoClip {id:string;trackId:string;assetId?:string;kind:'video'|'image'|'audio'|'text';name:string;at:number;length:number;in:number;link?:string;sourceClipId?:string;x:number;y:number;scale:number;fit:'contain'|'cover'|'stretch';volume:number;muted:boolean;fadeIn:number;fadeOut:number;text?:string;fontSize:number;color:string;subtitle?:boolean;precision?:string}
export interface VideoState {fps:24;width:number;height:number;tracks:{id:string;name:string;kind:'visual'|'audio'|'text'}[];clips:VideoClip[]}
export interface VideoRecord {id:string;name:string;project_id:string;revision:number;review_status:number;review_source?:string;reviewer_name?:string;review_message?:string;review_time?:string;metadata:{frames:number;fps?:number;width:number;height:number;size:number;posterKey?:string}}
export async function videoApi<T=any>(path:string,body?:unknown,method?:string):Promise<T> {
  const response=await fetch(videoBase+path,{method:method||(body?'POST':'GET'),credentials:'include',headers:body instanceof FormData?{}:{'Content-Type':'application/json'},body:body instanceof FormData?body:body?JSON.stringify(body):undefined})
  let value:any
  try{value=await response.json()}catch{throw Object.assign(new Error('视频服务暂不可用，请稍后重试'),{status:response.status})}
  if(!response.ok)throw Object.assign(new Error(value.message||'视频服务请求失败'),{status:response.status,code:value.code,detail:value.detail})
  return value as T
}
export const assetMedia=(projectId:string,assetId:string,kind?:string)=>`${videoBase}/projects/${projectId}/assets/${assetId}/media${kind?`?kind=${kind}`:''}`
export const frameTime=(frames:number,fps=24)=>`${(frames/(Number.isFinite(fps)&&fps>0?fps:24)).toFixed(2)}s`
