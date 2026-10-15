import test from 'node:test'
import assert from 'node:assert/strict'
import {patchRenderPortSource,renderServerNeedle} from '../src/render-port-patch.js'

const AsyncFunction=Object.getPrototypeOf(async()=>{}).constructor
function patchedServer(){return new AsyncFunction('createFileServer','work','fps','hyperframesFrameSelectionPrelude','requestedRanges',`let server;\n${patchRenderPortSource(renderServerNeedle)}\nreturn server`)}
test('blocked loopback ports are closed and replaced before opening the render browser',async()=>{
  const ports=[6665,6666,6697,10080,18080],closed:number[]=[]
  const server=await patchedServer()(async()=>{const port=ports.shift()!;return{url:`http://localhost:${port}/`,close:async()=>{closed.push(port)}}},'work',24,()=>'',[])
  assert.equal(server.url,'http://localhost:18080/')
  assert.deepEqual(closed,[6665,6666,6697,10080])
})
test('the patch is idempotent, fails closed on dependency drift, and bounds port retries',async()=>{
  const patched=patchRenderPortSource(renderServerNeedle)
  assert.equal(patchRenderPortSource(patched),patched)
  assert.throws(()=>patchRenderPortSource('changed dependency source'),/定位失败/)
  let closed=0
  await assert.rejects(patchedServer()(async()=>({url:'http://localhost:6665/',close:async()=>{closed++}}),'work',24,()=>'',[]),/渲染端口/)
  assert.equal(closed,32)
})
