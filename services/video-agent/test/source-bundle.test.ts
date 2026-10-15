import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeSource, sourceFiles, outputTargets, sourcePath } from '../src/source-bundle.js'
import { sourceFor,readState } from '../src/hypit.js'
import {emptyState,applyOperations} from '../src/timeline.js'
import {templateSource,captionCues} from '../src/hypit-engine.js'
import {pathToFileURL} from 'node:url'
import {resolve} from 'node:path'
import {hypitRoot} from '../src/hypit.js'
const asset={id:'5976602f-070c-4d7b-8d37-24509309b588',name:'森林实景',kind:'video' as const,frames:96,width:768,height:512,hasAudio:true,path:'forest.mp4',size:100}
test('caption grouping preserves real speech, mixed-language words and literal Script symbols',async()=>{
  const {parseScript}=await import(pathToFileURL(resolve(hypitRoot,'packages/script/src/parser.ts')).href)
  for(const text of ['沿着溪流走进清晨的森林转过街角感受城市及时的热闹最后在咖啡箱中回味这段旅程','穿过森林，一起欣赏这段旅程。继续走向城市，看看街角的咖啡馆。','这里介绍 OpenAI HyperFrames 3D，接下来观看视频','我的邮箱是 a@example.com，符号 < | { } 和反斜杠 \\ 都应该保留']){
    const original=parseScript('original',`<voice>${text.replace(/[\\@<|{}]/g,'\\$&')}</voice>`),grouped=parseScript('grouped',`<voice>${captionCues(text)}</voice>`)
    assert.deepEqual(grouped.tokens.map((t:any)=>t.speech),original.tokens.map((t:any)=>t.speech))
    assert.equal(grouped.serializations.speech,original.serializations.speech)
    assert.ok(grouped.captionProjection.breaks.length>0)
  }
})
test('legacy source normalizes without changing source/audio linkage and deduplicates immutable asset references',()=>{
  const state=applyOperations(emptyState(),[{type:'insert_clip',assetId:asset.id,trackId:'visual',at:0,length:72}],[asset]),source=normalizeSource(sourceFor(state,[asset]),[asset])
  assert.deepEqual(source.assetIds,[asset.id]);assert.deepEqual(readState(source),state);assert.deepEqual(outputTargets(source),['final.video'])
})
test('native bundles preserve nested UTF-8 source files and all Run targets instead of flattening them',()=>{
  const original=sourceFor(emptyState(),[]),files={...sourceFiles(original),'章节/片段.svml':'<?svml using="@hypit/markup@1"?><svml></svml>','main.svml':original['main.svml'].replace('<svml>','<svml><import as="chapter" source="./章节/片段.svml"/>'),'build.svrun':original['build.svrun'].replace('</svrun>','<target output="extra.video"/></svrun>')}
  const normalized=normalizeSource({...original,files,mode:'hypit',state:emptyState()},[])
  assert.equal(normalized.files!['章节/片段.svml'],files['章节/片段.svml']);assert.deepEqual(outputTargets(normalized),['final.video','extra.video']);assert.equal(normalized['main.svml'],files['main.svml'])
})
test('source paths reject absolute paths, traversal, scripts, reserved config and assets',()=>{
  for(const path of ['../main.svml','/tmp/file.svml','C:/secret.json','nested/../../secret.json','a\\b.svml','nested//data.json','build.js','assets/a.json','package.json','hypit.runtime.json','FEEDBACK.json'])assert.throws(()=>sourcePath(path),path)
  assert.equal(sourcePath('章节/片段.svml'),'章节/片段.svml')
})
test('resource/import closure rejects external files, remote URLs, missing data, other projects and unknown code',()=>{
  const base=sourceFor(emptyState(),[])
  for(const dependency of ['<media:Video src="https://evil/video.mp4"/>','<import as="x" source="../secret.json"/>','<import as="x" source="./missing.svml"/>','<import as="x" from="@local/arbitrary@1"/>','<import as="x" from="@hypit/missing@1"/>',`<media:Video src="./assets/${asset.id}.mp4"/>`])assert.throws(()=>normalizeSource({...base,'main.svml':base['main.svml'].replace('<svml>','<svml>'+dependency)},[]))
})
test('native source validates data size, JSON and comment identities',()=>{
  const base=sourceFor(emptyState(),[])
  assert.throws(()=>normalizeSource({...base,files:{'large.svs':'a'.repeat(1024**2)}},[]))
  assert.throws(()=>normalizeSource({...base,files:{'data.json':'{broken'}},[]))
  assert.throws(()=>normalizeSource({...base,feedback:{comments:[{id:'1',run:'elsewhere.svrun',at:0,text:'检查'}]}},[]))
  const feedback={comments:[{id:'1',run:'build.svrun',at:1.25,text:'真实场景：注意转场',resolved:false}]}
  assert.deepEqual(normalizeSource({...base,feedback},[]).feedback,feedback)
})
test('native template supports true footage, split-screen independent audio and keeps untouched current sources',()=>{
  const base=sourceFor(emptyState(),[])
  assert.throws(()=>templateSource('travel',[],base))
  for(const name of ['travel','split-screen','portrait']){const source=templateSource(name,[asset],base);assert.equal(source.mode,'hypit');assert.deepEqual(source.assetIds,[asset.id]);assert.ok(source['main.svml'].includes(asset.id))}
  const source=templateSource('split-screen',[asset],base),state=readState(source)
  assert.deepEqual(state.clips.filter(c=>c.kind==='video').map(c=>c.x),[-320,320]);assert.equal(state.clips.filter(c=>c.kind==='audio').filter(c=>c.muted).length,1)
  const advanced={...base,files:{'extra.svs':'<?svml using="@hypit/svs@1"?><sheet version="1"></sheet>'},mode:'hypit' as const,state:emptyState()}
  assert.equal(templateSource('current',[],advanced).files!['extra.svs'],advanced.files['extra.svs'])
})
