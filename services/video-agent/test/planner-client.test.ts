import test from 'node:test'
import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {once} from 'node:events'
import {requestPlannerTurn} from '../src/planner-client.js'

test('self-hosted native calls need no Aliyun key and preserve tool results',async()=>{
  const received:any[]=[]
  let finish='tool_calls'
  const server=createServer(async(req,res)=>{
    let text='';for await(const chunk of req)text+=chunk
    received.push({body:JSON.parse(text),authorization:req.headers.authorization})
    res.setHeader('Content-Type','application/json')
    res.end(JSON.stringify({choices:[{finish_reason:finish,message:{role:'assistant',content:null,tool_calls:[
      {id:'call_title',type:'function',function:{name:'insert_clip',arguments:'{"text":"每日现磨"}'}}
    ]}}]}))
  }).listen(0,'127.0.0.1')
  await once(server,'listening')
  const config={llmUrl:`http://127.0.0.1:${(server.address() as any).port}/v1/`,llmKey:'',llmModel:'Qwen3.8-27B-FP8',llmTimeoutMs:10_000}
  const tools=[{type:'function',function:{name:'insert_clip'}}]
  try {
    const turn=await requestPlannerTurn(config,[{role:'user',content:'咖啡带货短片添加标题'}],tools)
    assert.equal(received[0].authorization,undefined)
    assert.equal(received[0].body.model,'Qwen3.8-27B-FP8')
    assert.equal(received[0].body.stream,false)
    assert.equal(received[0].body.parallel_tool_calls,false)
    assert.equal(received[0].body.enable_thinking,false)
    const history=[{role:'user',content:'添加标题'},turn,{role:'tool',tool_call_id:turn.tool_calls[0].id,content:'{"created":true}'}]
    await requestPlannerTurn(config,history,tools)
    assert.deepEqual(received[1].body.messages,history)
    assert.notEqual(received[0].body.request_id,received[1].body.request_id)
    finish='length'
    await assert.rejects(requestPlannerTurn(config,history,tools),/未完整生成/)
  } finally {server.close();server.closeAllConnections()}
})
