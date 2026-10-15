import { randomUUID } from 'node:crypto'
import { ApiError, invariant } from './errors.js'
import { plannerFailureMessage } from './planner-error.js'

interface PlannerConfig { llmUrl:string; llmKey:string; llmModel:string; llmTimeoutMs:number }

/** 自建接口同步生成，返回原生 tool_calls，工具仍由 Agent 的既有校验器执行。 */
export async function requestPlannerTurn(config:PlannerConfig,messages:any[],tools:any[]) {
  const headers:Record<string,string>={'Content-Type':'application/json'}
  if(config.llmKey.trim())headers.Authorization=`Bearer ${config.llmKey}`
  const response=await fetch(`${config.llmUrl.replace(/\/+$/,'')}/chat/completions`,{
    method:'POST',headers,body:JSON.stringify({model:config.llmModel,messages,tools,tool_choice:'auto',
      temperature:0,max_tokens:4096,stream:false,enable_thinking:false,parallel_tool_calls:false,
      request_id:`gallery-video-${randomUUID()}`}),signal:AbortSignal.timeout(config.llmTimeoutMs),
  })
  if(!response.ok)throw new ApiError(502,'LLM_FAILED',plannerFailureMessage(response.status,await response.text()))
  const envelope=await response.json() as any,choice=envelope.choices?.[0],turn=choice?.message
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
