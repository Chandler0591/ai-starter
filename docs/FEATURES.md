# 功能说明

> 对象：`ai-starter` —— 一个「企业级 Java 工程能力 + AI 工程化能力」的全栈起步模板
> 读者：产品/使用方、接口对接方、二次开发同学
> 相关：[架构说明](ARCHITECTURE.md) · [部署文档](DEPLOYMENT.md) · [测试计划](TEST-PLAN.md)

---

## 1. 这是什么 / 给谁用

一个可直接跑起来的**企业 AI 助手底座**：用户登录后能在网页里和 AI 多轮对话，AI 的回答基于**你自己的知识库**（RAG）和**你自己的业务数据库**（工具调用），全过程有登录鉴权、角色权限、输入护栏、链路追踪、指标看板与回归评测。

| 角色 | 能做什么 |
| --- | --- |
| `USER`（示例账号 `user/user123`） | 对话（含自己的历史会话）、查看知识库、只能触发与本人数据相关的工具 |
| `ADMIN`（示例账号 `admin/admin123`） | USER 的全部能力 + 录入/上传/删除/重建知识文档 + 运行看板 + 跑评测回归 + actuator 指标 |

面向三类使用者：

- **想抄一套能跑的 AI 后端骨架的团队**：`ai` 包那条编排管道可以整体搬走，换成自己的模型与知识库。
- **需要在没有 API Key 的环境里做演示/教学的场景**：默认 `AI_PROVIDER=mock`，全链路零成本可跑，且指标、评测、SSE 都是真的。
- **要做 AI 能力验收的同学**：评测集 + 启动即门禁（`--eval=true`）+ 冒烟脚本，改 Prompt/阈值当天就能知道有没有退化。

---

## 2. 功能总览

| 模块 | 功能 | 角色 | 入口（页面 / 接口） |
| --- | --- | --- | --- |
| 认证 | 账号密码登录换 JWT、查看当前身份 | 全部 | 登录页 / `POST /api/auth/login`、`GET /api/auth/me` |
| 授权 | 接口级 RBAC + 数据级属主校验 + 工具级白名单 | 全部 | 后端 `SecurityConfig` 等三层 |
| 对话 | SSE 流式问答、多轮记忆、会话续用 | 全部 | 智能对话页 / `POST /api/chat/stream` |
| 会话 | 会话列表、历史消息、删除会话 | 全部 | 对话页左侧 / `GET /api/chat/sessions` 等 |
| Prompt | 模板版本化，请求内指定 `v1/v2/latest` | 全部 | 对话页工具栏下拉 |
| RAG | 知识库检索并注入答案依据；无依据则明确拒答 | 全部 | 后端自动 |
| 知识库 | 文本录入、文件上传、重建索引、删除、列表 | 读=全部，写=ADMIN | 知识库页 / `POST /api/knowledge` 等 |
| 工具 | 订单查询（本人数据）、知识检索（ADMIN） | 按角色 | 对话页「工具」开关 |
| 护栏 | 空值/超长/Prompt 注入拦截 | 全部 | 后端自动 |
| 可观测 | traceId 贯穿、`ai.*` 指标、运行看板 | 看板=ADMIN | 看板页 / `GET /api/dashboard/metrics` |
| 评测 | 跑回归集、看最近报告、启动即门禁 | ADMIN | 评测页 / `POST /api/eval/run` |

---

## 3. 页面功能详解

### 3.1 登录页 `/login`

- 用户名 + 密码表单；提供 `admin/admin123`、`user/user123` 一键填充（生产由部署侧关闭种子数据）。
- 成功后提示「欢迎，{用户名}」：ADMIN 落 `/dashboard`，USER 落 `/chat`；从其它页被拦截过来的会回到原目标页（`?redirect=`）。
- 未登录访问任何受限路由都会被送回此处。

### 3.2 主框架

- 左侧菜单按角色过滤：USER 见「智能对话、知识库」；ADMIN 另见「运行看板、评测回归」；底部显示当前用户名与角色标签。
- 退出登录清空本地令牌与身份并回登录页。
- 顶栏展示页面标题。

### 3.3 智能对话页 `/chat`

| 区域 | 功能 | 说明 |
| --- | --- | --- |
| 消息区 | 逐 token 流式渲染 | 用户右、AI 左；流式期间气泡内容持续增长；出错帧以红色提示显示（例如护栏拦截文案、越权提示） |
| 输入框 | 多行输入，`Enter` 发送 / `Shift+Enter` 换行 | 发送后输入框保留可继续输入，流式期间「发送」禁用 |
| 「停止」按钮 | 中断当前流 | 基于 `AbortController`，前端立即复位 loading |
| Prompt 版本下拉 | `latest` / `v2` / `v1` | 用于同一问题的模板 A/B 对比；随请求发送 `promptVersion` |
| 工具开关 | 默认开 | 关闭后本轮不暴露工具、不做前置调用（对照实验用） |
| 左侧会话列表 | 新建会话、点开历史、删除 | 标题自动取首个问题；删除有二次确认；历史消息按属主校验后载入（过滤 system 消息） |

每条回答都会带上服务端返回的 `sessionId`（下一轮自动续用）与 `traceId`（用于对日志/库内消息排查）。

### 3.4 知识库页 `/knowledge`

- 文档列表：标题、来源、片段数、索引状态（`PENDING/INDEXED/FAILED`）、创建时间。
- ADMIN 可：手工录入标题+正文、上传 `.txt`/`.md` 文件（以文件名为标题）、对某篇「重建索引」、删除文档（同时清理其向量）。
- USER 只读（页面显示只读提示；即便直接调写接口也会被后端 403 拦住）。
- 「重建索引」的用途：改过正文、换过切片参数或换过 embedding 模型后，让召回重新对齐。

### 3.5 运行看板页 `/dashboard`（ADMIN）

- 概览卡片：当前模型 provider、知识库文档数、已加载 Prompt 版本、AI 指标条数。
- 指标表：直接读 Micrometer，展示 `ai.*` 指标名 + tag 维度 + 统计值（count/sum/max/VALUE 等），支持 5 秒自动刷新观察增量。
- 观测点示例：LLM 调用次数与成功率、平均/P95 耗时、token 消耗、RAG 命中片段数、工具调用量、护栏拦截量、降级次数（`stage=rag/llm/persist/prepare`）。

### 3.6 评测回归页 `/eval`（ADMIN）

- 「运行评测集」触发 `POST /api/eval/run`：显示 `通过数 / 总数（通过率）`、provider、运行时间，以及每条用例的输入、期望关键词、实际回答、PASS/FAIL。
- 用例集来自 `backend/src/main/resources/eval/eval-cases.json`，改文件即改用例，不动代码。
- 也可 `GET /api/eval/report` 看最近一次报告（进程内缓存）。

---

## 4. 接口说明

通用约定：除登录外都要 `Authorization: Bearer <token>`；请求体 `Content-Type: application/json`（缺头会 415）；错误体统一为 `{ "status", "message", "traceId" }`；响应头带 `X-Trace-Id`。

### 4.1 认证

```http
POST /api/auth/login
{ "username": "admin", "password": "admin123" }

200 →
{ "token": "eyJ…", "tokenType": "Bearer", "expiresInSeconds": 7200,
  "username": "admin", "role": "ROLE_ADMIN" }
```

```http
GET /api/auth/me        → 200 { "id":1, "username":"admin", "displayName":"管理员", "role":"ROLE_ADMIN" }
```

失败：401 `用户名或密码错误`（`{status:401,message,traceId}`）。

### 4.2 对话（SSE）

```http
POST /api/chat/stream
Accept: text/event-stream
{ "message": "公司年假有几天？", "sessionId": null, "promptVersion": "latest", "useTools": true }
```

请求字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `message` | string | 是 | 用户问题；`@Size(max=8000)`，另受护栏 `ai.guardrail.max-input-chars=4000` 约束 |
| `sessionId` | long | 否 | 为空则新开会话；非空校验属主（非 ADMIN 越权 → 一帧错误） |
| `promptVersion` | string | 否 | 默认 `v1`；支持 `v1`/`v2`/`latest` |
| `useTools` | boolean | 否 | 默认为真（`null` 视作开启） |

响应为 `text/event-stream`，每帧 `data:` 是一个 JSON：

```json
{"content":"[MOCK 回答] 知识库命中：1 段","done":false,"error":false,"traceId":"cd8dce740f86","sessionId":12,"provider":"mock"}
{"content":"","done":true,"error":false,"traceId":"cd8dce740f86","sessionId":12,"provider":"mock"}
```

| 字段 | 说明 |
| --- | --- |
| `content` | 本帧增量文本（mock 每帧 12 字符） |
| `done` | 结束帧标志 —— **客户端据此收束**，不必等连接关闭 |
| `error` | 该帧内容是错误提示（护栏拦截、越权、降级兜底等），此时 HTTP 仍是 200 |
| `sessionId` | 服务端确定的会话 id，下一轮请带上 |
| `provider` | `mock` 或 `openai`，便于确认当前实际走的模型 |

### 4.3 会话

```http
GET    /api/chat/sessions                 → [ { "id":12,"title":"公司年假有几天？","promptKey":"system-chat","promptVer":"v2","createdAt":"…" } ]
GET    /api/chat/sessions/{id}/messages   → [ { "id":88,"role":"assistant","content":"…","traceId":"…","createdAt":"…" } ]
DELETE /api/chat/sessions/{id}            → 204
```

### 4.4 知识库

```http
GET    /api/knowledge                     → [ { "id":1,"title":"员工福利制度","source":"SEED","chunkCount":3,"status":"INDEXED","createdAt":"…" } ]
POST   /api/knowledge   (ADMIN)           { "title":"报销细则", "content":"…", "source":"MANUAL" }   → 200 DocView
POST   /api/knowledge/file (ADMIN, multipart)  form field: file=@报销细则.txt                        → 200 DocView
POST   /api/knowledge/{id}/reindex (ADMIN)                                                       → 200 DocView
DELETE /api/knowledge/{id} (ADMIN)                                                               → 204
```

校验：`title` 非空且 ≤200；`content` 非空（否则 400，消息含 `内容不能为空`）。

### 4.5 看板与评测

```http
GET  /api/dashboard/metrics (ADMIN) →
{ "provider":"mock",
  "prompts": { "system-chat":["v1","v2"], "rag-answer":["v1"] },
  "knowledgeDocs": 1,
  "metrics": [ { "name":"ai.llm.latency", "tags":{"provider":"mock","type":"stream","success":"true"},
                 "values":{"count":4.0,"totalTime":1.2,"max":0.349} } ] }

POST /api/eval/run (ADMIN) →
{ "ranAt":"2026-10-27T14:59:30+08:00", "provider":"mock", "total":4, "passed":4,
  "results":[ { "id":"rag-hit","input":"公司年假有多少天？","expectContains":["已依据知识库片段作答"],
                "actual":"[MOCK 回答] …","passed":true } ] }

GET  /api/eval/report (ADMIN) → 最近一次 EvalReport（未跑过则为空报告）
```

### 4.6 运维

```http
GET /actuator/health              公开
GET /actuator/prometheus   (ADMIN) 含 ai_llm_calls_total / ai_llm_latency_seconds_* / ai_llm_tokens_total / ai_rag_* / ai_tool_calls_total / ai_guardrail_blocked_total / ai_degradation_total
```

---

## 5. 权限矩阵

| 接口 / 能力 | 未登录 | USER | ADMIN |
| --- | --- | --- | --- |
| `POST /api/auth/login`、`GET /actuator/health` | ✅ | ✅ | ✅ |
| `GET /api/auth/me`、`/api/chat/**` | 401 | ✅（仅本人会话） | ✅（可跨会话，运维视角） |
| `GET /api/knowledge/**` | 401 | ✅ 只读 | ✅ |
| `POST/DELETE /api/knowledge/**` | 401 | **403** | ✅ |
| `GET /api/dashboard/**` | 401 | **403** | ✅ |
| `POST/GET /api/eval/**` | 401 | **403** | ✅ |
| `GET /actuator/**`（除 health/info） | 401 | **403** | ✅ |
| 工具 `queryMyRecentOrders` / `queryOrderDetail` | — | ✅（只查本人订单） | ✅ |
| 工具 `searchKnowledgeBase` | — | **不暴露给模型** | ✅ |

三层关卡互相独立：接口级（URL 规则）、数据级（会话属主 / 订单 `userId` 过滤）、工具级（角色白名单决定暴露给模型的能力）。任何一层单独被绕过都不会导致数据越权。

---

## 6. AI 能力清单（工程化视角）

| # | 能力 | 现状 | 位置 |
| --- | --- | --- | --- |
| 1 | 模型接入抽象 | `LlmClient` 接口 + mock/OpenAI 兼容两套实现，`AI_PROVIDER` 一键切；切 Azure/Ollama 只需加一个 bean | `ai/LlmClient`、`ai/MockLlmClient`、`ai/SpringAiLlmClient`、`config/AiConfig` |
| 2 | 流式输出 | WebMVC 返回 `Flux<ChatChunk>`，SSE 逐 token；超时/降级/中断都有确定语义 | `controller/ChatController`、`ai/ChatService` |
| 3 | RAG | pgvector HNSW + 余弦，`top-k` 与相似度阈值可配（mock/真实双阈值），检索失败降级空上下文；文档按 `documentId` 精确清旧向量 | `ai/RagService`、`resources/application.yml` |
| 4 | 工具调用 | 双路径：服务端意图命中「前置调用」+ 模型原生 function calling；角色白名单；`ToolContext` 携带 `userId/admin` | `ai/tool/ToolRegistry`、`OrderQueryTool`、`KnowledgeSearchTool` |
| 5 | Prompt 版本化 | `classpath:prompts/{name}.{vN}.md`，文件名即版本；请求内 `promptVersion` 支持灰度与 A/B；`latest` 取最大版本 | `ai/PromptRegistry`、`resources/prompts/` |
| 6 | 多轮记忆 | 落业务库（可审计、可按 traceId 关联），携带最近 `ai.max-history` 条 | `ai/ChatHistoryService` |
| 7 | 输入护栏 | 空值、超长、中英文 Prompt 注入特征；被拦截不产生模型调用；按 reason 计数 | `ai/GuardrailService` |
| 8 | 输出护栏 | 预留扩展点 `checkOutput()`（未实现） | `ai/GuardrailService`、`ChatService` 收尾处 |
| 9 | 可观测 | `traceId` 四端贯穿（头/帧/日志/库）+ 8 个 `ai.*` 指标族 + 看板 + Prometheus | `observability/TraceFilter`、`AiMetrics` |
| 10 | 评测与门禁 | JSON 用例集 + `POST /api/eval/run` + 启动即评测 `--eval=true`（任一失败非零退出） | `eval/EvalService`、`EvalRunner` |
| 11 | 降级策略 | RAG 失败→空上下文；LLM 失败/超时→兜底文案帧；落库失败→不追回答案；编排前置失败→可读错误帧 | `ai/ChatService` |
| 12 | 无 Key 可演示 | mock provider 让护栏、RAG、工具、SSE、指标、评测全链路真实可跑，且结果确定 | `ai/MockLlmClient`、`config/AiConfig` |

### 当前 Prompt 一览

| 名称 | 版本 | 用途 |
| --- | --- | --- |
| `system-chat` | v1 / v2 | 系统提示词（角色设定与作答约束），对话请求可指定版本 |
| `rag-answer` | v1 | 用户侧组装模板，占位符：`{history}` `{context}` `{tools}` `{question}`，并要求「依据不足时直接说明未找到相关信息，不要编造」 |

---

## 7. 一次问答的信息流（用户视角示例）

```
输入：「帮我查一下我最近的订单，顺便说说年假几天」
  ├─ 护栏：通过
  ├─ 会话：新建/续用，读取最近 8 条历史
  ├─ RAG：命中《员工福利制度》1 段（相似度 0.18 > mock 阈值 0.12）
  ├─ 工具：命中订单意图 → 前置调用 queryMyRecentOrders（只查本人 3 笔订单）
  ├─ Prompt：system-chat@latest + rag-answer（history/context/tools/question）
  ├─ 模型：逐帧返回，页面即时增长
  └─ 收尾：user + assistant 落库（同 traceId），指标 +1
```

---

## 8. 已知限制

| 限制 | 影响 | 建议做法 |
| --- | --- | --- |
| 真实模型分支未在本地实测（无 Key） | token 口径、function calling 行为需自行验证 | 设 `AI_PROVIDER=openai` 跑同一评测集并按需标定 `ai.rag.min-score` |
| 知识文件只支持 `.txt/.md`（按 UTF-8 读文本） | PDF/DOCX 需外部转换 | 接 Tika/PdfDocumentLoader，管道不变 |
| 会话无分页、历史一次性载入 | 超长会话页面变重 | 加 `Pageable`，前端虚拟滚动 |
| 多租户缺失 | 只能单组织使用 | 加 `tenant_id` 并在 JWT、向量过滤、SQL 三处贯穿 |
| 无对话限流/配额 | 真实模型下成本不可控 | 网关或拦截器按用户/分钟限流；`ai.degradation` 观测 |
| 评测断言只有「包含关键词」 | 不能度量语义质量 | 按测试计划 §6.2 引入检索金标集 + LLM-as-judge |
| 种子数据无开关 | 生产会写入演示账号 | 部署文档 §7 检查项：`ai.seed.enabled=false` |
| 令牌存 localStorage | XSS 场景需自行评估 | 视安全要求换 HttpOnly Cookie + CSRF 防护 |

---

## 9. 二次开发速查

| 想做的事 | 改哪里 |
| --- | --- |
| 接自己的模型网关 | 只改环境变量：`AI_PROVIDER=openai` + `OPENAI_BASE_URL` + `OPENAI_CHAT_MODEL` + `OPENAI_API_KEY` |
| 加一个新工具 | `ai/tool/` 新建 `@Component` + `@Tool` 方法 → 注册进 `ToolRegistry` 的角色白名单 |
| 改提示词 | `resources/prompts/system-chat.vN.md`；加版本就加文件，请求里 `promptVersion` 灰度 |
| 换向量库（milvus/ES） | 换 starter 与配置即可，`RagService` 只依赖 `VectorStore` 接口 |
| 加输出侧护栏 | `GuardrailService#checkOutput` + 在 `ChatService` 收尾处调用，指标复用 `ai.guardrail.blocked{reason=OUTPUT}` |
| 加业务表并让 AI 查询 | Flyway 新迁移 + JPA 实体 + 一个 `@Tool`（记得在工具内做数据级过滤） |
| 加指标维度（tenant/scenario） | `observability/AiMetrics` 的 tag 上动，看板与 Prometheus 自动跟随 |
| 把评测接进 CI | 见部署文档 §11 与测试计划 §12（`--eval=true` 作为门禁步骤） |
