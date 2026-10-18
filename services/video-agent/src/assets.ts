import { mkdir, readFile, writeFile, stat, copyFile, unlink, rm } from 'node:fs/promises'
import { createHash, randomUUID } from 'node:crypto'
import { resolve, extname } from 'node:path'
import { createReadStream } from 'node:fs'
import sharp, { type Metadata } from 'sharp'
import { rows, transaction, json, db } from './db.js'
import { config, dataRoot, serviceKey } from './config.js'
import { ticket, type Actor, type Scope } from './auth.js'
import { command, ffmpegPath, ffprobePath } from './hypit.js'
import { ApiError, invariant } from './errors.js'
import type { Asset } from './timeline.js'
export async function probe(path: string) {
  return JSON.parse(await command(ffprobePath, ['-v','error','-show_format','-show_streams','-of','json',path]))
}
export async function storeObject(actor: Actor, scope: Scope, path: string, extension: string) {
  return storeObjectTracked(actor, scope, path, extension)
}
async function storeObjectTracked(actor: Actor, scope: Scope, path: string, extension: string, created?: Set<string>) {
  const bytes = await readFile(path), digest = createHash('sha256').update(bytes).digest('hex')
  const scopeName = scope.space_id ? `space-${scope.space_id}` : `public-${scope.owner_id || actor.userId}`
  return transaction(async connection => {
    await connection.query('INSERT IGNORE INTO video_quota(scope) VALUES (?)', [scopeName])
    const quota = (await rows('SELECT * FROM video_quota WHERE scope=? FOR UPDATE', [scopeName], connection))[0]!
    const existing = (await rows('SELECT * FROM video_object WHERE scope=? AND digest=?', [scopeName,digest], connection))[0]
    if (existing) return existing.object_key as string
    if (Number(quota.bytes) + bytes.length > config.quotaBytes) throw new ApiError(413, 'VIDEO_QUOTA_EXCEEDED', '视频空间字节配额不足')
    const key = `videos/${scopeName}/${digest}.${extension}`
    const { url } = await ticket(actor, scope, key, 'PUT')
    const response = await fetch(url, { method: 'PUT', body: new Uint8Array(bytes), signal: AbortSignal.timeout(180_000) })
    if (!response.ok) throw new ApiError(502, 'COS_UPLOAD_FAILED', `COS 上传失败 (${response.status})`)
    await connection.query('INSERT INTO video_object(scope,digest,object_key,size) VALUES (?,?,?,?)', [scopeName,digest,key,bytes.length])
    await connection.query('UPDATE video_quota SET bytes=bytes+? WHERE scope=?', [bytes.length,scopeName])
    created?.add(key)
    return key
  })
}
async function deleteObject(actor: Actor, scope: Scope, key: string) {
  const {url}=await ticket(actor,scope,key,'DELETE')
  const response=await fetch(url,{method:'DELETE',signal:AbortSignal.timeout(60_000)})
  if(!response.ok && response.status!==404)throw new ApiError(502,'COS_DELETE_FAILED',`COS 清理失败 (${response.status})`)
  await transaction(async connection=>{
    const found=(await rows('SELECT scope,size FROM video_object WHERE object_key=? FOR UPDATE',[key],connection))[0]
    if(!found)return
    await connection.query('DELETE FROM video_object WHERE object_key=?',[key])
    await connection.query('UPDATE video_quota SET bytes=GREATEST(bytes-?,0) WHERE scope=?',[found.size,found.scope])
  })
}
export async function importAsset(project: any, actor: Actor, input: string, name: string, purpose?: 'speech'): Promise<Asset> {
  const id = randomUUID(), directory = resolve(dataRoot, 'assets', id)
  await mkdir(directory, {recursive: true})
  try { return await importAssetInDirectory(project, actor, input, name, purpose, id, directory) }
  catch (error) { await rm(directory, {recursive:true, force:true}).catch(()=>{}); throw error }
}
async function importAssetInDirectory(project: any, actor: Actor, input: string, name: string, purpose: 'speech' | undefined, id: string, directory: string): Promise<Asset> {
  const original = resolve(directory, 'original' + (extname(name).match(/^\.[a-zA-Z0-9]{1,8}$/) ? extname(name).toLowerCase() : '.bin'))
  await copyFile(input, original)
  const size = (await stat(original)).size
  invariant(size > 0 && size <= config.maxAssetBytes, '单素材不能超过上传大小限制')
  let imageInfo: Metadata | undefined
  try { imageInfo = await sharp(original).metadata() } catch { /* Moving media is inspected with ffprobe. */ }
  let asset: Asset
  if (imageInfo?.width && imageInfo.height && (imageInfo.pages === undefined || imageInfo.pages === 1)) {
    const path = resolve(directory, 'image.png')
    const normalized = await sharp(original).rotate().png().toBuffer({resolveWithObject:true}).catch(()=>{throw new ApiError(400,'INVALID_MEDIA','图片损坏或无法解码')})
    await writeFile(path,normalized.data)
    asset = { id, name: name.slice(0,200), kind:'image', width:normalized.info.width,height:normalized.info.height, frames:0,hasAudio:false,path,size }
  } else {
    const info = await probe(original).catch(error=>{if(error instanceof ApiError&&error.code==='MEDIA_PROCESS_FAILED')throw new ApiError(400,'INVALID_MEDIA','文件损坏或媒体格式不受支持');throw error}), visual = info.streams.find((s:any) => s.codec_type === 'video' && !s.disposition?.attached_pic)
    const audio = info.streams.find((s:any) => s.codec_type === 'audio')
    invariant(visual || audio, '文件不包含可用的音视频流')
    const seconds = Number(visual?.duration || audio?.duration || info.format.duration)
    invariant(Number.isFinite(seconds) && seconds > 0 && seconds <= 3600, '媒体时长无效或超过一小时')
    const path = resolve(directory, visual ? 'video.mp4' : 'audio.wav')
    const frames = Math.ceil(seconds * 24)
    if (visual) {
      const silent=audio?resolve(directory,'video-silent.mp4'):path
      // Do not combine unbounded apad with -shortest: encoder buffering can append
      // several seconds of AAC after the last video frame, extending browser playback.
      await command(ffmpegPath, ['-y','-i',original,'-map',`0:${visual.index}`,'-an',
        '-vf','fps=24,scale=trunc(iw/2)*2:trunc(ih/2)*2','-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p',
        '-movflags','+faststart',silent])
      const normalized = await probe(silent), video = normalized.streams.find((s:any)=>s.codec_type ==='video')
      asset = { id,name:name.slice(0,200),kind:'video',width:video.width,height:video.height,frames:Number(video.nb_frames || Math.round(Number(video.duration)*24)),hasAudio:!!audio,path,size }
      if (audio) {
        asset.audioPath = resolve(directory,'source.wav')
        await command(ffmpegPath,['-y','-i',original,'-map',`0:${audio.index}`,'-vn','-ar','48000','-ac','2','-af',`aresample=48000:first_pts=0,apad,atrim=end=${asset.frames/24}`,'-c:a','pcm_s16le',asset.audioPath])
        await command(ffmpegPath,['-y','-i',silent,'-i',asset.audioPath,'-map','0:v:0','-map','1:a:0','-c:v','copy','-c:a','aac','-ar','48000','-ac','2','-t',String(asset.frames/24),'-movflags','+faststart',path])
        const finished=await probe(path),outputAudio=finished.streams.find((s:any)=>s.codec_type==='audio')
        invariant(outputAudio&&Math.abs(Number(outputAudio.duration)-asset.frames/24)<=1/24,'导入素材的音频尾部与画面不一致')
        invariant(Math.abs(Number(finished.format.duration)-asset.frames/24)<=1/24,'导入素材的播放时长与画面不一致')
        await unlink(silent)
      }
    } else {
      await command(ffmpegPath,['-y','-i',original,'-map',`0:${audio.index}`,'-ar','48000','-ac','2','-af',`apad,atrim=end=${frames/24}`,'-c:a','pcm_s16le',path])
      asset = {id,name:name.slice(0,200),kind:'audio',width:0,height:0,frames,hasAudio:true,path,size}
    }
  }
  if(purpose)asset.purpose=purpose
  const createdKeys=new Set<string>()
  try {
    // Upload every retained immutable object before the asset can be inserted into a project.
    asset.originalKey = await storeObjectTracked(actor,project,original,extname(original).slice(1) || 'bin',createdKeys)
    asset.objectKey = await storeObjectTracked(actor,project,asset.path,asset.kind==='image'?'png':asset.kind==='video'?'mp4':'wav',createdKeys)
    if (asset.audioPath) asset.audioKey = await storeObjectTracked(actor,project,asset.audioPath,'wav',createdKeys)
    if (asset.kind==='video' || asset.kind==='image') {
      const poster = resolve(directory,'poster.jpg')
      if (asset.kind==='image') await sharp(asset.path).resize({width:480,withoutEnlargement:true}).flatten({background:'#000000'}).jpeg().toFile(poster)
      else await command(ffmpegPath,['-y','-i',asset.path,'-frames:v','1','-vf','scale=480:-1',poster])
      asset.posterKey = await storeObjectTracked(actor,project,poster,'jpg',createdKeys)
    }
    await db.query('INSERT INTO video_asset(id,project_id,name,kind,metadata) VALUES (?,?,?,?,?)',[id,project.id,asset.name,asset.kind,JSON.stringify(asset)])
    return asset
  } catch (error) {
    for (const key of createdKeys) { try { await deleteObject(actor,project,key) } catch { /* 清理失败由下一次一致性任务处理 */ } }
    throw error
  }
}
export async function projectAssets(projectId: string, actor?: Actor, scope?: Scope): Promise<Asset[]> {
  const assets = (await rows('SELECT metadata FROM video_asset WHERE project_id=? ORDER BY created_at', [projectId])).map(row=>json<Asset>(row.metadata))
  for (const asset of assets) {
    try { await stat(asset.path) } catch {
      if (!actor || !scope || !asset.objectKey) throw new ApiError(422,'ASSET_NOT_CACHED','素材缓存缺失且无法重新获取')
      const directory = resolve(dataRoot,'assets',asset.id); await mkdir(directory,{recursive:true})
      asset.path = resolve(directory, asset.kind==='image'?'image.png':asset.kind==='video'?'video.mp4':'audio.wav')
      await downloadObject(actor,scope,asset.objectKey,asset.path)
    }
    if (asset.audioKey && asset.audioPath) try { await stat(asset.audioPath) } catch { if (actor && scope) await downloadObject(actor,scope,asset.audioKey,asset.audioPath) }
  }
  return assets
}
export async function downloadObject(actor: Actor, scope: Scope, key: string, path: string) {
  const {url}=await ticket(actor,scope,key,'GET'); const response=await fetch(url,{signal:AbortSignal.timeout(180_000)})
  if(!response.ok) throw new ApiError(502,'COS_READ_FAILED',`COS 下载失败 (${response.status})`)
  await mkdir(resolve(path,'..'),{recursive:true}); await writeFile(path,Buffer.from(await response.arrayBuffer()))
}
/** Repair only the browser rendition of older imports. Immutable render/audio sources remain intact. */
export async function repairVideoPlayback(project:any,actor:Actor) {
  const assets=await projectAssets(project.id,actor,project),repaired:string[]=[]
  for(const asset of assets.filter(a=>a.kind==='video'&&!a.browserObjectKey)){
    const info=await probe(asset.path),expected=asset.frames/24
    if(Math.abs(Number(info.format.duration)-expected)<=1/24)continue
    const rendition=resolve(dataRoot,'assets',asset.id,'browser.mp4')
    const args=['-y','-i',asset.path]
    if(asset.audioPath)args.push('-i',asset.audioPath)
    args.push('-map','0:v:0',...(asset.audioPath?['-map','1:a:0']:['-map','0:a?']),'-c:v','copy','-c:a','aac','-ar','48000','-ac','2','-t',String(expected),'-movflags','+faststart',rendition)
    await command(ffmpegPath,args)
    const result=await probe(rendition)
    invariant(Math.abs(Number(result.format.duration)-expected)<=1/24,'已有素材的播放版本时长不一致')
    const key=await storeObject(actor,project,rendition,'mp4')
    await transaction(async connection=>{
      await connection.query("UPDATE video_asset SET metadata=JSON_SET(metadata,'$.browserObjectKey',?) WHERE id=? AND project_id=?",[key,asset.id,project.id])
      // Directly uploaded formal videos refer to this asset; exports are separate objects.
      await connection.query("UPDATE video_record SET metadata=JSON_SET(metadata,'$.browserObjectKey',?) WHERE project_id=? AND JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.id'))=?",[key,project.id,asset.id])
    })
    repaired.push(asset.id)
  }
  return repaired
}
export async function importPicture(project: any, actor: Actor, pictureId: string) {
  invariant(/^\d+$/.test(pictureId),'无效图片标识')
  const response=await fetch(`${config.javaUrl}/internal/video/pictures/${pictureId}`,{headers:{Cookie:actor.cookie,'X-Video-Service-Key':serviceKey},signal:AbortSignal.timeout(60_000)})
  invariant(response.ok && response.headers.get('content-type')==='application/octet-stream','无法授权访问原图片')
  const temporary=resolve(dataRoot,`picture-${randomUUID()}.bin`)
  try {
    await writeFile(temporary,Buffer.from(await response.arrayBuffer()))
    return await importAsset(project,actor,temporary,`图库图片-${pictureId}.png`)
  } finally {
    await unlink(temporary).catch(()=>{})
  }
}

