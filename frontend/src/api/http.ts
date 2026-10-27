/**
 * HTTP 访问层：统一 JWT 存取、请求头拼装、错误结构解析与 401 自动登出。
 * 不依赖 axios，也不反向引用 router/store（登出回调由外部注入）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import type { ErrorResponse } from './types'

/** localStorage 里 token 的 key；改名会遗失存量登录态，需与 clearSession 一起改 */
const TOKEN_KEY = 'ai-starter-token'
/** localStorage 里登录用户快照的 key（仅用于刷新后还原展示，权限一律以服务端为准） */
const USER_KEY = 'ai-starter-user'

/** 本地缓存的最小用户信息 */
export interface StoredUser {
  username: string
  role: string
}

/** 401 时的回调由 router/store 注入，避免 http 反向依赖路由 */
let unauthorizedHandler: () => void = () => {}

/** 注册 401 处理逻辑（典型实现：清登录态 + 跳登录页） */
export function setUnauthorizedHandler(fn: () => void) {
  unauthorizedHandler = fn
}

/** 读当前 JWT；未登录返回 null */
export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

/** 读本地用户快照；存的不是合法 JSON 时会抛，调用方需先保证走的是 saveSession 写入 */
export function getStoredUser(): StoredUser | null {
  const raw = localStorage.getItem(USER_KEY)
  return raw ? (JSON.parse(raw) as StoredUser) : null
}

/** 登录成功后一次性写入 token 与用户信息 */
export function saveSession(token: string, user: StoredUser) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/** 清除本地登录态（登出、401、令牌过期都走这里） */
export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/**
 * 接口错误：带 HTTP 状态码与 traceId。
 * traceId 来自后端响应头或错误体，展示给用户可直接用于捞日志。
 */
export class ApiError extends Error {
  readonly status: number
  readonly traceId?: string

  constructor(status: number, message: string, traceId?: string) {
    super(message)
    this.status = status
    this.traceId = traceId
  }
}

/** 把非 2xx 响应转成 ApiError：优先用后端统一错误体的 message/traceId，解析失败则兜底默认文案 */
export async function toError(res: Response): Promise<ApiError> {
  let message = `请求失败（HTTP ${res.status}）`
  let traceId: string | undefined = res.headers.get('X-Trace-Id') ?? undefined
  try {
    const body = (await res.json()) as ErrorResponse
    if (body?.message) message = body.message
    if (body?.traceId) traceId = body.traceId
  } catch {
    // 非 JSON 响应体，保留默认信息
  }
  return new ApiError(res.status, message, traceId)
}

/** 拼装带 Bearer 头的请求头；供 SSE、上传等不走 request() 的场景复用 */
function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const token = getToken()
  const headers: Record<string, string> = { ...(extra ?? {}) }
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}

/** 统一的 JSON 请求入口：自动带 JWT、统一错误结构、401 自动登出 */
export async function request<T>(
  path: string,
  options: { method?: string; body?: unknown; auth?: boolean } = {}
): Promise<T> {
  const { method = 'GET', body, auth = true } = options
  const headers: Record<string, string> = {}
  // Content-Type 与是否登录无关：登录接口不带头会被后端当 text/plain 而返回 415
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const init: RequestInit = {
    method,
    headers: auth ? authHeaders(headers) : headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  }
  const res = await fetch(path, init)
  if (res.status === 401 && auth) {
    unauthorizedHandler()
    throw await toError(res)
  }
  if (!res.ok) throw await toError(res)
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/** 文件上传走 multipart，不能手动设置 Content-Type */
export async function upload<T>(path: string, file: File): Promise<T> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch(path, { method: 'POST', headers: authHeaders(), body: form })
  if (res.status === 401) {
    unauthorizedHandler()
    throw await toError(res)
  }
  if (!res.ok) throw await toError(res)
  return (await res.json()) as T
}

/** authHeaders 只给需要自己发 fetch 的场景用（目前仅 chat.ts 的 SSE），其余请求走 request() / upload() */
export { authHeaders }
