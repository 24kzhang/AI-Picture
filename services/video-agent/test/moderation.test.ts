import test from 'node:test'
import assert from 'node:assert/strict'
import { decision } from '../src/moderation.js'
test('only sexual, violence and gore can reject a completed AI review',()=>{
 assert.equal(decision({sexual:false,violence:false,gore:false,other:'advertising'}).status,1)
 for(const key of ['sexual','violence','gore'])assert.equal(decision({sexual:false,violence:false,gore:false,[key]:true}).status,2)
})
test('malformed model results never approve media',()=>{
 for(const raw of [{},{sexual:'false',violence:false,gore:false},{sexual:false,violence:null,gore:false}])assert.throws(()=>decision(raw))
})
