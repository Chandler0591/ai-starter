/**
 * 前端类型定义：与后端 DTO 一一对应（com.aistarter.dto / eval），改后端记得同步这里。
 * 这里不写任何逻辑，只维护结构契约；字段可选性与后端保持一致（可空的用 ? 或 | null）。
 *
 * @author Flynn
 * @date 2026-10-27
 */

/** 角色字面量，与后端 Role 枚举同步 */
export type Role = 'ROLE_ADMIN' | 'ROLE_USER'

/** 登录成功响应：token 由调用方写入本地登录态，过期秒数仅用于提示 */
export interface LoginResponse {
  token: string
  tokenType: string
  expiresInSeconds: number
  username: string
  role: Role
}

/** 当前登录者信息（/api/auth/me） */
export interface MeResponse {
  id: number
  username: string
  displayName: string
  role: Role
}

/**
 * 对话请求体。
 *
 * sessionId 为空则开新会话；promptVersion 为空后端按 v1 处理；useTools 为空视为开启。
 */
export interface ChatRequest {
  message: string
  sessionId?: number | null
  promptVersion?: string
  useTools?: boolean
}

/** SSE 单帧：done=true 结束，error=true 表示护栏拦截或降级文案 */
export interface ChatChunk {
  content: string
  done: boolean
  error: boolean
  traceId: string
  sessionId: number | null
  provider: string
}

/** 会话列表项；promptVer 是请求里带的版本原值（可能是 "latest"），不是解析后的具体版本 */
export interface SessionView {
  id: number
  title: string
  promptKey: string
  promptVer: string
  createdAt: string
}

/** 历史消息项，role 为 user / assistant（服务端落库的原值） */
export interface MessageView {
  id: number
  role: string
  content: string
  traceId: string
  createdAt: string
}

/** 索引状态：FAILED 可用 reindex 重试 */
export type IndexStatus = 'PENDING' | 'INDEXED' | 'FAILED'

/** 知识文档元数据（不含正文） */
export interface DocView {
  id: number
  title: string
  source: string
  chunkCount: number
  status: IndexStatus
  createdAt: string
}

/** 手工录入请求；source 为空时后端默认 MANUAL */
export interface UploadRequest {
  title: string
  content: string
  source?: string
}

/** 一行指标：values 的 key 是统计名（count / totalAmount / max 等），不同 meter 类型不一样 */
export interface MetricRow {
  name: string
  tags: Record<string, string>
  values: Record<string, number>
}

/** 看板快照 */
export interface DashboardView {
  provider: string
  prompts: Record<string, string[]>
  knowledgeDocs: number
  metrics: MetricRow[]
}

/** 单条评测用例结果；actual 已被后端截断到 200 字 */
export interface EvalCaseResult {
  id: string
  input: string
  expectContains: string[]
  actual: string
  passed: boolean
}

/** 一轮评测汇总；passed < total 时页面应标红列出失败用例 */
export interface EvalReport {
  ranAt: string
  provider: string
  total: number
  passed: number
  results: EvalCaseResult[]
}

/** 后端统一错误体（含 SecurityConfig 直出的 401/403） */
export interface ErrorResponse {
  status: number
  message: string
  traceId?: string
}
