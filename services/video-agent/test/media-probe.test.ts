import test from 'node:test'
import assert from 'node:assert/strict'
import {pathToFileURL} from 'node:url'
import {resolve} from 'node:path'
import {hypitRoot,patchMediaExecution} from '../src/hypit.js'
await patchMediaExecution()
const {installDistributionPackageResolution}=await import(pathToFileURL(resolve(hypitRoot,'packages/package-loader-node/src/distribution-resolution.ts')).href)
installDistributionPackageResolution([hypitRoot])
const {parseMediaInspection}=await import(pathToFileURL(resolve(hypitRoot,'packages/media-execution/src/probe.ts')).href)
test('Hypit classifies a timed single-frame video as moving while preserving image and cover roles',()=>{
  for(const [mediaType,attached,role] of [['video/mp4',0,'moving'],['image/png',0,'still'],['video/mp4',1,'attached-picture']]){
    const inspection=parseMediaInspection({source:{kind:'blob',resource:'test-source',size:100,mediaType},ffprobeVersion:'acceptance',value:{streams:[{index:0,codec_type:'video',codec_name:'h264',width:1280,height:720,time_base:'1/12288',disposition:{attached_pic:attached}}],frames:[{media_type:'video',stream_index:0,pts:0,pkt_duration:512}]}})
    assert.equal(inspection.streams[0].role,role);assert.equal(inspection.streams[0].timingStatus,'admissible')
  }
})
