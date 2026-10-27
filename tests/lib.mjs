/**
 * tests 共用能力层：登录取令牌、JSON 请求、SSE 逐帧读取、Prometheus 文本解析、断言与用例统计。
 *
 * 只用 Node 内置模块（fetch / fs / path / url），零第三方依赖，所以 Windows、macOS、Linux
 * 与 CI 里都是同一条命令（npm run smoke）。Node 需 >= 18（全局 fetch），仓库 frontend 已要求 20+。
 *
 * 环境变量：BASE_URL（默认 http://localhost:8080）、ADMIN_USER / ADMIN_PASSWORD、
 * USER_USER / USER_PASSWORD —— 换端口或换种子账号时不用改代码。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const BASE_URL = (process.env.BASE_URL ?? 'http://localhost:8080').replace(/\/+$/, '');
export const CASE_DIR = join(dirname(fileURLToPath(import.meta.url)), 'cases');

/** 请求超时（毫秒）：SSE 首帧慢或后端没起时不要挂死在这里 */
const TIMEOUT_MS = Number(process.env.TIMEOUT_MS ?? 30000);

const ACCOUNTS = {
  admin: { username: process.env.ADMIN_USER ?? 'admin', password: process.env.ADMIN_PASSWORD ?? 'admin123' },
  user: { username: process.env.USER_USER ?? 'user', password: process.env.USER_PASSWORD ?? 'user123' },
};

/** HTTP 非 2xx 时抛出，保留状态码与响应体，便于断言 401/403 这类预期失败 */
export class ApiError extends Error {
  constructor(status, bodyText, url) {
    super(`HTTP ${status} ${url}\n${truncate(bodyText, 400)}`);
    this.status = status;
    this.bodyText = bodyText;
  }
}

/**
 * 基础请求封装：自动拼 BASE_URL、带 Bearer 令牌、JSON 序列化与超时。
 * 返回原始 Response，由调用方决定怎么消费 body。
 */
export async function send(path, { method = 'GET', token, json, headers, accept } = {}) {
  const reqHeaders = { Accept: accept ?? 'application/json', ...headers };
  if (token) reqHeaders.Authorization = `Bearer ${token}`;
  if (json !== undefined) reqHeaders['Content-Type'] = 'application/json';

  const url = `${BASE_URL}${path}`;
  const res = await fetch(url, {
    method,
    headers: reqHeaders,
    body: json === undefined ? undefined : JSON.stringify(json),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  }).catch((e) => {
    throw new Error(`请求 ${method} ${url} 发不出去：${e.message}（后端是否已启动？BASE_URL=${BASE_URL}）`);
  });
  return res;
}

/** 期望 2xx 并解析 JSON；非 2xx 抛 ApiError */
export async function getJson(path, opts = {}) {
  const res = await send(path, opts);
  const text = await res.text();
  if (!res.ok) throw new ApiError(res.status, text, path);
  return text ? JSON.parse(text) : null;
}

/** 只关心状态码与响应体的场景（RBAC、越权、401 等），不抛异常 */
export async function getRaw(path, opts = {}) {
  const res = await send(path, opts);
  return { status: res.status, text: await res.text() };
}

/** 登录并取回 JWT；失败直接抛错，因为后面每一步都依赖它 */
export async function login(account = 'admin') {
  const { username, password } = ACCOUNTS[account];
  const body = await getJson('/api/auth/login', { method: 'POST', json: { username, password } });
  return { token: body.token, role: body.role, username: body.username, expiresIn: body.expiresInSeconds };
}

/** 读取 tests/cases 下的请求体语料 */
export async function loadCase(fileName) {
  const text = await readFile(join(CASE_DIR, fileName), 'utf8');
  return JSON.parse(text);
}

/**
 * 发一次 SSE 对话并把帧收全：返回 status、帧数组与拼接后的完整回答。
 * 后端把护栏拦截、越权、模型降级都包成 error 帧（HTTP 仍是 200），所以这里必须看帧而不是看状态码。
 */
export async function streamChat(body, token) {
  const res = await send('/api/chat/stream', {
    method: 'POST',
    token,
    json: body,
    accept: 'text/event-stream',
  });
  const frames = [];
  if (!res.ok) {
    const text = await res.text();
    throw new ApiError(res.status, text, '/api/chat/stream');
  }

  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  for await (const chunk of res.body) {
    buffer += decoder.decode(chunk, { stream: true });
    // SSE 一帧以空行分隔；跨 chunk 的半行留在 buffer 里等下一片
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).replace(/\r$/, '');
      buffer = buffer.slice(idx + 1);
      if (line.startsWith('data:')) frames.push(JSON.parse(line.slice(5).trim()));
    }
  }
  buffer += decoder.decode();

  const content = frames.map((f) => f.content ?? '').join('');
  return { status: res.status, frames, content, last: frames[frames.length - 1] ?? null };
}

/**
 * 读 /actuator/prometheus（仅 ADMIN）里某个指标的值；找不到返回 null。
 * @param name 指标名，如 ai_degradation_total
 * @param labelContains 标签片段过滤，如 'stage="prepare"'
 */
export async function gauge(name, labelContains, adminToken) {
  // 该端点的 produces 带 version 参数（text/plain; version=0.0.4），Accept 写 */* 最稳，写 text/plain 会被 406 拦
  const res = await send('/actuator/prometheus', { token: adminToken, accept: '*/*' });
  if (!res.ok) throw new ApiError(res.status, await res.text(), '/actuator/prometheus');
  const text = await res.text();
  for (const line of text.split('\n')) {
    if (line.startsWith('#') || !line.startsWith(name)) continue;
    if (labelContains && !line.includes(labelContains)) continue;
    const value = Number(line.trim().split(/\s+/).pop());
    if (!Number.isNaN(value)) return value;
  }
  return null;
}

/** 断言失败时抛错，由 Suite.step 记为 FAIL */
export function assert(condition, message) {
  if (!condition) throw new Error(message);
}

/**
 * 用例统计器：把每一步包成 PASS/FAIL，结尾统一打印并给出退出码（0 全绿 / 1 有失败）。
 */
export class Suite {
  constructor(title) {
    this.title = title;
    this.passed = 0;
    this.failures = [];
    this.index = 0;
    console.log(`\n=== ${title} @ ${BASE_URL} ===`);
  }

  /** 执行一步；fn 返回的字符串会作为附加说明打印出来 */
  async step(label, fn) {
    this.index += 1;
    try {
      const detail = await fn();
      this.passed += 1;
      console.log(`[ok  ${this.index}] ${label}${detail ? `  ->  ${detail}` : ''}`);
    } catch (e) {
      this.failures.push({ label, message: e.message ?? String(e) });
      console.log(`[FAIL ${this.index}] ${label}  ->  ${truncate(e.message ?? String(e), 200)}`);
    }
  }

  /** 打印汇总；返回进程退出码 */
  finish() {
    const total = this.passed + this.failures.length;
    console.log(`\n--- ${this.title}: ${this.passed}/${total} passed ---`);
    for (const f of this.failures) console.log(`  FAIL ${f.label}: ${truncate(f.message, 300)}`);
    return this.failures.length === 0 ? 0 : 1;
  }
}

function truncate(text, max) {
  const oneLine = String(text).replace(/\s+/g, ' ').trim();
  return oneLine.length > max ? `${oneLine.slice(0, max)}...` : oneLine;
}

/**
 * 脚本收尾：把汇总结果写进 process.exitCode 并返回退出码。
 * 必须显式 return，否则调用方 `process.exit(exitWith(s))` 会拿到 undefined 而退成 0（失败就骗过 CI 了）。
 */
export function exitWith(suite) {
  const code = suite.finish();
  process.exitCode = code;
  return code;
}
