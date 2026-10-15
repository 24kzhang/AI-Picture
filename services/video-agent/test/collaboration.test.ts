import test from 'node:test'
import assert from 'node:assert/strict'
import { CollaborationGate, invalidatesEditing } from '../../../frontend/src/utils/videoCollaboration.js'
const held = { userId:'2105494801052676097', sessionId:'a', epoch:3, until:new Date(120_000).toISOString() }
test('a handoff fences local state even when the source revision is unchanged',()=>{
  const gate=new CollaborationGate()
  assert.equal(gate.reconcile(held,{...held,epoch:4,sessionId:null,userId:'b'},'a',100_000),undefined)
  assert.equal(gate.reconcile(undefined,held,'a',99_000),undefined)
})
test('delayed renewal and poll responses cannot restore an expired lease',()=>{
  const gate=new CollaborationGate()
  assert.equal(gate.reconcile(held,held,'a',120_000),undefined)
  assert.equal(gate.reconcile(undefined,held,'a',119_000),undefined)
})
test('server time governs validity and renewed expiry replaces the local expiry',()=>{
  const gate=new CollaborationGate(),renewed={...held,until:new Date(180_000).toISOString()}
  assert.deepEqual(gate.reconcile(held,renewed,'a',130_000),renewed)
  assert.deepEqual(gate.reconcile(renewed,held,'a',100_000),renewed)
})
test('another tab of the same user cannot inherit editing rights',()=>{
  assert.equal(new CollaborationGate().reconcile(held,{...held,sessionId:'other-tab'},'a',100_000),undefined)
  assert.equal(new CollaborationGate().reconcile(undefined,held,'a',100_000),undefined)
})
test('authentication revocation clears editing, transient disconnection does not',()=>{
  for(const failure of [{status:401},{status:403},{code:'LEASE_EXPIRED'},{code:'LEASE_REQUIRED'}])assert.equal(invalidatesEditing(failure),true)
  assert.equal(invalidatesEditing({status:503}),false)
  assert.equal(invalidatesEditing({code:'REVISION_CONFLICT',status:409}),false)
})
