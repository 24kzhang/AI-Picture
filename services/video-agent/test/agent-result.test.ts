import test from 'node:test'
import assert from 'node:assert/strict'
import {executionReceipt} from '../src/agent-result.js'
const base={modelAnswer:'无法完成，请确认',applied:true,pending:false,generatedCount:0,revision:10,tools:['inspect_project','transcribe'],toolErrors:0}
test('committed subtitle receipt is authoritative even if the planner incorrectly refuses afterwards',()=>{
  const reply=executionReceipt(base);assert.match(reply,/已保存项目 v10/);assert.match(reply,/中文字幕转写 1 次/);assert.doesNotMatch(reply,/无法完成|请确认/)
})
test('pending generated assets never report timeline application or a render',()=>{
  const reply=executionReceipt({...base,pending:true,generatedCount:1,exportFrames:240});assert.match(reply,/请手动插入/);assert.doesNotMatch(reply,/已保存项目|已导出/)
})
test('no tool mutation, partial failures and export receipts state their actual outcomes',()=>{
  assert.match(executionReceipt({...base,applied:false,tools:['inspect_project']}),/^本次未修改项目/)
  const reply=executionReceipt({...base,tools:['trim_clip','move_clip','move_clip'],toolErrors:1,exportFrames:240});assert.match(reply,/移动 2 次/);assert.match(reply,/1 次工具调用失败/);assert.match(reply,/240 帧 MP4/)
})
test('native export receipt uses the exported programme clock instead of a fixed 24fps assumption',()=>{
  assert.match(executionReceipt({...base,exportFrames:300,exportFps:30}),/10\.00 秒/)
})
