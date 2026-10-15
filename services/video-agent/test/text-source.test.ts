import test from 'node:test'
import assert from 'node:assert/strict'
import {emptyState,applyOperations} from '../src/timeline.js'
import {sourceFor} from '../src/hypit.js'
import {nativeProjection} from '../src/studio.js'

test('multiline text uses explicit breaks, escaped literals and full-height layout',()=>{
  const state=applyOperations(emptyState(),[{type:'insert_clip',trackId:'titles',text:'森林\n\n<旅程> & 留念\n结束',at:0,length:96}],[])
  const source=sourceFor(state,[])
  assert.ok(source['main.svml'].includes('森林<typo:Break/><typo:Break/>&lt;旅程&gt; &amp; 留念<typo:Break/>结束'))
  assert.ok(source['main.svml'].includes('width="1152px" height="720px"'))
  assert.ok(source['main.svs'].includes('size: 42;'))
  assert.deepEqual(nativeProjection(state,source['main.svml'],source['main.svs'],[]),state)
  const edited=nativeProjection(state,source['main.svml'],source['main.svs'].replace('size: 42;','size: 48;'),[])
  assert.equal(edited.clips[0]!.fontSize,48)
})

test('text source normalizes CRLF without treating escaped Break text as markup',()=>{
  const state=applyOperations(emptyState(),[{type:'insert_clip',trackId:'titles',text:'<typo:Break/>\r\n第二行',at:0,length:24}],[])
  const source=sourceFor(state,[])
  assert.equal(nativeProjection(state,source['main.svml'],source['main.svs'],[]).clips[0]!.text,'<typo:Break/>\n第二行')
})

test('native Studio projection preserves scientific-notation gains on unrelated edits',()=>{
  const asset={id:'5976602f-070c-4d7b-8d37-24509309b588',name:'口播',kind:'audio' as const,frames:96,width:0,height:0,hasAudio:true,path:'voice.wav',size:100}
  let state=applyOperations(emptyState(),[{type:'insert_clip',assetId:asset.id,trackId:'music',at:0,length:96}],[asset])
  state=applyOperations(state,[{type:'set_audio',clipId:state.clips[0]!.id,volume:1e-7}],[asset])
  const source=sourceFor(state,[asset])
  assert.deepEqual(nativeProjection(state,source['main.svml'],source['main.svs'],[asset]),state)
})
