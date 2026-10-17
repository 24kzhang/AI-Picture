import { isDeepStrictEqual } from 'node:util'
import { ApiError, invariant } from './errors.js'
import { applyOperations, operationSchema, type State, type Asset } from './timeline.js'

/** Merge independent objects; never silently overwrite an object edited since the input revision. */
export function mergeAnnotations(base: State, latest: State, raw: unknown[], assets: Asset[], maxFrames: number) {
  invariant(raw.length > 0 && raw.length <= 100, '每次提交 1 至 100 个字幕或贴图操作')
  const operations = raw.map(op => operationSchema.parse(op))
  const candidate = structuredClone(latest)
  for (const op of operations) {
    if (op.type === 'insert_clip') {
      const asset = assets.find(a => a.id === op.assetId)
      invariant(op.text !== undefined && !op.assetId && !op.overlay || op.overlay === true && op.text === undefined && asset?.kind === 'image' && op.trackId === 'overlays', '多人模式仅可插入文字和图片贴图')
      if (op.overlay && !candidate.tracks.some(t => t.id === 'overlays')) candidate.tracks.push({id:'overlays',name:'贴图',kind:'visual'})
      invariant(candidate.tracks.some(t => t.id === op.trackId && t.kind === (op.overlay ? 'visual' : 'text')), '请选择字幕或贴图轨道')
      continue
    }
    invariant(['edit_subtitles','set_layout','move_clip','trim_clip','split_clip','duplicate_clip','delete_clip'].includes(op.type) && 'clipId' in op && op.clipId, '多人模式不能修改视频、音频、画幅和轨道结构')
    const clipId = (op as {clipId:string}).clipId
    const old = base.clips.find(c => c.id === clipId), current = latest.clips.find(c => c.id === clipId)
    if (!old || !current || !isDeepStrictEqual(old,current)) throw new ApiError(409,'OBJECT_CONFLICT','此字幕或贴图已被其他成员修改或删除，请核对最新内容；本地属性仍保留',{clipId})
    invariant(current.kind === 'text' || current.kind === 'image' && current.overlay === true, '多人模式不能操作视频本身或原始画面')
    if ('trackId' in op && op.trackId) invariant(candidate.tracks.some(t => t.id === op.trackId && t.kind === (current.kind === 'text' ? 'text' : 'visual')), '轨道类型不匹配')
  }
  return applyOperations(candidate, operations, assets, maxFrames)
}
