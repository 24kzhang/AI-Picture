import test from 'node:test'
import assert from 'node:assert/strict'
import {resolve,dirname,delimiter,isAbsolute,basename} from 'node:path'
import {spawnSync} from 'node:child_process'
import {mediaEnvironment} from '../src/media-environment.js'
import {ffprobePath,ffmpegPath} from '../src/hypit.js'

test('media tools precede inherited PATH, retain Unicode/spaces and remove duplicate Windows casing',()=>{
  const executable=resolve('中文媒体工具/with spaces/ffprobe.exe')
  const base={Path:'existing-tools',PATH:'other-tools',OTHER:'unchanged'}
  const result=mediaEnvironment(base,[executable,executable,'ffmpeg'])
  assert.deepEqual(base,{Path:'existing-tools',PATH:'other-tools',OTHER:'unchanged'})
  const keys=Object.keys(result).filter(key=>key.toLowerCase()==='path')
  assert.equal(keys.length,1)
  assert.deepEqual(result[keys[0]!]!.split(delimiter),[dirname(executable),'existing-tools','other-tools'])
  assert.equal(result.OTHER,'unchanged')
})
test('Studio can discover the configured bundled ffprobe without a system installation',
  {skip:!isAbsolute(ffprobePath)},()=>{
    const env=mediaEnvironment({SystemRoot:process.env.SystemRoot,Path:''},[ffmpegPath,ffprobePath])
    const result=spawnSync(basename(ffprobePath),['-version'],{env,windowsHide:true,encoding:'utf8'})
    assert.equal(result.status,0,result.error?.message||result.stderr)
    assert.match(result.stdout,/ffprobe version/)
  })
