import test,{mock} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import {syncBuiltinESMExports} from 'node:module'
import {pathToFileURL} from 'node:url'
import {resolve,relative,sep} from 'node:path'
import {root} from '../src/config.js'
import {hypitRoot,patchMediaExecution} from '../src/hypit.js'
await patchMediaExecution()
const {installDistributionPackageResolution}=await import(pathToFileURL(resolve(hypitRoot,'packages/package-loader-node/src/distribution-resolution.ts')).href);installDistributionPackageResolution([hypitRoot])
const {FileBuildResult}=await import(pathToFileURL(resolve(hypitRoot,'packages/build-result/src/store.ts')).href)
const scratch=resolve(root,'runtime-data/video-acceptance/result-store-tests');await fs.mkdir(scratch,{recursive:true})
for(const [label,code,failures,expected,success] of [['temporary Windows directory lock','EPERM',2,3,true],['persistent Windows directory lock','EPERM',8,5,false],['permanent filesystem error','EACCES',8,1,false]] as const){
  test(`Hypit atomic result publication handles ${label}`,{skip:code==='EPERM'&&process.platform!=='win32'},async()=>{
    const directory=await fs.mkdtemp(resolve(scratch,'case-')),rename=fs.rename;let attempts=0
    mock.method(fs,'rename',async(from:any,to:any)=>{if(String(from).includes('.preparing-')){attempts++;if(attempts<=failures)throw Object.assign(new Error(label),{code})}return rename(from,to)});syncBuiltinESMExports()
    const seed={id:'bld_20261002T151531284Z_TEST000001',source:{path:'main.svml'},targets:[],publishedOutputs:[]}
    try{if(success){const result=await FileBuildResult.create(directory,seed);assert.equal((await result.read()).id,seed.id)}else await assert.rejects(FileBuildResult.create(directory,seed),(error:any)=>error.code===code);assert.equal(attempts,expected)}
    finally{mock.restoreAll();syncBuiltinESMExports();const checked=relative(scratch,directory);assert.ok(checked&&!checked.startsWith('..')&&!checked.includes(sep));await fs.rm(directory,{recursive:true,force:true})}
  })
}
