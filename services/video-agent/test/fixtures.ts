import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import sharp from 'sharp'
import { root } from '../src/config.js'
import { command, ffmpegPath } from '../src/hypit.js'
export async function fixtures() {
  const directory=resolve(root,'runtime-data/video-acceptance/media');await mkdir(directory,{recursive:true})
  await command(ffmpegPath,['-y','-f','lavfi','-i','color=red:s=320x240:r=24:d=2','-f','lavfi','-i','color=green:s=320x240:r=24:d=2','-f','lavfi','-i','sine=frequency=800:sample_rate=48000:duration=4','-filter_complex','[0:v][1:v]concat=n=2:v=1:a=0[v]','-map','[v]','-map','2:a','-c:v','libx264','-preset','fast','-pix_fmt','yuv420p','-c:a','aac','-ac','2','-shortest',resolve(directory,'red-green.mp4')])
  await command(ffmpegPath,['-y','-f','lavfi','-i','color=blue:s=320x240:r=24:d=3','-f','lavfi','-i','sine=frequency=1200:sample_rate=48000:duration=3','-c:v','libx264','-preset','fast','-pix_fmt','yuv420p','-c:a','aac','-ac','2','-shortest',resolve(directory,'blue.mov')])
  await command(ffmpegPath,['-y','-f','lavfi','-i','sine=frequency=300:sample_rate=48000:duration=6','-c:a','pcm_s16le','-ac','2',resolve(directory,'music.wav')])
  await sharp({create:{width:320,height:240,channels:4,background:'#f4d35e'}}).png().toFile(resolve(directory,'overlay.png'))
  console.log(directory)
}
if(process.argv[1]?.endsWith('fixtures.ts'))await fixtures()
