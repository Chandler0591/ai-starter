/**
 * 运行看板与离线评测接口（均仅 ADMIN，普通用户调用会得到 403）。
 * 看板指标直接读后端 MeterRegistry，无需接 Prometheus。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { request } from './http'
import type { DashboardView, EvalReport } from './types'

/** 拉一次 ai.* 指标快照（含 provider、Prompt 目录、知识文档数） */
export function fetchMetrics() {
  return request<DashboardView>('/api/dashboard/metrics')
}

/**
 * 同步跑一遍回归集，返回完整报告。
 * 后端逐条走完整对话管道，用例多时响应会比较久，页面侧需要 loading 与防重复点击。
 */
export function runEval() {
  return request<EvalReport>('/api/eval/run', { method: 'POST' })
}

/** 取服务内存里最近一次报告；重启后或从未跑过则为 null */
export function lastEvalReport() {
  return request<EvalReport | null>('/api/eval/report')
}
