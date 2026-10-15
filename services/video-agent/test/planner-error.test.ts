import test from 'node:test'
import assert from 'node:assert/strict'
import {plannerFailureMessage} from '../src/planner-error.js'

test('billing errors explain the required recovery and preserve manual editing',()=>{
  const message=plannerFailureMessage(400,JSON.stringify({error:{code:'Arrearage',message:'private upstream detail'}}))
  assert.match(message,/欠费.*充值/)
  assert.match(message,/手动剪辑/)
  assert.doesNotMatch(message,/private upstream detail/)
})
test('credentials, quota, permissions, rate limits and non-JSON errors remain actionable',()=>{
  assert.match(plannerFailureMessage(401,'{}'),/密钥无效/)
  assert.match(plannerFailureMessage(429,'{"error":{"code":"insufficient_quota"}}'),/额度不足/)
  assert.match(plannerFailureMessage(403,'{}'),/权限/)
  assert.match(plannerFailureMessage(429,'{}'),/请求受限/)
  assert.match(plannerFailureMessage(502,'<html>secret</html>'),/HTTP 502/)
  assert.doesNotMatch(plannerFailureMessage(502,'<html>secret</html>'),/secret/)
})
