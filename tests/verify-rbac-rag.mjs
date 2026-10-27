/**
 * 专项验证：会话的数据级属主校验，与知识文档重建索引的幂等性。
 * 覆盖 TC-AUTH-10（USER 读 ADMIN 的会话消息必须 403）、其正向对照
 * （USER 读自己的会话必须 200），以及 TC-RAG-06（连续两次 reindex，向量片段数不得累加）。
 *
 * 不依赖任何固定 ID：会话取对端最新一条，文档取列表第一条，都是运行时现查。
 * 跑法：npm run verify:rbac   （或 cd tests && node verify-rbac-rag.mjs）
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { Suite, assert, exitWith, getJson, getRaw, login, streamChat } from './lib.mjs';

const s = new Suite('verify-rbac-rag');
let adminToken = null;
let userToken = null;

await s.step('login admin & user', async () => {
  adminToken = (await login('admin')).token;
  userToken = (await login('user')).token;
  return 'both roles ready';
});

// 登录不成后面每一步都是噪声，直接收尾退出
if (!adminToken || !userToken) {
  console.log('! 登录失败，剩下用例无意义（确认后端已启动、种子账号存在）');
  process.exit(exitWith(s));
}

// 接口级 403 已由 smoke 覆盖，这里验第二道关卡：属主校验（ChatHistoryService.requireOwned）
await s.step('TC-AUTH-10 USER reads ADMIN session messages', async () => {
  const sessions = await getJson('/api/chat/sessions', { token: adminToken });
  assert(sessions.length >= 1, 'admin 没有会话，先跑一次 npm run smoke');
  const target = sessions[0].id;
  const res = await getRaw(`/api/chat/sessions/${target}/messages`, { token: userToken });
  assert(res.status === 403, `越权读他人会话应 403，实际 ${res.status}`);
  const body = JSON.parse(res.text);
  assert(body.traceId && body.status === 403 && body.message, `错误体结构不对：${res.text}`);
  return `sessionId=${target} message="${body.message}" traceId=${body.traceId}`;
});

// 正向对照：同一个接口换成属主身份必须放行，否则上一条的 403 可能只是路由被误配
await s.step('positive control: USER reads own session messages', async () => {
  const { last } = await streamChat({ message: '你好，做个属主对照测试' }, userToken);
  assert(last?.sessionId, `对话没有返回 sessionId：${JSON.stringify(last)}`);
  // 消息在流收尾时落库，留一点时间窗避免读到空列表
  await new Promise((resolve) => setTimeout(resolve, 300));
  const messages = await getJson(`/api/chat/sessions/${last.sessionId}/messages`, { token: userToken });
  assert(messages.length >= 2, `自己的会话应能读到问答两条，实际 ${messages.length} 条`);
  assert(messages.every((m) => m.content !== undefined), `消息结构不对：${JSON.stringify(messages[0])}`);
  return `sessionId=${last.sessionId} messages=${messages.map((m) => m.role).join('/')}`;
});

// 重建索引是先删后写：跑几轮片段数都应一致，否则换 embedding 模型会把库越 reindex 越大
await s.step('TC-RAG-06 reindex twice keeps chunkCount stable', async () => {
  const docs = await getJson('/api/knowledge', { token: adminToken });
  assert(docs.length >= 1, '知识库为空，DataSeeder 是否跑过？');
  const target = docs[0];
  const chunks = async () => {
    const again = await getJson('/api/knowledge', { token: adminToken });
    return again.find((d) => d.id === target.id);
  };
  const reindexAndRead = async () => {
    await getJson(`/api/knowledge/${target.id}/reindex`, { method: 'POST', token: adminToken });
    return chunks();
  };

  const after1 = await reindexAndRead();
  const after2 = await reindexAndRead();
  assert(after1.chunkCount === target.chunkCount, `第一次 reindex 后片段数变化：${target.chunkCount} -> ${after1.chunkCount}`);
  assert(after2.chunkCount === target.chunkCount, `第二次 reindex 后片段数变化：${target.chunkCount} -> ${after2.chunkCount}`);
  assert(after2.status === 'INDEXED', `重建后状态应为 INDEXED，实际 ${after2.status}`);
  return `docId=${target.id} chunkCount ${target.chunkCount} -> ${after1.chunkCount} -> ${after2.chunkCount}`;
});

exitWith(s);
