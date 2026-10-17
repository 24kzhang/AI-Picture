import { spawn, type ChildProcess } from 'node:child_process'
import {createWriteStream} from 'node:fs'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import http from 'node:http'
import net from 'node:net'
import type { FastifyRequest, FastifyReply } from 'fastify'
import { hypitRoot, writeWorkspace, sourceFor, ffmpegPath, ffprobePath, type SourceBundle } from './hypit.js'
import { mediaEnvironment } from './media-environment.js'
import { dataRoot, root, config } from './config.js'
import { revision, project as getProject, assertWrite, commitState, commitSource, assertBuildReferences, type WriteContext } from './projects.js'
import { sourceFiles, runEntry, normalizeSource } from './source-bundle.js'
import { rows } from './db.js'
import { applyOperations, type State, type Asset } from './timeline.js'
import type { Actor } from './auth.js'
import { ApiError, invariant } from './errors.js'
const sessions=new Map<string,{port:number;process:ChildProcess;workspace:string;revision:number}>()
const opening=new Map<string,Promise<any>>()
const writes=new Map<string,Promise<void>>()
let nextStudioPort=19000
async function studioPort():Promise<number>{
  while(nextStudioPort<65536){
    const port=nextStudioPort++
    const available=await new Promise<boolean>((accept,reject)=>{
      const socket=net.createServer()
      socket.once('error',(error:NodeJS.ErrnoException)=>error.code==='EADDRINUSE'?accept(false):reject(error))
      socket.listen(port,'127.0.0.1',()=>socket.close(()=>accept(true)))
    })
    if(available)return port
  }
  throw new ApiError(503,'STUDIO_PORT_UNAVAILABLE','没有可用的 Studio 本地端口')
}
const supportedParameters=['gain','fade-in','fade-out','frame.x','frame.y','appearance.fit','style.size','style.fill']
export async function patchStudio() {
  const manifest=JSON.parse(await readFile(resolve(hypitRoot,'package.json'),'utf8'))
  invariant(manifest.version==='0.2.16','Hypit 补丁仅支持固定版本 0.2.16')
  const path=resolve(hypitRoot,'packages/studio/start.ts')
  let text=await readFile(path,'utf8')
  if(!text.includes('VIDEO_STUDIO_BASE')) {
    invariant(text.includes('root: here,'),'Hypit Studio 上游结构改变，不能应用补丁')
    text=text.replace('root: here,','root: here,\n    base: process.env.VIDEO_STUDIO_BASE ?? "/",')
      .replace('      port,','      port,\n      host: "127.0.0.1",\n      strictPort: true,')
    await writeFile(path,text,'utf8')
  }
  const serverPath=resolve(hypitRoot,'packages/studio/src/server.ts')
  let server=await readFile(serverPath,'utf8')
  if(!server.includes('VIDEO_STUDIO_BASE')){
    const needle='const url = new URL(request.url ?? "/", "http://studio.hypit.local");'
    invariant(server.includes(needle),'Hypit Studio 路由补丁定位失败')
    server=server.replace(needle,needle+'\n        const galleryBase = process.env.VIDEO_STUDIO_BASE;\n        if (galleryBase && url.pathname.startsWith(galleryBase)) url.pathname = "/" + url.pathname.slice(galleryBase.length);')
    await writeFile(serverPath,server,'utf8')
  }
  if(!server.includes('galleryInitialCompilation')){
    const needle='  const schedule = (): void => {'
    invariant(server.includes(needle),'Hypit 初始化编译补丁定位失败')
    const initial=`  // Multiple gallery members must share the first compilation instead of invalidating each other.
  let galleryInitialCompilation: Promise<void> | undefined;
  const galleryEnsureInitialSnapshot = async (): Promise<void> => {
    if (snapshot !== undefined || failure !== undefined) return;
    galleryInitialCompilation ??= (async () => {
      for (let attempt = 0; attempt < 8 && snapshot === undefined && failure === undefined; attempt++) {
        await publish(++requestedRevision);
      }
      if (snapshot === undefined && failure === undefined) failure = { revision: requestedRevision, error: "Source changed repeatedly during Studio initialization; reload after editing settles." };
    })().finally(() => { galleryInitialCompilation = undefined; });
    await galleryInitialCompilation;
  };

`
    server=server.replace(needle,initial+needle)
      .replace('if (snapshot === undefined && failure === undefined) await publish(++requestedRevision);','if (snapshot === undefined && failure === undefined) await galleryEnsureInitialSnapshot();')
    await writeFile(serverPath,server,'utf8')
  }
  if(!server.includes('gallery-initial-singleflight-v2')){
    // Match the whole initial-session branch, keeping source mutation publication independent.
    server=server.replace('await galleryEnsureInitialSnapshot();\n              json(response, 202, { revision: attempt });','const attempt = ++requestedRevision;\n              await publish(attempt);\n              json(response, 202, { revision: attempt });')
    const branch='if (snapshot === undefined && failure === undefined) {\n              const attempt = ++requestedRevision;\n              await publish(attempt);\n            }'
    invariant(server.includes(branch),'Hypit 首次快照分支定位失败')
    server=server.replace(branch,'if (snapshot === undefined && failure === undefined) {\n              await galleryEnsureInitialSnapshot(); // gallery-initial-singleflight-v2\n            }')
    await writeFile(serverPath,server,'utf8')
  }
  const uiPath=resolve(hypitRoot,'packages/studio/src/ui/main.ts');let ui=await readFile(uiPath,'utf8')
  if(!ui.includes('gallerySupportedParameters')){const needle='const editable = parameter.edit !== undefined || change !== undefined;';invariant(ui.includes(needle),'Hypit 属性补丁定位失败');ui=ui.replace(needle,`const gallerySupportedParameters = ${JSON.stringify(supportedParameters)};\n  const editable = (parameter.edit !== undefined || change !== undefined) && gallerySupportedParameters.includes(parameter.binding);`)}
  if(!ui.includes('__GALLERY_HYPIT_FULL__'))ui=ui.replace('&& gallerySupportedParameters.includes(parameter.binding)','&& ((window as Window & {__GALLERY_HYPIT_FULL__?:boolean}).__GALLERY_HYPIT_FULL__ || gallerySupportedParameters.includes(parameter.binding))')
  await writeFile(uiPath,ui,'utf8')
  const timelinePath=resolve(hypitRoot,'packages/studio/src/ui/timeline.ts'),timeline=await readFile(timelinePath,'utf8')
  if(!timeline.includes('gallery-keyboard-selection')){
    const needle='        node.addEventListener("pointerdown", (event) => {'
    invariant(timeline.includes(needle),'Hypit 时间线键盘选择补丁定位失败')
    await writeFile(timelinePath,timeline.replace(needle,`        // gallery-keyboard-selection: semantic buttons must also select on activation.
        node.addEventListener("keydown", (event) => {
          if (event.key !== "Enter" && event.key !== " ") return;
          event.preventDefault(); event.stopPropagation();
          store.select(clip.id, "timeline");
        });
${needle}`),'utf8')
  }
  const editorPath=resolve(hypitRoot,'packages/studio/src/ui/code.ts'),editor=await readFile(editorPath,'utf8')
  if(!editor.includes('gallery-save-reason')){
    const needle='saveState.className = "code-save-state error";'
    invariant(editor.includes(needle),'Hypit 源编辑错误提示补丁定位失败')
    await writeFile(editorPath,editor.replace(needle,needle+'\n      saveState.title = error instanceof Error ? error.message : String(error); // gallery-save-reason'),'utf8')
  }
  const libraryPath=resolve(hypitRoot,'packages/studio/src/build-library.ts'),library=await readFile(libraryPath,'utf8')
  if(!library.includes('gallery-project-library')){
    const needle='(isWithin(input.workspaceRoot, item.run?.path ?? item.source.path) || isWithin(input.workspaceRoot, item.source.path))'
    invariant(library.includes(needle),'Hypit 产物目录补丁定位失败')
    await writeFile(libraryPath,library.replace(needle,'(process.env.VIDEO_STUDIO_BASE !== undefined || isWithin(input.workspaceRoot, item.run?.path ?? item.source.path) || isWithin(input.workspaceRoot, item.source.path)) /* gallery-project-library: business proxy filters immutable Build ownership */'),'utf8')
  }
}
export async function openStudio(project:any,assets:Asset[]) {
  const pending=opening.get(project.id);if(pending)return pending
  const work=openStudioWorkspace(project,assets);opening.set(project.id,work)
  try{return await work}finally{opening.delete(project.id)}
}
async function openStudioWorkspace(project:any,assets:Asset[]) {
  const current=await revision(project.id),base=`/api/video-editor/projects/${project.id}/studio/`
  let session=sessions.get(project.id)
  if(session&&session.process.exitCode===null&&session.process.signalCode===null&&!session.process.killed && (session as any).entry===runEntry(current.source)) {
    if(session.revision!==current.revision){await writeWorkspace(project.id,'studio',current.source,assets);session.revision=current.revision}
    return {...session,base}
  }
  session?.process.kill()
  await patchStudio()
  const workspace=await writeWorkspace(project.id,'studio',current.source,assets)
  const port=await studioPort()
  const child=spawn(process.execPath,[resolve(hypitRoot,'bin/hypit.mjs'),'studio','--run',resolve(workspace,runEntry(current.source)),'--workspace',workspace,'--runtime',resolve(workspace,'hypit.runtime.json'),'--port',String(port)],
    {windowsHide:true,env:{...mediaEnvironment(process.env,[ffmpegPath,ffprobePath]),VIDEO_STUDIO_BASE:base,HYPIT_STATE_HOME:resolve(dataRoot,'hypit-state'),NODE_OPTIONS:'--use-system-ca',npm_config_cache:resolve(root,'runtime-data/npm-cache'),npm_config_registry:'https://registry.npmmirror.com'}})
  const log=createWriteStream(resolve(workspace,'studio.log'),{flags:'a'});child.stdout.pipe(log);child.stderr.pipe(log);child.on('exit',()=>log.end())
  let error='';child.stderr.on('data',chunk=>{error=(error+chunk.toString()).slice(-6000)})
  session={port,process:child,workspace,revision:current.revision};(session as any).entry=runEntry(current.source);sessions.set(project.id,session)
  for(let attempt=0;attempt<90;attempt++){
    if(child.exitCode!==null||child.signalCode!==null||child.killed)throw new ApiError(502,'STUDIO_START_FAILED',error||'Hypit Studio 启动失败')
    // Session reads can compile/process media. Polling that endpoint repeatedly invalidates its pending snapshots.
    try{const response=await fetch(`http://127.0.0.1:${port}${base}`,{signal:AbortSignal.timeout(1500)});if(response.ok&&response.headers.get('content-type')?.includes('text/html'))return {...session,base}}catch{}
    await new Promise<void>(r=>setTimeout(r,500))
  }
  throw new ApiError(504,'STUDIO_NOT_READY','Studio 初始化尚未完成，请重试')
}
export async function proxyStudio(request:FastifyRequest,reply:FastifyReply,project:any,actor:Actor,assets:Asset[]) {
  const previous=writes.get(project.id)||Promise.resolve()
  if(['GET','HEAD'].includes(request.method)){await previous;return proxyStudioRequest(request,reply,project,actor,assets)}
  let release!:()=>void;const held=new Promise<void>(r=>{release=r});const queued=previous.then(()=>held);writes.set(project.id,queued)
  await previous
  try{return await proxyStudioRequest(request,reply,project,actor,assets)}finally{release();if(writes.get(project.id)===queued)writes.delete(project.id)}
}
async function proxyStudioRequest(request:FastifyRequest,reply:FastifyReply,project:any,actor:Actor,assets:Asset[]) {
  project=await getProject(project.id)
  const session=await openStudio(project,assets),relative=request.url.slice(request.url.indexOf('/studio/')+8)
  if(relative.includes('..'))throw new ApiError(403,'STUDIO_PATH_REJECTED','不允许访问工作区外部文件')
  const mutation=request.method!=='GET'&&request.method!=='HEAD'
  let context:WriteContext|undefined,before:SourceBundle|undefined
  const currentSource=(await revision(project.id)).source,full=currentSource.mode==='hypit',route=relative.split('?')[0]
  const owned=new Set((await rows('SELECT DISTINCT s.build_id FROM video_step s JOIN video_run r ON r.id=s.run_id WHERE r.project_id=? AND s.build_id IS NOT NULL',[project.id])).map(r=>r.build_id))
  const requestedBuild=new URL(request.url,'http://localhost').searchParams.get('build')||(request.body as any)?.build
  if(requestedBuild&&!owned.has(requestedBuild))throw new ApiError(403,'BUILD_NOT_OWNED','不能访问其他项目的 Build')
  if(mutation) {
    const permitted=route==='__studio/mutation'||full&&['__studio/source','__studio/feedback','__studio/artifact-name'].includes(route!)
    if(!permitted)throw new ApiError(403,'STUDIO_WRITE_REJECTED','此操作需要完整 Hypit 工程模式')
    const intent=request.body as any
    if(!full&&intent?.type==='parameter.adjust'&&!supportedParameters.includes(String(intent.parameterId).split(':inspector:').at(-1)!))throw new ApiError(422,'STUDIO_PARAMETER_UNSUPPORTED','该属性请在完整 Hypit 工程模式编辑')
    try{context=JSON.parse(String(request.headers['x-video-context']||''))}catch{throw new ApiError(409,'LEASE_REQUIRED','Studio 写入需要当前编辑权')}
    assertWrite(project,actor,context!);before=(await revision(project.id,context!.expectedRevision)).source
    if(route==='__studio/source'){
      const name=intent.path||'main.svml',files=sourceFiles(before!)
      invariant(name in files&&typeof intent.text==='string','只能修改已有工程源文件')
      await assertBuildReferences(project.id,normalizeSource({...before!,files:{...files,[name]:intent.text}},assets))
    }
  }
  const upstreamPath=relative.startsWith('__studio/')?'/'+relative:session.base+relative
  const response=await fetch(`http://127.0.0.1:${session.port}${upstreamPath}`,{method:request.method,
    headers:{'Content-Type':'application/json',Origin:`http://127.0.0.1:${session.port}`,...(request.headers.range?{Range:request.headers.range}:{})},body:mutation?JSON.stringify(request.body):undefined,signal:AbortSignal.timeout(90_000)})
  const isText=/(text\/|json|javascript)/.test(response.headers.get('content-type')||''); let body: any=isText?await response.text():Buffer.from(await response.arrayBuffer())
  if(/(?:text\/html|javascript)/.test(response.headers.get('content-type')||''))body=body.replace(/(["'`])\/__studio\//g,(_match:string,quote:string)=>quote+session.base+'__studio/')
  if(response.headers.get('content-type')?.includes('json'))body=body.replace(/"\/__studio\//g,'"'+session.base+'__studio/')
  if(mutation&&response.ok&&route!=='__studio/artifact-name') {
    try{
      if(full){
        const files=Object.fromEntries(await Promise.all(Object.keys(sourceFiles(before!)).map(async name=>[name,await readFile(resolve(session.workspace,name),'utf8')]))),feedback=JSON.parse(await readFile(resolve(session.workspace,'FEEDBACK.json'),'utf8'))
        const committed=await commitSource(project.id,actor,context!,assets,{...before!,files,feedback},route==='__studio/feedback'?'Hypit 评论审阅':'Hypit 工程编辑',{studio:request.body,route})
        session.revision=committed.revision
        const result=JSON.parse(body);body=JSON.stringify({...result,galleryRevision:committed.revision})
      }else{
      const svml=await readFile(resolve(session.workspace,'main.svml'),'utf8'),svs=await readFile(resolve(session.workspace,'main.svs'),'utf8')
      const original=(await revision(project.id,context!.expectedRevision)).state
      // Convert native edit intents, then regenerate all source-in/linked audio changes through the common compiler gate.
      const native=projectionChangesWithoutValidation(original,svml,svs,assets)
      const committed=await commitState(project.id,actor,context!,assets,native,'Hypit Studio 编辑',{studio:request.body})
      await writeWorkspace(project.id,'studio',sourceFor(committed.state,assets),assets);session.revision=committed.revision
      body=JSON.stringify({...JSON.parse(body),galleryRevision:committed.revision})
      }
    } catch(error){await writeWorkspace(project.id,'studio',before!,assets);throw error}
  }
  if(route==='__studio/library'&&response.ok){
    let view=JSON.parse(body)
    const filter=(page:any)=>({...page,tasks:(page.tasks||[]).filter((t:any)=>owned.has(t.id)),artifacts:(page.artifacts||[]).filter((a:any)=>owned.has(a.build)&&(!a.ownerBuild||owned.has(a.ownerBuild))).map((a:any)=>({...a,origins:a.origins.filter((o:any)=>owned.has(o.build))}))})
    view=filter(view)
    // Skip empty pages introduced by ownership filtering, retaining the upstream
    // cursor and native grouping. Do not expose foreign records to fill a page.
    for(let page=0;page<100&&view.next&&!view.tasks.length&&!view.artifacts.length;page++){
      const url=new URL(upstreamPath,`http://127.0.0.1:${session.port}`);url.searchParams.set('before',view.next)
      const next=await fetch(url,{signal:AbortSignal.timeout(90000)});if(!next.ok)break;view=filter(await next.json())
    }
    delete view.runtime;view.environment=project.name;body=JSON.stringify(view)
  }
  if(response.headers.get('content-type')?.includes('text/html')&&relative==='') {
    const script=`<script>(()=>{window.__GALLERY_HYPIT_FULL__=${JSON.stringify(full)};let context=null;window.addEventListener('message',e=>{if(e.source===parent&&e.origin===location.origin&&e.data?.type==='gallery-context')context=e.data.context});const fetchOriginal=window.fetch;window.fetch=async(input,init={})=>{let address=typeof input==='string'?input:input instanceof URL?input.href:input.url;if(address.startsWith('/__studio/'))address=${JSON.stringify(session.base)}+address.slice(1);const method=init.method||'GET';if(!['GET','HEAD'].includes(method)){if(!context)throw Error('请先获取编辑权');init={...init,headers:{...init.headers,'X-Video-Context':JSON.stringify({...context,idempotencyKey:crypto.randomUUID()})}}}const response=await fetchOriginal(address,init);if(!['GET','HEAD'].includes(method)&&response.ok){const result=await response.clone().json();if(result.galleryRevision&&context)context={...context,expectedRevision:result.galleryRevision};parent.postMessage({type:'gallery-studio-committed',revision:result.galleryRevision},location.origin)}if(!['GET','HEAD'].includes(method)&&!response.ok){const error=await response.clone().json().catch(()=>({message:'Studio 请求失败'}));parent.postMessage({type:'gallery-studio-error',status:response.status,code:error.code,message:error.message},location.origin)}return response};parent.postMessage({type:'gallery-studio-ready'},location.origin)})();</script>`
    const compact='<style>@media(max-width:920px){.studio-shell[data-view="studio"] .upper-shell{grid-template-columns:minmax(180px,1fr) 8px minmax(180px,240px);overflow:auto}.studio-shell[data-view="studio"] .source-panel,.studio-shell[data-view="studio"] .source-handle{display:none}.studio-shell[data-view="studio"] .workspace-panel{display:flex}.studio-shell[data-view="studio"] .workspace-handle{display:block}}</style>'
    const fullLayout='<style>@media(min-width:921px) and (max-width:1200px){:root{--source-width:330px;--workspace-width:280px}}</style>'
    const theme='<style>:root{color-scheme:light!important;--app:#f4f7fb!important;--panel:#ffffff!important;--panel-raised:#ffffff!important;--field:#f2f6fc!important;--lane:#f8fafd!important;--line:#e2e8f0!important;--line-strong:#cbd7e6!important;--text:#243247!important;--text-2:#52657f!important;--text-3:#718096!important;--subtab-surface:#edf3fb!important;--subtab-active-surface:#ffffff!important;--subtab-active-text:#1677ff!important;--brand-200:#c6dfff!important;--brand-400:#69a9ff!important;--brand-500:#1677ff!important;--brand-600:#0958d9!important;--brand-soft:#eaf3ff!important;--interaction-accent:#1677ff!important;--interaction-pressed:#0958d9!important;--timeline-height:clamp(140px,35vh,310px)!important}html,body,#app{min-width:0!important;min-height:0!important}button[aria-label*="色模式"]{display:none!important}</style>'
    body=body.replace('<head>','<head>'+script+(full?fullLayout:compact)+theme)
  }
  for(const header of ['content-range','accept-ranges','x-hypit-storyboard-count','x-hypit-storyboard-columns','x-hypit-storyboard-rows','x-hypit-storyboard-tile-width','x-hypit-storyboard-tile-height','x-hypit-storyboard-sample-fps'])if(response.headers.has(header))reply.header(header,response.headers.get(header))
  return reply.code(response.status).header('Cache-Control','private, no-store').header('Content-Type',response.headers.get('content-type')||'application/json').send(body)
}
function projectionChangesWithoutValidation(state:State,svml:string,svs:string,assets:Asset[]) {
  // The helper only gathers operations; assets are supplied to its deterministic validation below.
  return nativeProjection(state,svml,svs,assets)
}
export function nativeProjection(state:State,svml:string,svs:string,assets:Asset[]):State {
  // Reuse the same mapper with actual asset facts; no arbitrary source is accepted from the browser.
  const operations:any[]=[];const seen=new Set<string>()
  for(const clip of state.clips){const element=svml.match(new RegExp(`<[^>]+id="c-${clip.id}"[^>]*>`))?.[0];invariant(element,'原片段缺失')
    const at=Number(element.match(/\bat="(\d+)f"/)?.[1]),length=Number(element.match(/\bfor="(\d+)f"/)?.[1])
    if(at!==clip.at||length!==clip.length){const key=clip.link||clip.id;if(!seen.has(key)){operations.push({type:length!==clip.length?'trim_clip':'move_clip',clipId:clip.id,at,...(length!==clip.length?{length}:{})});seen.add(key)}}
    if(clip.kind==='audio'){const volume=Number(element.match(/\bgain="([^"\s]+)"/)?.[1]),fadeIn=Number(element.match(/\bfade-in="(\d+)f"/)?.[1]),fadeOut=Number(element.match(/\bfade-out="(\d+)f"/)?.[1]);if(volume!==(clip.muted?0:clip.volume)||fadeIn!==clip.fadeIn||fadeOut!==clip.fadeOut)operations.push({type:'set_audio',clipId:clip.id,volume,muted:volume===0,fadeIn,fadeOut})}
    const block=svs.match(new RegExp(`(?:media|text)\\.c-${clip.id}\\s*\\{([^}]+)\\}`))?.[1]
    if(block){const props=Object.fromEntries([...block.matchAll(/([a-z-]+):\s*([^;]+);/g)].map(m=>[m[1],m[2]!.trim()]));if(props.fit&&props.fit!==clip.fit)operations.push({type:'set_layout',clipId:clip.id,fit:props.fit});if(clip.kind==='text'&&(Number(props.size)!==clip.fontSize*clip.scale||props.fill!==clip.color))operations.push({type:'edit_subtitles',clipId:clip.id,fontSize:Number(props.size)/clip.scale,color:props.fill})}
    if(clip.kind!=='audio'){
      const frame=svml.match(new RegExp(`<space:AnchoredFrame[^>]+id="frame-${clip.id}"[^>]*>`))?.[0];invariant(frame,'Studio 不支持替换画面坐标定义')
      const px=(name:string)=>Number(frame.match(new RegExp(`\\b${name}="(-?[\\d.]+)px"`))?.[1])
      const x=px('x')-state.width/2,y=px('y')-state.height/2,scale=px('width')/state.width
      if(x!==clip.x||y!==clip.y||clip.kind!=='text'&&scale!==clip.scale)operations.push(clip.kind==='text'?{type:'edit_subtitles',clipId:clip.id,x,y}:{type:'set_layout',clipId:clip.id,x,y,scale})
    }
    if(clip.kind==='text'){
      const inner=svml.match(new RegExp(`<typo:Area[^>]+id="c-${clip.id}"[^>]*>([\\s\\S]*?)</typo:Area>`))?.[1]
      const content=inner?.match(/^\s*<typo:P>((?:[^<]|<typo:Break\s*\/>)*?)<\/typo:P>\s*$/)?.[1];invariant(content!==undefined,'请在图库属性面板编辑复杂文字')
      const text=content.replace(/<typo:Break\s*\/>/g,'\n').replaceAll('&lt;','<').replaceAll('&gt;','>').replaceAll('&quot;','"').replaceAll('&amp;','&')
      if(text!==clip.text)operations.push({type:'edit_subtitles',clipId:clip.id,text})
    }
  }
  return applyOperations(state,operations,assets,config.maxFrames)
}
export function closeStudios(){for(const session of sessions.values())session.process.kill()}
export function studioSession(id:string){return sessions.get(id)}

