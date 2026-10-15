import { z } from 'zod'
import { randomUUID } from 'node:crypto'
import { invariant } from './errors.js'
export const FPS = 24
const frame = z.number().int().nonnegative()
export const clipSchema = z.object({
  id: z.string().uuid(), trackId: z.string().min(1).max(100), assetId: z.string().uuid().optional(),
  kind: z.enum(['video', 'image', 'audio', 'text']), name: z.string().max(200),
  at: frame, length: frame.positive(), in: frame.default(0), link: z.string().uuid().optional(), sourceClipId: z.string().uuid().optional(),
  x: z.number().finite().default(0), y: z.number().finite().default(0), scale: z.number().min(0.05).max(10).default(1),
  fit: z.enum(['contain', 'cover', 'stretch']).default('contain'), volume: z.number().min(0).max(4).default(1),
  muted: z.boolean().default(false), fadeIn: frame.default(0), fadeOut: frame.default(0),
  text: z.string().max(2000).optional(), fontSize: z.number().min(8).max(300).default(42), color: z.string().regex(/^#[0-9a-fA-F]{6}$/).default('#ffffff'),
  subtitle: z.boolean().optional(), precision: z.enum(['character', 'sentence']).optional(),
}).strict()
export const stateSchema = z.object({ fps: z.literal(24), width: z.number().int().positive().max(2048), height: z.number().int().positive().max(2048),
  tracks: z.array(z.object({ id: z.string().min(1).max(100), name: z.string().max(100), kind: z.enum(['visual', 'audio', 'text']) }).strict()).min(1).max(30),
  clips: z.array(clipSchema).max(500),
}).strict()
export type Clip = z.infer<typeof clipSchema>
export type State = z.infer<typeof stateSchema>
export type Asset = { id: string; name: string; kind: 'video' | 'image' | 'audio'; frames: number; width: number; height: number; hasAudio: boolean; path: string; audioPath?: string; objectKey?: string; posterKey?: string; originalKey?: string; audioKey?: string; size: number; [key: string]: any }
export const operationSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('insert_clip'), assetId: z.string().uuid().optional(), trackId: z.string(), at: frame, length: frame.positive().optional(), in: frame.optional(), text: z.string().max(2000).optional(), replaceOriginal: z.boolean().optional() }).strict(),
  z.object({ type: z.literal('trim_clip'), clipId: z.string().uuid(), at: frame, length: frame.positive() }).strict(),
  z.object({ type: z.literal('split_clip'), clipId: z.string().uuid(), frame }).strict(),
  z.object({ type: z.literal('move_clip'), clipId: z.string().uuid(), at: frame, trackId: z.string().optional() }).strict(),
  z.object({ type: z.literal('duplicate_clip'), clipId: z.string().uuid(), at: frame.optional() }).strict(),
  z.object({ type: z.literal('delete_clip'), clipId: z.string().uuid() }).strict(),
  z.object({ type: z.literal('unlink_clip'), clipId: z.string().uuid() }).strict(),
  z.object({ type: z.literal('set_layout'), clipId: z.string().uuid().optional(), width: z.number().int().positive().max(2048).optional(), height: z.number().int().positive().max(2048).optional(), x: z.number().finite().optional(), y: z.number().finite().optional(), scale: z.number().min(.05).max(10).optional(), fit: z.enum(['contain', 'cover', 'stretch']).optional() }).strict(),
  z.object({ type: z.literal('set_audio'), clipId: z.string().uuid(), volume: z.number().min(0).max(4).optional(), muted: z.boolean().optional(), fadeIn: frame.optional(), fadeOut: frame.optional() }).strict(),
  z.object({ type: z.literal('edit_subtitles'), clipId: z.string().uuid(), text: z.string().max(2000).optional(), at: frame.optional(), length: frame.positive().optional(), x: z.number().finite().optional(), y: z.number().finite().optional(), fontSize: z.number().min(8).max(300).optional(), color: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional() }).strict(),
  z.object({ type: z.literal('add_track'), kind: z.enum(['visual', 'audio', 'text']), name: z.string().max(100) }).strict(),
  z.object({ type: z.literal('reorder_track'), trackId: z.string(), index: frame }).strict(),
])
export type Operation = z.infer<typeof operationSchema>
export function emptyState(): State {
  return { fps: FPS, width: 1280, height: 720, tracks: [{ id: 'visual', name: '画面', kind: 'visual' }, { id: 'titles', name: '标题 / 字幕', kind: 'text' }, { id: 'original', name: '原声', kind: 'audio' }, { id: 'voice', name: '配音', kind: 'audio' }, { id: 'music', name: '音乐', kind: 'audio' }], clips: [] }
}
export const duration = (state: State) => state.clips.length ? Math.max(...state.clips.map(c => c.at + c.length)) : FPS
export function validateState(state: State, assets: Asset[], maxFrames = 2880) {
  stateSchema.parse(state)
  invariant(state.width % 2 === 0 && state.height % 2 === 0, 'H.264 输出画幅的宽高必须为偶数')
  invariant(duration(state) <= maxFrames, `项目不能超过 ${maxFrames / FPS} 秒`)
  invariant(new Set(state.clips.map(c => c.id)).size === state.clips.length, '重复片段标识')
  invariant(new Set(state.tracks.map(t => t.id)).size === state.tracks.length, '重复轨道标识')
  for (const clip of state.clips) {
    const track = state.tracks.find(t => t.id === clip.trackId)
    invariant(track && track.kind === (clip.kind === 'text' ? 'text' : clip.kind === 'audio' ? 'audio' : 'visual'), '素材与轨道类型不匹配')
    if (clip.kind !== 'text') {
      const asset = assets.find(a => a.id === clip.assetId)
      invariant(asset, '素材不存在')
      invariant(clip.kind === asset.kind || clip.kind === 'audio' && asset.hasAudio, '素材类型不匹配')
      if (clip.kind !== 'image') invariant(clip.in + clip.length <= asset.frames, '裁剪区间超过素材长度')
    }
    invariant(clip.fadeIn <= clip.length && clip.fadeOut <= clip.length, '淡入淡出不能超过片段长度')
    if(clip.sourceClipId){const parent=state.clips.find(c=>c.id===clip.sourceClipId);invariant(clip.kind==='text'&&parent&&(parent.kind==='video'||parent.kind==='audio'),'字幕来源片段不存在')}
  }
  return stateSchema.parse(JSON.parse(JSON.stringify(state)))
}
export function applyOperations(current: State, raw: unknown[], assets: Asset[], maxFrames = 2880): State {
  const state = structuredClone(current)
  // Older versions only have a group link. Record an explicit source in the next
  // committed version, choosing the picture for legacy linked picture/audio groups.
  for(const caption of state.clips.filter(c=>c.subtitle&&c.link&&!c.sourceClipId)){
    const group=state.clips.filter(c=>c.link===caption.link&&c.kind!=='text')
    const parent=group.find(c=>c.kind==='video')||group.find(c=>c.kind==='audio')
    if(parent)caption.sourceClipId=parent.id
  }
  for (const value of raw) {
    const op = operationSchema.parse(value)
    if (op.type === 'add_track') { state.tracks.push({ id: randomUUID(), kind: op.kind, name: op.name }); continue }
    if (op.type === 'reorder_track') {
      const index = state.tracks.findIndex(t => t.id === op.trackId); invariant(index >= 0 && op.index < state.tracks.length, '无效轨道顺序')
      const track = state.tracks.splice(index, 1)[0]!; state.tracks.splice(op.index, 0, track); continue
    }
    if (op.type === 'insert_clip') {
      const asset = assets.find(a => a.id === op.assetId)
      invariant(asset || op.text !== undefined, '请选择素材或输入文字')
      const kind = op.text !== undefined ? 'text' : asset!.kind
      const length = op.length ?? (kind === 'image' || kind === 'text' ? 72 : asset!.frames - (op.in ?? 0))
      const link = kind === 'video' && asset?.hasAudio ? randomUUID() : undefined
      const clip = clipSchema.parse({ id: randomUUID(), name: asset?.name || '文字', assetId: asset?.id, kind, trackId: op.trackId, at: op.at, length, in: op.in ?? 0, text: op.text, link })
      if(kind==='audio'&&(asset?.purpose==='speech'||op.trackId==='voice')&&op.replaceOriginal!==false){
        // Split the linked picture/audio/captions at both replacement boundaries, retaining source-in.
        for(const boundary of [clip.at,clip.at+clip.length]){
          const crossing=state.clips.filter(c=>c.kind==='audio'&&c.trackId==='original'&&c.at<boundary&&c.at+c.length>boundary).map(c=>c.id)
          for(const id of crossing)Object.assign(state,applyOperations(state,[{type:'split_clip',clipId:id,frame:boundary}],assets,maxFrames))
        }
        const replaced=state.clips.filter(c=>c.kind==='audio'&&c.trackId==='original'&&c.at>=clip.at&&c.at+c.length<=clip.at+clip.length)
        for(const original of replaced)original.muted=true
        // Detached picture/audio still represent the same source only while their
        // source-to-project mapping agrees. Remove stale speech captions in the
        // replaced interval without moving either detached stream.
        for(const caption of [...state.clips.filter(c=>c.subtitle)]){
          const parent=state.clips.find(c=>c.id===caption.sourceClipId)
          const affected=parent&&replaced.some(c=>c.assetId===parent.assetId&&c.in-c.at===parent.in-parent.at)
          if(!affected||caption.at>=clip.at+clip.length||caption.at+caption.length<=clip.at)continue
          const end=caption.at+caption.length
          state.clips=state.clips.filter(c=>c.id!==caption.id)
          if(caption.at<clip.at)state.clips.push({...caption,length:clip.at-caption.at,fadeIn:0,fadeOut:0})
          if(end>clip.at+clip.length)state.clips.push({...caption,id:randomUUID(),at:clip.at+clip.length,length:end-clip.at-clip.length,fadeIn:0,fadeOut:0})
        }
      }
      state.clips.push(clip)
      if (link) state.clips.push({ ...clip, id: randomUUID(), kind: 'audio', name: `${clip.name} · 原声`, trackId: 'original' })
      continue
    }
    if (op.type === 'set_layout' && !op.clipId) {
      invariant(op.width !== undefined && op.height !== undefined, '请指定输出画幅'); state.width = op.width; state.height = op.height; continue
    }
    const clipId = 'clipId' in op ? op.clipId : undefined
    const clip = state.clips.find(c => c.id === clipId); invariant(clip, '片段不存在')
    // Captions follow edits to their source media; editing a caption never edits its source.
    const linked = () => {
      if(clip.kind==='text')return [clip]
      const media=state.clips.filter(c=>c.kind!=='text'&&(c.id===clip.id||clip.link&&c.link===clip.link)),ids=new Set(media.map(c=>c.id))
      return state.clips.filter(c=>ids.has(c.id)||c.kind==='text'&&(c.sourceClipId?ids.has(c.sourceClipId):clip.link&&c.link===clip.link))
    }
    if (op.type === 'unlink_clip') {
      if(clip.kind==='text'){delete clip.link;delete clip.sourceClipId;continue}
      const members=linked()
      for(const media of members.filter(c=>c.kind!=='text')){
        const captions=members.filter(c=>c.kind==='text'&&c.sourceClipId===media.id)
        if(captions.length){media.link=randomUUID();for(const caption of captions)caption.link=media.link}
        else delete media.link
      }
      continue
    }
    if (op.type === 'delete_clip') { const ids = new Set(linked().map(c => c.id)); state.clips = state.clips.filter(c => !ids.has(c.id)); continue }
    if (op.type === 'duplicate_clip') {
      const delta = (op.at ?? clip.at + clip.length) - clip.at; const link = clip.kind !== 'text' && clip.link ? randomUUID() : undefined
      const members=linked(),ids=new Map(members.map(c=>[c.id,randomUUID()]))
      state.clips.push(...members.map(c => ({ ...c, id: ids.get(c.id)!, link, sourceClipId:c.sourceClipId?ids.get(c.sourceClipId):undefined, at: c.at + delta }))); continue
    }
    if (op.type === 'move_clip') {
      const delta = op.at - clip.at; for (const item of linked()) { invariant(item.at + delta >= 0, '移动后不能位于负时间'); item.at += delta }
      if (op.trackId) clip.trackId = op.trackId; continue
    }
    if (op.type === 'trim_clip') {
      const end = op.at + op.length; const oldStart = clip.at; const oldEnd = clip.at + clip.length
      for (const item of linked()) {
        const newStart = item.id === clip.id || item.kind !== 'text' && item.at === oldStart ? op.at : Math.max(item.at, op.at)
        const newEnd = item.id === clip.id || item.kind !== 'text' && item.at + item.length === oldEnd ? end : Math.min(item.at + item.length, end)
        if (newEnd <= newStart) { state.clips = state.clips.filter(c => c.id !== item.id); continue }
        if (item.kind !== 'text' && item.kind !== 'image') item.in += newStart - item.at
        item.at = newStart; item.length = newEnd - newStart; item.fadeIn = Math.min(item.fadeIn, item.length); item.fadeOut = Math.min(item.fadeOut, item.length)
      }
      continue
    }
    if (op.type === 'split_clip') {
      invariant(op.frame > clip.at && op.frame < clip.at + clip.length, '播放头必须位于片段内部')
      const rightLink = clip.kind === 'text' ? clip.link : clip.link ? randomUUID() : undefined
      const members=linked(),rightIds=new Map(members.filter(c=>c.kind!=='text'&&c.at+c.length>op.frame).map(c=>[c.id,c.at>=op.frame?c.id:randomUUID()]))
      for (const item of members) {
        if (item.at >= op.frame) { item.link = rightLink;if(item.sourceClipId)item.sourceClipId=rightIds.get(item.sourceClipId)||item.sourceClipId;continue }
        if (item.at + item.length <= op.frame) continue
        const offset = op.frame - item.at
        const right = { ...item, id: rightIds.get(item.id)||randomUUID(), at: op.frame, length: item.length - offset, in: item.in + (item.kind === 'text' || item.kind === 'image' ? 0 : offset), link: rightLink, sourceClipId:item.sourceClipId?rightIds.get(item.sourceClipId)||item.sourceClipId:undefined, fadeIn: 0 }
        item.length = offset; item.fadeOut = 0; item.fadeIn = Math.min(item.fadeIn,item.length); right.fadeOut = Math.min(right.fadeOut,right.length); state.clips.push(right)
      }
      continue
    }
    if (op.type === 'set_layout') {
      invariant(clip.kind !== 'audio', '音频片段没有画面属性')
      invariant(clip.kind !== 'text' || op.scale === undefined && op.fit === undefined, '文字请通过字号调整大小')
      for (const key of ['x', 'y', 'scale', 'fit'] as const) if (op[key] !== undefined) (clip as any)[key] = op[key]; continue
    }
    if (op.type === 'set_audio') {
      invariant(clip.kind === 'audio' || clip.kind === 'video', '请选择视频或音频片段')
      const targets = clip.kind === 'audio' ? [clip] : linked().filter(c => c.kind === 'audio')
      invariant(targets.length, '片段没有音轨')
      for (const item of targets) for (const key of ['volume', 'muted', 'fadeIn', 'fadeOut'] as const) if (op[key] !== undefined) (item as any)[key] = op[key]
      continue
    }
    if (op.type === 'edit_subtitles') { invariant(clip.kind === 'text', '请选择文字片段'); const { type, clipId, ...fields } = op; Object.assign(clip, fields) }
  }
  return validateState(state, assets, maxFrames)
}
export function subtitlesForClip(clip: Clip, segments: any[], trackId = 'titles'): Clip[] {
  const result: Clip[] = []
  for (const segment of segments) {
    if (!Number.isFinite(segment.start) || !Number.isFinite(segment.end) || segment.end <= segment.start || typeof segment.text !== 'string' || !segment.text.trim()) continue
    // ASR input is the actual used clip audio, so timestamps are local to its destination window.
    const start = Math.max(0, Math.round(segment.start * FPS)); const end = Math.min(clip.length, Math.round(segment.end * FPS))
    if (end <= start) continue
    const completeCharacters=segment.characters?.length>0&&segment.characters.every((c:any)=>Number.isFinite(c.start)&&Number.isFinite(c.end)&&c.end>c.start)
    result.push(clipSchema.parse({ id: randomUUID(), trackId, kind: 'text', name: '字幕', at: clip.at + start, length: end - start, in: 0,
      text: segment.text, link: clip.link, sourceClipId:clip.id, subtitle: true, y: 250, fontSize: 38, precision: completeCharacters ? 'character' : 'sentence' }))
  }
  return result
}
export function replaceSubtitles(state: State, clip: Clip, segments: any[]): Clip[] {
  const sourceIds=new Set(state.clips.filter(c=>c.kind!=='text'&&(c.id===clip.id||clip.link&&c.link===clip.link)).map(c=>c.id))
  const additions=subtitlesForClip(clip,segments)
  state.clips=state.clips.filter(c=>!(c.subtitle&&(c.sourceClipId?sourceIds.has(c.sourceClipId):clip.link&&c.link===clip.link)))
  state.clips.push(...additions)
  return additions
}
