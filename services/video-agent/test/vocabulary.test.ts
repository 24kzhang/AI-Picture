import test from 'node:test'
import assert from 'node:assert/strict'
import {resolve} from 'node:path'
import {root} from '../src/config.js'
import {vocabulary} from '../src/hypit-engine.js'

test('installed Hypit vocabulary is readable without unsupported workspace arguments',async()=>{
  const result=await vocabulary('typography-track',resolve(root,'services/video-agent'))
  assert.ok(result.surfaces.length>0)
  assert.ok(!('projectRoot' in result))
  assert.ok(result.surfaces.every((surface:any)=>!('readme' in surface)))
})
