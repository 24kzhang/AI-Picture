import { randomUUID } from 'node:crypto'
import { request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { ApiError, invariant } from './errors.js'
import { plannerFailureMessage } from './planner-error.js'

interface PlannerConfig { llmUrl:string; llmKey:string; llmModel:string; llmTimeoutMs:number }

function postJson(url:string,headers:Record<string,string>,body:unknown,timeout:number):Promise<{status:number;body:string}> {
  const target=new URL(url),request=target.protocol==='https:'?httpsRequest:httpRequest
  invariant(['http:','https:'].includes(target.protocol),'规划模型地址必须是 HTTP(S)')
  const payload=JSON.stringify(body)
  return new Promise((resolve,reject)=>{
    // Node fetch 的默认响应头等待上限为 300 秒，早于自建模型的 600 秒推理上限。
    // 原生 HTTP 请求只使用这里的显式总时限，保持断连时取消远端推理的语义。
    const pending=request(target,{method:'POST',headers:{...headers,'Content-Length':String(Buffer.byteLength(payload))},signal:AbortSignal.timeout(timeout)},response=>{
      const chunks:Buffer[]=[];let size=0
      response.on('data',(chunk:Buffer)=>{size+=chunk.length;if(size>8*1024**2){pending.destroy(new Error('规划响应过大'));return}chunks.push(chunk)})
      response.on('end',()=>resolve({status:response.statusCode||502,body:Buffer.concat(chunks).toString('utf8')}))
      response.on('error',reject)
    })
    pending.on('error',error=>reject(new ApiError(502,error.name==='AbortError'?'LLM_TIMEOUT':'LLM_DISCONNECTED',
      error.name==='AbortError'?'规划等待超时，候选修改未提交，请拆分指令后重试':'规划模型连接中断，候选修改未提交，请稍后重试')))
    pending.end(payload)
  })
}

/** 自建接口同步生成，返回原生 tool_calls，工具仍由 Agent 的既有校验器执行。 */
export async function requestPlannerTurn(config:PlannerConfig,messages:any[],tools:any[]) {
  const headers:Record<string,string>={'Content-Type':'application/json'}
  if(config.llmKey.trim())headers.Authorization=`Bearer ${config.llmKey}`
  const response=await postJson(`${config.llmUrl.replace(/\/+$/,'')}/chat/completions`,headers,
    {model:config.llmModel,messages,tools,tool_choice:'auto',
      temperature:0,max_tokens:4096,stream:false,enable_thinking:false,parallel_tool_calls:false,
      request_id:`gallery-video-${randomUUID()}`},config.llmTimeoutMs)
  if(response.status<200||response.status>=300)throw new ApiError(502,'LLM_FAILED',plannerFailureMessage(response.status,response.body))
  const envelope=JSON.parse(response.body) as any,choice=envelope.choices?.[0],turn=choice?.message
  invariant(turn?.role==='assistant','对话模型没有返回有效消息')
  if(choice.finish_reason==='length')throw new ApiError(502,'LLM_TRUNCATED','规划结果未完整生成，请拆分指令后重试；本轮候选编辑未提交')
  if(turn.tool_calls!==undefined) {
    invariant(Array.isArray(turn.tool_calls)&&turn.tool_calls.length<=1,'模型没有遵守单工具调用约定')
    for(const call of turn.tool_calls) {
      invariant(call.id&&call.type==='function'&&typeof call.function?.arguments==='string','模型返回无效工具调用')
      invariant(tools.some(tool=>tool.function.name===call.function.name),'模型调用了未注册工具')
      let args:unknown
      try {args=JSON.parse(call.function.arguments)}catch {throw new ApiError(502,'LLM_INVALID_ARGUMENTS','规划模型返回无效工具参数')}
      invariant(args!==null&&typeof args==='object'&&!Array.isArray(args),'工具参数必须是对象')
    }
  }
  invariant(turn.tool_calls?.length||typeof turn.content==='string','对话模型没有返回文本或工具调用')
  return turn
}
