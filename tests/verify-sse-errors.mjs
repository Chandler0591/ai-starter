/**
 * 专项验证：SSE 编排前置阶段（prepare）出错时，必须以 error 帧收尾而不是直接断流。
 * 对应测试计划里的 TC-CHAT-09（会话不存在）与 TC-CHAT-10（越权访问他人会话），
 * 也就是缺陷 B9 的回归用例 —— 修复前这两条前端只看到一个莫名崩断的流。
 *
 * 断言只看帧结构与计数，不依赖具体中文文案（文案只打印出来给人看）。
 * 跑法：npm run verify:sse   （或 cd tests && node verify-sse-errors.mjs）
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { Suite, assert, exitWith, gauge, getJson, login, streamChat } from './lib.mjs';

const s = new Suite('verify-sse-errors');
let adminToken = null;
let userToken = null;
let before = 0;

// 两个身份都要：越权请求用 user 发，指标只有 ADMIN 能读
// 干净启动时 prepare 序列还不存在，当 0 看
await s.step('login admin & user, read prepare counter', async () => {
  adminToken = (await login('admin')).token;
  userToken = (await login('user')).token;
  before = (await gauge('ai_degradation_total', 'stage="prepare"', adminToken)) ?? 0;
  return `before=${before}`;
});

// 登录不成后面每一步都是噪声，直接收尾退出
if (!adminToken || !userToken) {
  console.log('! 登录失败，剩下用例无意义（确认后端已启动、种子账号存在）');
  process.exit(exitWith(s));
}

// 用例一：带一个不存在的 sessionId
await s.step('TC-CHAT-09 stream with a nonexistent sessionId', async () => {
  const { status, frames, last } = await streamChat({ message: 'hello', sessionId: 999999 }, userToken);
  assert(status === 200, `应仍是 HTTP 200，实际 ${status}`);
  assert(frames.length === 1, `应只有一帧错误帧，实际 ${frames.length} 帧`);
  assert(last?.error === true && last?.done === true, `末帧应 error+done：${JSON.stringify(last)}`);
  assert(last.traceId, '错误帧缺 traceId');
  return `content="${last.content}" traceId=${last.traceId}`;
});

// 用例二：拿 ADMIN 的最新会话，用 USER 身份续聊 —— 属主校验必须在编排前拦住
await s.step("TC-CHAT-10 stream into someone else's session", async () => {
  const sessions = await getJson('/api/chat/sessions', { token: adminToken });
  assert(sessions.length >= 1, 'admin 没有可用会话，先跑一次 npm run smoke');
  const target = sessions[0].id;
  const { status, frames, last } = await streamChat({ message: 'hello', sessionId: target }, userToken);
  assert(status === 200, `应仍是 HTTP 200，实际 ${status}`);
  assert(frames.length === 1, `应只有一帧错误帧，实际 ${frames.length} 帧`);
  assert(last?.error === true && last?.done === true, `末帧应 error+done：${JSON.stringify(last)}`);
  return `sessionId=${target} content="${last.content}" traceId=${last.traceId}`;
});

// 兜底分支确实被走到：上面两条用例各应打一次 prepare 降级点，所以计数至少增加 2
// （同一 JVM 里可能有并发请求，所以只断下界）
await s.step('ai.degradation{stage="prepare"} increased', async () => {
  const after = await gauge('ai_degradation_total', 'stage="prepare"', adminToken);
  assert(after !== null, '执行完两条用例后仍然没有 ai_degradation_total{stage="prepare"} 序列');
  assert(after - before >= 2, `prepare 降级计数没有增加：before=${before} after=${after}`);
  return `before=${before} after=${after} delta=${after - before}`;
});

exitWith(s);
