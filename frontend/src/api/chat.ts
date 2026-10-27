/**
 * 对话相关接口：会话 CRUD 与 SSE 流式问答。
 * 流式消费不用 EventSource（它只支持 GET 且不能带 Authorization），而是 fetch + ReadableStream 自己拆帧。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { authHeaders, request, toError } from './http'
import type { ChatChunk, ChatRequest, MessageView, SessionView } from './types'

/** 当前登录用户的会话列表，按 id 倒序（最新在前） */
export function listSessions() {
  return request<SessionView[]>('/api/chat/sessions')
}

/** 某个会话的全部消息；越权访问他人会话时后端返 403，由 request() 转成 ApiError */
export function listMessages(sessionId: number) {
  return request<MessageView[]>(`/api/chat/sessions/${sessionId}/messages`)
}

/** 删除会话；后端先校验属主，再清掉该会话的全部消息 */
export function deleteSession(sessionId: number) {
  return request<void>(`/api/chat/sessions/${sessionId}`, { method: 'DELETE' })
}

/** 流式回调 */
export interface StreamHandlers {
  /** 每收到一帧 data 回调一次，包含增量内容或 done/error 标记 */
  onChunk: (chunk: ChatChunk) => void
  /** 流结束时回调（包括收到 done 帧后主动断开），用于收尾 UI 状态 */
  onFinish?: () => void
}

/**
 * SSE 逐 token 消费：用 fetch + ReadableStream 而不是 EventSource，
 * 因为对话是 POST 且需要携带 Authorization 头。
 *
 * @param payload  对话请求（消息、会话 id、Prompt 版本等）
 * @param handlers 逐帧回调与收尾回调
 * @param signal   外部中止信号，“停止生成”靠它断开连接
 * @throws 非 2xx 响应抛 ApiError；浏览器无 res.body 时抛普通 Error
 */
export async function streamChat(
  payload: ChatRequest,
  handlers: StreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const res = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { ...authHeaders({ 'Content-Type': 'application/json' }), Accept: 'text/event-stream' },
    body: JSON.stringify(payload),
    signal
  })
  if (!res.ok) throw await toError(res)
  if (!res.body) throw new Error('浏览器不支持流式响应')

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  // 服务端已给 done 帧就不再等连接关闭：代理/ keep-alive 下连接可能不立即断，
  // 依赖 reader 结束会让 UI 一直停在“生成中”
  outer: while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')

    let sep
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      // 一帧可能有多行 data:，需要拼接（JSON 体较长时服务端可能分多行写）
      const data = frame
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trim())
        .join('')
      if (!data) continue
      try {
        const chunk = JSON.parse(data) as ChatChunk
        handlers.onChunk(chunk)
        if (chunk.done) break outer
      } catch {
        // 心跳或非 JSON 帧忽略，避免打断渲染
      }
    }
  }

  try {
    await reader.cancel()
  } catch {
    // 连接已关闭时 cancel 会抛错，忽略
  }
  handlers.onFinish?.()
}
