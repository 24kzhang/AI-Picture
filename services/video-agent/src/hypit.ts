import { spawn } from 'node:child_process'
import { mkdir, writeFile, readFile, copyFile, access, unlink, symlink, readdir, cp } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { resolve, dirname, basename } from 'node:path'
import { dataRoot, root, config } from './config.js'
import { ApiError } from './errors.js'
import { stateSchema, duration, type State, type Asset } from './timeline.js'
import sharp from 'sharp'
import { sourceFiles, runEntry } from './source-bundle.js'
import {patchRenderPortSource} from './render-port-patch.js'

export const hypitRoot = resolve(root, 'services/video-agent/node_modules/@hypit/hypit')
export const ffmpegPath = process.env.VIDEO_FFMPEG || (process.platform === 'win32' && existsSync(resolve(root,'runtime-data/media-tools/ffmpeg.exe')) ? resolve(root,'runtime-data/media-tools/ffmpeg.exe') : 'ffmpeg')
export const ffprobePath = process.env.VIDEO_FFPROBE || (process.platform === 'win32' ? resolve(root, 'services/video-agent/node_modules/@ffprobe-installer/win32-x64/ffprobe.exe') : 'ffprobe')
export const chromePath = process.env.VIDEO_CHROME
let resultsReady:Promise<void>|undefined
async function prepareResults(){
  resultsReady??=(async()=>{
    const target=resolve(dataRoot,'hypit-results');await mkdir(target,{recursive:true})
    // Preserve old immutable Results; no existing revision or media is deleted.
    const projects=await readdir(resolve(dataRoot,'projects'),{withFileTypes:true}).catch(()=>[])
    for(const project of projects.filter(p=>p.isDirectory()))for(const version of await readdir(resolve(dataRoot,'projects',project.name),{withFileTypes:true})){
      if(!version.isDirectory())continue
      const previous=resolve(dataRoot,'projects',project.name,version.name,'.hypit/results')
      if(existsSync(previous))await cp(previous,target,{recursive:true,force:false,errorOnExist:false})
    }
    // Repair the earlier adapter's receipt serialization, retaining its original
    // bytes. The acknowledged task identity and all Outputs remain unchanged.
    for(const date of await readdir(target,{withFileTypes:true}))if(date.isDirectory())for(const build of await readdir(resolve(target,date.name),{withFileTypes:true})){
      if(!build.isDirectory())continue
      const path=resolve(target,date.name,build.name,'result.json');if(!existsSync(path))continue
      const original=await readFile(path,'utf8'),result=JSON.parse(original);let repaired=false
      for(const operation of result.operations||[])if(operation.endpoint==='gallery.asr'&&!operation.receipt?.id&&typeof operation.receipt?.taskId==='string'&&operation.receipt.taskId.trim()){operation.receipt.id=operation.receipt.taskId;repaired=true}
      if(repaired){await writeFile(path+'.legacy-receipt',original,{flag:'wx'}).catch(error=>{if(error.code!=='EEXIST')throw error});await writeFile(path,JSON.stringify(result,null,2),'utf8')}
    }
  })();try{await resultsReady}catch(error){resultsReady=undefined;throw error}
}
export async function patchMediaExecution() {
  const manifest=JSON.parse(await readFile(resolve(hypitRoot,'package.json'),'utf8'))
  if(manifest.version!=='0.2.16')throw new ApiError(500,'HYPIT_VERSION_MISMATCH','媒体补丁仅支持 Hypit 0.2.16')
  const captureFile=resolve(hypitRoot,'packages/provider-hyperframes-local/src/capture.ts')
  const captureSource=await readFile(captureFile,'utf8'),safeCaptureSource=patchRenderPortSource(captureSource)
  if(safeCaptureSource!==captureSource)await writeFile(captureFile,safeCaptureSource,'utf8')
  const file=resolve(hypitRoot,'packages/media-execution/src/execute.ts')
  const source=await readFile(file,'utf8')
  const needle='assert(visual.role === "moving" && visual.timingStatus === "admissible"'
  if(!source.includes('gallery-single-frame-mux')&&!source.includes(needle))throw new ApiError(500,'HYPIT_PATCH_FAILED','单帧合成补丁定位失败')
  // The probe classifies one decoded frame as still. This mux already requires a video
  // artifact with exactly the declared count and dimensions; retain all timing checks.
  if(!source.includes('gallery-single-frame-mux'))await writeFile(file,source.replace(needle,'// gallery-single-frame-mux\n    assert((visual.role === "moving" || need.visual.frameCount === 1 && visual.role === "still") && visual.timingStatus === "admissible"'),'utf8')
  const probeFile=resolve(hypitRoot,'packages/media-execution/src/probe.ts'),probeSource=await readFile(probeFile,'utf8')
  if(!probeSource.includes('gallery-single-frame-video')){
    const role=': measured.decodedUnitCount >= 2 ? "moving" : "still";'
    if(!probeSource.includes(role))throw new ApiError(500,'HYPIT_PATCH_FAILED','单帧视频识别补丁定位失败')
    // An admitted video container with one timed frame remains a video. Images
    // and attached cover art keep their original roles; timing checks still apply.
    await writeFile(probeFile,probeSource.replace(role,': measured.decodedUnitCount >= 2 || measured.decodedUnitCount === 1 && args.source.mediaType.startsWith("video/") ? "moving" : "still"; // gallery-single-frame-video'),'utf8')
  }
  const storeFile=resolve(hypitRoot,'packages/build-result/src/store.ts'),storeSource=await readFile(storeFile,'utf8')
  if(!storeSource.includes('gallery-windows-result-rename')){
    const rename='      await rename(temporary, directory);'
    if(!storeSource.includes(rename))throw new ApiError(500,'HYPIT_PATCH_FAILED','Windows 结果目录补丁定位失败')
    await writeFile(storeFile,storeSource.replace(rename,`      // gallery-windows-result-rename: retry the same atomic publication, never the Build.
      for (let attempt = 0; ; attempt += 1) {
        try { await rename(temporary, directory); break; }
        catch (error) {
          if (process.platform !== "win32" || attempt >= 4 || !["EPERM", "EBUSY"].includes((error as { code?: string }).code ?? "")) throw error;
          await new Promise<void>((accept) => setTimeout(accept, 50 * 2 ** attempt));
        }
      }`),'utf8')
  }
}
const xml = (text: unknown) => String(text).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;').replaceAll('>', '&gt;')
export type SourceBundle = { 'main.svml': string; 'main.svs': string; 'build.svrun': string; assetIds: string[]; files?:Record<string,string>; entry?:string; mode?:'timeline'|'hypit'; state?:State; feedback?:{comments:Record<string,unknown>[]} }
export function readState(source: SourceBundle): State {
  if(source.mode==='hypit'&&source.state)return stateSchema.parse(source.state)
  const match = source['main.svml'].match(/<!-- gallery-state:([A-Za-z0-9+/=]+) -->/)
  if (!match) throw new ApiError(500, 'INVALID_SOURCE', '项目源文件缺少编辑元数据')
  return stateSchema.parse(JSON.parse(Buffer.from(match[1]!, 'base64').toString('utf8')))
}
export function sourceFor(state: State, assets: Asset[]): SourceBundle {
  const svml: string[] = ['<?svml using="@hypit/markup@1"?>', '<svml>',
    `<!-- gallery-state:${Buffer.from(JSON.stringify(state)).toString('base64')} -->`,
    ...[['time','timeline-author'],['space','spatial'],['media','media'],['pipeline','media-pipeline'],['visual','media-track'],['audio','audio-track'],['typo','typography-track'],['film','film'],['render','render-hyperframes']].map(([name, pkg]) => `<import as="${name}" from="@hypit/${pkg}@1"/>`),
    '<import as="style" source="./main.svs"/>', '<time:Clock id="clock" frame-rate="24"/>',
    `<time:Timeline id="program" clock={clock} end="${duration(state)}f"/>`,
    `<space:Canvas id="canvas" width="${state.width}" height="${state.height}"/>`,
  ]
  const svs = ['<?svml using="@hypit/svs@1"?>', '<sheet version="1">', 'film.main { background: #000000; }']
  const references: string[] = ['background.visual']
  svml.push('<media:Image id="black" src="./assets/black.png"/>', '<space:Extent id="black-extent" width="1" height="1"/>', `<space:Frame id="full" within={canvas} left="0px" top="0px" right="${state.width}px" bottom="${state.height}px"/>`,
    `<visual:Track id="background" timeline={program.timeline} canvas={canvas}><visual:Item id="background-image" image={black} extent={black-extent} frame={full} at="0f" for="${duration(state)}f" appearance={style.media.background}/></visual:Track>`)
  svs.push('media.background { stack-order: 0; fit: stretch; }')
  const used = assets.filter(a => state.clips.some(c => c.assetId === a.id))
  for (const asset of used) {
    const key = `a-${asset.id}`
    const file = `./assets/${asset.id}${asset.kind === 'image' ? '.png' : asset.kind === 'audio' ? '.wav' : '.mp4'}`
    svml.push(`<media:${asset.kind === 'image' ? 'Image' : asset.kind === 'audio' ? 'Audio' : 'Video'} id="${key}" src="${file}"/>`)
    if (asset.kind === 'image') svml.push(`<space:Extent id="extent-${asset.id}" width="${asset.width}" height="${asset.height}"/>`)
    else svml.push(`<pipeline:Normalize id="n-${asset.id}" source={${key}} video="${asset.kind === 'video' ? 'primary-moving' : 'none'}" audio="${asset.hasAudio ? 'default' : 'none'}" span-authority="${asset.kind === 'video' ? 'video' : 'audio'}" clock={clock}/>`)
  }
  if (state.clips.some(c => c.kind === 'text')) svml.push('<media:Font id="font" src="./assets/font.ttf" weight="400" style="normal"/>')
  for (const [index, clip] of state.clips.entries()) {
    const id = `c-${clip.id}`
    const trackOrder = state.tracks.findIndex(t => t.id === clip.trackId)
    const stack = trackOrder * 1000 + index + 1
    if (clip.kind !== 'audio') {
      const width = state.width * clip.scale * (clip.kind === 'text' ? .9 : 1)
      const height = state.height * clip.scale
      svml.push(`<space:AnchoredFrame id="frame-${clip.id}" within={canvas} x="${state.width / 2 + clip.x}px" y="${state.height / 2 + clip.y}px" width="${width}px" height="${height}px" anchor="center"/>`)
    }
    if (clip.kind === 'video' || clip.kind === 'image') {
      svs.push(`media.${id} { stack-order: ${stack}; fit: ${clip.fit};${clip.kind === 'video' ? ` playback: once-start; trim-start: ${clip.in}; trim-end: ${clip.in + clip.length};` : ''} }`)
      svml.push(`<visual:Track id="v-${clip.id}" timeline={program.timeline} canvas={canvas}><visual:Item id="${id}" ${clip.kind === 'image' ? `image={a-${clip.assetId}} extent={extent-${clip.assetId}}` : `media={n-${clip.assetId}.media}`} frame={frame-${clip.id}} at="${clip.at}f" for="${clip.length}f" appearance={style.media.${id}}/></visual:Track>`)
      references.push(`v-${clip.id}.visual`)
    } else if (clip.kind === 'audio') {
      svml.push(`<audio:Track id="s-${clip.id}" timeline={program.timeline}><audio:Item id="${id}" source={n-${clip.assetId}.media} at="${clip.at}f" for="${clip.length}f" trim-start="${clip.in}f" trim-end="${clip.in + clip.length}f" gain="${clip.muted ? 0 : clip.volume}" fade-in="${clip.fadeIn}f" fade-out="${clip.fadeOut}f" playback="once"/></audio:Track>`)
      references.push(`s-${clip.id}.audio`)
    } else {
      svs.push(`text.${id} { stack-order: ${stack}; size: ${clip.fontSize * clip.scale}; fill: ${clip.color}; align: center; block-align: center; block-size: hug; wrap: word; line-height: 1.2; }`)
      // SVML collapses source whitespace. Explicit breaks preserve authored lines,
      // including empty lines, inside the same canvas-sized flow box as the preview.
      const content = (clip.text || '').split(/\r\n|\r|\n/).map(xml).join('<typo:Break/>')
      svml.push(`<typo:Style id="style-${clip.id}" font={font} recipe={style.text.${id}}/>`, `<typo:Track id="t-${clip.id}" timeline={program.timeline}><typo:Area id="${id}" placement={frame-${clip.id}} style={style-${clip.id}} at="${clip.at}f" for="${clip.length}f"><typo:P>${content}</typo:P></typo:Area></typo:Track>`)
      references.push(`t-${clip.id}.track`)
    }
  }
  svml.push(`<film:Film id="main" canvas={canvas} timeline={program.timeline} appearance={style.film.main}>${references.map(r => `<film:Track source={${r}}/>`).join('')}</film:Film>`, '<render:Video id="final" composition={main.composition} timeline={program.timeline}/>', '</svml>')
  return { 'main.svml': svml.join('\n'), 'main.svs': [...svs, '</sheet>'].join('\n'), 'build.svrun': '<?svml using="@hypit/run-markup@1"?>\n<svrun version="1"><author source="./main.svml"/><target output="final.video"/></svrun>\n', assetIds: used.map(a => a.id) }
}
export async function command(program: string, args: string[], cwd?: string, log?: string, onOutput?: (text: string) => void, timeout = 30 * 60_000): Promise<string> {
  return new Promise((accept, reject) => {
    const child = spawn(program, args, { cwd, shell: false, windowsHide: true, env: { ...process.env, NODE_OPTIONS: '--use-system-ca', HYPIT_STATE_HOME: resolve(dataRoot, 'hypit-state'), npm_config_cache: resolve(root, 'runtime-data/npm-cache'), npm_config_registry: 'https://registry.npmmirror.com' } })
    let output = '', error = ''
    const timer = setTimeout(() => { child.kill(); reject(new ApiError(504, 'PROCESS_TIMEOUT', '媒体处理超时')) }, timeout)
    child.stdout.on('data', chunk => { output += chunk.toString('utf8'); onOutput?.(chunk.toString('utf8')) })
    child.stderr.on('data', chunk => { error = (error + chunk.toString('utf8')).slice(-12000); onOutput?.(chunk.toString('utf8')) })
    child.on('error', cause => { clearTimeout(timer); reject(cause) })
    child.on('close', async code => { clearTimeout(timer); if (log) await writeFile(log, output + '\n' + error).catch(() => {}); code === 0 ? accept(output) : reject(new ApiError(422, 'MEDIA_PROCESS_FAILED', error || output || `进程退出 ${code}`)) })
  })
}
export const hypit = (args: string[], workspace: string, log?: string, onOutput?: (s: string) => void) =>
  command(process.execPath, [resolve(hypitRoot, 'bin/hypit.mjs'), ...args,
    ...(args[0] === 'vocabulary' ? [] : ['--workspace', workspace]), '--json'], workspace, log, onOutput)
export async function writeWorkspace(projectId: string, revision: number | string, source: SourceBundle, assets: Asset[]) {
  await prepareResults()
  const workspace = resolve(dataRoot, 'projects', projectId, String(revision))
  await mkdir(resolve(workspace, 'assets'), { recursive: true })
  await sharp({ create: { width: 1, height: 1, channels: 3, background: '#000000' } }).png().toFile(resolve(workspace, 'assets/black.png'))
  const files=sourceFiles(source),manifestPath=resolve(workspace,'gallery-source-manifest.json')
  let previous:string[]=[];try{previous=JSON.parse(await readFile(manifestPath,'utf8'))}catch{}
  for(const name of previous)if(!(name in files))await unlink(resolve(workspace,name)).catch(()=>{})
  for (const [name,text] of Object.entries(files)){await mkdir(dirname(resolve(workspace,name)),{recursive:true});await writeFile(resolve(workspace,name),text,'utf8')}
  await writeFile(manifestPath,JSON.stringify(Object.keys(files)),'utf8')
  await writeFile(resolve(workspace,'FEEDBACK.json'),JSON.stringify(source.feedback||{comments:[]}),'utf8')
  await writeFile(resolve(workspace, 'package.json'), JSON.stringify({ name: 'gallery-video-project', private: true, type: 'module' }))
  await writeFile(resolve(workspace,'hypit.results.json'),JSON.stringify({format:'hypit.build-results@1',use:'@hypit/build-result-fs',config:{path:resolve(dataRoot,'hypit-results')}}),'utf8')
  const providerDirectory=resolve(workspace,'node_modules/@gallery/provider-self-hosted')
  await mkdir(dirname(providerDirectory),{recursive:true})
  if(!existsSync(providerDirectory))await symlink(resolve(root,'services/video-agent/packages/hypit-self-hosted'),providerDirectory,process.platform==='win32'?'junction':'dir').catch(async error=>{if(!existsSync(providerDirectory))throw error})
  for (const id of source.assetIds) {
    const asset = assets.find(a => a.id === id); if (!asset) throw new ApiError(422, 'MISSING_ASSET', '版本引用的素材缺失')
    await copyFile(asset.path, resolve(workspace, 'assets', id + (asset.kind === 'image' ? '.png' : asset.kind === 'audio' ? '.wav' : '.mp4')))
  }
  if (Object.values(files).some(text=>text.includes('font.ttf'))) {
    const font = process.env.VIDEO_FONT || (process.platform === 'win32' ? 'C:/Windows/Fonts/simhei.ttf' : '/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttf')
    await copyFile(font, resolve(workspace, 'assets/font.ttf'))
  }
  await writeFile(resolve(workspace, 'hypit.runtime.json'), JSON.stringify({ format: 'hypit.runtime-local@1', dataRoot: resolve(dataRoot, 'hypit-runtime'),
    endpoints: { 'gallery.asr':{use:'@gallery/provider-self-hosted',config:{baseUrl:config.modelUrl}},'media.local': { use: '@hypit/provider-media-local', config: { ffmpegPath, ffprobePath, defaultConcurrency: 1 } },
      'hyperframes.local': { use: '@hypit/provider-hyperframes-local', config: { workers: 2, defaultConcurrency: 1, browserCapacity: 2, ...(chromePath ? {chromePath} : {browserCacheDirectory: resolve(dataRoot, 'browser'), browserDownloadBaseUrl: 'https://registry.npmmirror.com/-/binary/chrome-for-testing'}), ffmpegPath, ffprobePath, browserGpu: 'software' } } } }))
  return workspace
}
export async function checkSource(projectId: string, source: SourceBundle, assets: Asset[]) {
  const workspace = await writeWorkspace(projectId, `candidate-${crypto.randomUUID()}`, source, assets)
  await hypit(['check', resolve(workspace, runEntry(source))], workspace)
  return workspace
}





