import test from 'node:test'
import assert from 'node:assert/strict'
import { modelSdk, executeModelProvider } from '../src/model-sdk.js'
test('public Hypit SDK defines self-hosted exact models and rejects invalid LTX request',async()=>{
 const sdk=await modelSdk();assert.equal(sdk.models.generate_image.endpoints.generate.ports.model,'qwen-image-2.1');assert.equal(sdk.models.synthesize_speech.endpoints.generate.ports.model,'indextts-2.5')
 const endpoint=sdk.models.generate_video.endpoints.generate
 assert.throws(()=>endpoint.sealRequest({prompt:['x'],width:[720],height:[512],numFrames:[49]}))
 assert.doesNotThrow(()=>endpoint.sealRequest({prompt:['x'],width:[768],height:[512],numFrames:[49]}))
 assert.doesNotThrow(()=>endpoint.sealRequest({prompt:['咖啡商品特写'],width:[768],height:[512],numFrames:[257]}))
 for(const frames of [321,2881])assert.throws(()=>endpoint.sealRequest({prompt:['咖啡商品特写'],width:[768],height:[512],numFrames:[frames]}))
})

test('overlong LTX generation is rejected before the provider submits remote work',async()=>{
 let submitted=false
 await assert.rejects(()=>executeModelProvider('generate_video',{prompt:'咖啡豆电商广告',width:768,height:512,num_frames:321,frame_rate:24},[],async()=>{submitted=true;throw Error('must not submit')}))
 assert.equal(submitted,false)
})
