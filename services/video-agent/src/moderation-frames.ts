import { access } from 'node:fs/promises'
import { resolve } from 'node:path'
import { command, ffmpegPath, ffprobePath } from './hypit.js'

export function reviewFrameIndexes(frameCount: number): [number, number, number] {
  if (!Number.isSafeInteger(frameCount) || frameCount < 1) throw Error('视频没有有效的审核帧')
  return [0, Math.floor((frameCount - 1) / 2), frameCount - 1]
}

export async function extractReviewFrames(path: string, folder: string) {
  // Count decoded frames rather than estimating the tail from duration or frame rate.
  const info = JSON.parse(await command(ffprobePath, ['-v', 'error', '-select_streams', 'v:0',
    '-count_frames', '-show_entries', 'stream=nb_read_frames', '-of', 'json', path]))
  const indexes = reviewFrameIndexes(Number(info.streams?.[0]?.nb_read_frames))
  const unique = [...new Set(indexes)]
  const select = unique.map(index => `eq(n,${index})`).join('+')
  await command(ffmpegPath, ['-v', 'error', '-i', path, '-map', '0:v:0', '-an',
    '-vf', `select='${select}',scale=640:-2`, '-fps_mode', 'vfr', '-frames:v', String(unique.length),
    '-q:v', '3', '-start_number', '0', resolve(folder, 'frame-%d.jpg')])
  const paths = indexes.map(index => resolve(folder, `frame-${unique.indexOf(index)}.jpg`))
  // Very short clips reuse their available frames, but never substitute a missing tail.
  await Promise.all(paths.map(path => access(path)))
  return { paths, indexes, frameCount: Number(info.streams[0].nb_read_frames) }
}
