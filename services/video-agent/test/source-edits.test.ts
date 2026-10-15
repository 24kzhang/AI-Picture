import test from 'node:test'
import assert from 'node:assert/strict'
import {plannerSourceFiles,preserveSourceMetadata,replaceSourceText} from '../src/source-edits.js'

test('localized source changes preserve untouched files and reject ambiguous or missing targets',()=>{
  const original={'main.svml':'<svml><title>咖啡豆</title></svml>','main.svs':'unchanged'}
  const changes=replaceSourceText(original,[{path:'main.svml',find:'<title>咖啡豆</title>',replace:'<title>开袋香气</title>'}])
  assert.deepEqual(Object.keys(changes),['main.svml'])
  assert.match(changes['main.svml']!,/<title>开袋香气<\/title>/)
  assert.equal(original['main.svml'],'<svml><title>咖啡豆</title></svml>')
  for(const edits of [[{path:'missing.svml',find:'咖啡豆',replace:'a'}],[{path:'main.svml',find:'missing',replace:'a'}],[{path:'main.svml',find:'title',replace:'a'}]])assert.throws(()=>replaceSourceText(original,edits))
})
test('machine metadata is hidden from the model and restored unchanged if regenerated or omitted',()=>{
  const marker='<!-- gallery-state:eyJmb28iOiJiYXIifQ== -->'
  const original={'main.svml':`<svml>\n${marker}\n<title>咖啡豆</title></svml>`}
  const readable=plannerSourceFiles(original)
  assert.doesNotMatch(readable['main.svml']!,/gallery-state/)
  for(const text of [readable['main.svml']!,readable['main.svml']!.replace('<svml>','<svml><!-- gallery-state:broken -->')]){
    const repaired=preserveSourceMetadata(original,{'main.svml':text})['main.svml']!
    assert.ok(repaired.includes(marker));assert.doesNotMatch(repaired,/broken/)
    assert.equal(repaired.match(/gallery-state:/g)?.length,1)
  }
  assert.throws(()=>replaceSourceText(original,[{path:'main.svml',find:marker,replace:''}]),/元数据/)
})
