import test from 'node:test'
import assert from 'node:assert/strict'
import { modelSdk } from '../src/model-sdk.js'
test('public Hypit SDK defines self-hosted exact models and rejects invalid LTX request',async()=>{
 const sdk=await modelSdk();assert.equal(sdk.models.generate_image.endpoints.generate.ports.model,'qwen-image-2.1');assert.equal(sdk.models.synthesize_speech.endpoints.generate.ports.model,'indextts-2.5')
 const endpoint=sdk.models.generate_video.endpoints.generate
 assert.throws(()=>endpoint.sealRequest({prompt:['x'],width:[720],height:[512],numFrames:[49]}))
 assert.doesNotThrow(()=>endpoint.sealRequest({prompt:['x'],width:[768],height:[512],numFrames:[49]}))
})
