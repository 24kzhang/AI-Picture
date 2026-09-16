import type { AgentRunEvent } from '@/api/agent'

/**
 * Agent 运行实时事件（SSE）客户端。
 * 云图库后端以事件名 snapshot / progress 推送快照，服务端心跳为注释帧。
 */

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export interface RunEventHandlers {
  onSnapshot?: (event: AgentRunEvent) => void
  onProgress?: (event: AgentRunEvent) => void
  onError?: (error: Event) => void
  onOpen?: () => void
}

/**
 * 订阅指定运行的实时事件，返回取消订阅函数。
 */
export function openRunEvents(runId: string | number, handlers: RunEventHandlers): () => void {
  const source = new EventSource(`${BASE_URL}/api/agent-runs/${runId}/events`, {
    withCredentials: true,
  })

  const parse = (raw: string): AgentRunEvent | null => {
    try {
      return JSON.parse(raw) as AgentRunEvent
    } catch {
      return null
    }
  }

  const handle = (callback?: (event: AgentRunEvent) => void) => (event: MessageEvent) => {
    const parsed = parse(event.data)
    if (parsed && callback) {
      callback(parsed)
    }
  }

  source.addEventListener('open', () => handlers.onOpen?.())
  source.addEventListener('snapshot', handle(handlers.onSnapshot))
  source.addEventListener('progress', handle(handlers.onProgress))
  source.onmessage = handle(handlers.onSnapshot)
  source.onerror = (error) => handlers.onError?.(error)

  return () => source.close()
}
