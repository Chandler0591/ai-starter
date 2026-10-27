/**
 * 主链路冒烟：后端已启动（默认 http://localhost:8080）+ PostgreSQL 健康时跑一遍全链路。
 *
 * 11 步覆盖：登录 → /me → SSE 对话（RAG 命中 / 工具调用 / 护栏拦截）→ RBAC 403 与 401
 * → 知识库列表 → 会话与历史消息 → 看板指标 → 离线评测 → Prometheus ai_* 序列。
 * 只有第 10 步会往库里写数据（评测每条用例新建一个会话），其余步骤都是只读。
 *
 * 跑法（任意平台，需 Node >= 18）：
 *   cd tests && npm run smoke
 *   或仓库根目录：npm run smoke
 *   换目标：BASE_URL=http://127.0.0.1:9090 npm run smoke
 *
 * 退出码：全绿 0，任一步失败 1（失败步骤会打印原因）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { Suite, assert, exitWith, gauge, getJson, getRaw, loadCase, login, streamChat } from './lib.mjs';

const s = new Suite('smoke');
let adminToken = null;
let userToken = null;

// 1) 两个角色都要登录成功：后面既有 ADMIN-only 接口，也要 USER 视角的越权用例
await s.step('login admin & user', async () => {
  const admin = await login('admin');
  const user = await login('user');
  adminToken = admin.token;
  userToken = user.token;
  assert(admin.role === 'ROLE_ADMIN', `admin 角色应为 ROLE_ADMIN，实际 ${admin.role}`);
  assert(user.role === 'ROLE_USER', `user 角色应为 ROLE_USER，实际 ${user.role}`);
  return `admin=${admin.role} expires=${admin.expiresIn}s, user=${user.role}`;
});

// 登录不成后面每一步都是噪声，直接收尾退出
if (!adminToken || !userToken) {
  console.log('! 登录失败，剩下 10 步没有意义（确认后端已启动、种子账号存在）');
  process.exit(exitWith(s));
}

// 2) 令牌能被 JwtAuthenticationFilter 解析并回查到库里的当前身份
await s.step('GET /api/auth/me', async () => {
  const me = await getJson('/api/auth/me', { token: adminToken });
  assert(me.username === 'admin' && me.role === 'ROLE_ADMIN', `返回异常：${JSON.stringify(me)}`);
  return `id=${me.id} username=${me.username}`;
});

// 3) RAG 命中：语料问的是种子文档里的年假条款，mock 回答必须带上「已依据知识库片段作答」
await s.step('SSE chat, RAG hit (admin)', async () => {
  const { frames, content, last } = await streamChat(await loadCase('case-rag.json'), adminToken);
  assert(frames.length > 1, `只收到 ${frames.length} 帧，流式没有分片输出`);
  assert(last?.done === true, '末帧缺少 done=true');
  assert(content.includes('已依据知识库片段作答'), `未命中知识库：${content}`);
  return `frames=${frames.length} sessionId=${last.sessionId} traceId=${last.traceId}`;
});

// 4) 工具链路：USER 问订单，服务端前置调用应把 queryMyRecentOrders 挂进本轮工具集
await s.step('SSE chat, tool available for USER', async () => {
  const { content } = await streamChat(await loadCase('case-order.json'), userToken);
  assert(content.includes('queryMyRecentOrders'), `工具集里没有订单工具：${content}`);
  return `answer length=${content.length}`;
});

// 5) 护栏：注入类问题必须以 error 帧收尾（HTTP 仍是 200），不能破流
await s.step('SSE chat, guardrail blocked', async () => {
  const { status, last } = await streamChat(await loadCase('case-guardrail.json'), adminToken);
  assert(status === 200, `护栏拦截也应返回 200，实际 ${status}`);
  assert(last?.error === true && last?.done === true, `末帧不是 error+done：${JSON.stringify(last)}`);
  return `blocked message=${last.content}`;
});

// 6) 两道权限关卡：接口级 403（USER 写知识库）与未认证 401，错误体都得带 traceId 便于捞日志
await s.step('RBAC 403 & unauthenticated 401', async () => {
  const forbidden = await getRaw('/api/knowledge', {
    method: 'POST',
    token: userToken,
    json: { title: 't', content: 'c' },
  });
  assert(forbidden.status === 403, `USER 写知识库应 403，实际 ${forbidden.status}`);
  const unauth = await getRaw('/api/auth/me');
  assert(unauth.status === 401, `无令牌应 401，实际 ${unauth.status}`);
  for (const r of [forbidden, unauth]) {
    const body = JSON.parse(r.text);
    assert(body.traceId, `错误体缺 traceId：${r.text}`);
    assert(body.status === r.status && body.message, `错误体结构不对：${r.text}`);
  }
  return `403=${JSON.parse(forbidden.text).traceId} 401=${JSON.parse(unauth.text).traceId}`;
});

// 7) 知识库列表：只给元数据（正文不外泄），且种子文档已建索引
await s.step('GET /api/knowledge', async () => {
  const docs = await getJson('/api/knowledge', { token: adminToken });
  assert(docs.length >= 1, '知识库为空，DataSeeder 是否跑过？');
  assert(docs.every((d) => d.content === undefined), '列表不应返回正文');
  const indexed = docs.filter((d) => d.status === 'INDEXED');
  assert(indexed.length >= 1, `没有已索引文档：${JSON.stringify(docs)}`);
  return `docs=${docs.length} indexed=${indexed.length} firstChunks=${docs[0].chunkCount}`;
});

// 8) 会话与历史：列表可见，且能按属主读到消息（ADMIN 可跨用户，USER 不行见 verify 脚本）
await s.step('GET /api/chat/sessions & messages', async () => {
  const sessions = await getJson('/api/chat/sessions', { token: adminToken });
  assert(sessions.length >= 1, '会话列表为空，前面步骤没产生会话？');
  const messages = await getJson(`/api/chat/sessions/${sessions[0].id}/messages`, { token: adminToken });
  assert(Array.isArray(messages), `历史消息返回结构不对：${JSON.stringify(messages).slice(0, 120)}`);
  return `sessions=${sessions.length} messagesInFirst=${messages.length} title="${sessions[0].title}"`;
});

// 9) 看板：一次拉齐 provider、Prompt 目录与 ai.* 指标；关键埋点必须已注册
await s.step('GET /api/dashboard/metrics', async () => {
  const dash = await getJson('/api/dashboard/metrics', { token: adminToken });
  assert(dash.provider, 'provider 为空');
  const names = dash.metrics.map((m) => m.name);
  for (const required of ['ai.llm.calls', 'ai.rag.contexts', 'ai.tool.calls', 'ai.guardrail.blocked']) {
    assert(names.includes(required), `缺少指标 ${required}，实际：${names.join(', ')}`);
  }
  const ragRow = dash.metrics.find((m) => m.name === 'ai.rag.contexts');
  return `provider=${dash.provider} docs=${dash.knowledgeDocs} rows=${dash.metrics.length} ragContexts=${ragRow.values.count}`;
});

// 10) 离线评测：走完整管道做关键词断言，全过才算不回归
await s.step('POST /api/eval/run', async () => {
  const report = await getJson('/api/eval/run', { method: 'POST', token: adminToken });
  assert(report.total >= 4, `评测用例数偏少：${report.total}`);
  const failed = report.results.filter((r) => !r.passed).map((r) => r.id);
  assert(failed.length === 0, `未通过用例：${failed.join(', ')}`);
  return `provider=${report.provider} passed=${report.passed}/${report.total}`;
});

// 11) Prometheus 抓取端点：指标名把点换成下划线，看板之外的告警都依赖它
await s.step('GET /actuator/prometheus ai_* series', async () => {
  const res = await getRaw('/actuator/prometheus', { token: adminToken, accept: '*/*' });
  assert(res.status === 200, `抓取端点应 200，实际 ${res.status}`);
  const series = new Set(
    res.text
      .split('\n')
      .filter((line) => line.startsWith('ai_') && !line.startsWith('#'))
      .map((line) => line.slice(0, line.indexOf('{') === -1 ? line.indexOf(' ') : line.indexOf('{'))),
  );
  assert(series.size >= 5, `ai_* 指标族偏少（${series.size}）：${[...series].join(', ')}`);
  const degradation = await gauge('ai_degradation_total', null, adminToken);
  return `series=${[...series].sort().join(', ')} degradation=${degradation}`;
});

exitWith(s);
