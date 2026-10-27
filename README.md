# Spring Boot 4 + Spring AI 2 + Vue 3 的 AI 工程化全栈模板

保留 Java / Spring Boot 的既有工程能力（接口、数据库、事务、部署），在此之上补齐 **AI 工程化** 的那一半：
模型接入、RAG、工具调用、护栏、评测、权限、可观测性。默认 **Mock Provider**，**不需要任何 API Key 就能把全链路跑通**；
配好密钥把 `AI_PROVIDER=openai` 一开，同一条链路换成真实模型，业务代码零改动。

| 层 | 选型 |
| --- | --- |
| 后端 | Spring Boot 4.1.2 · Spring AI 2.0.2 · Spring Security 7（JWT + RBAC）· Spring Data JPA · Flyway · JDK 17 |
| 数据 | PostgreSQL 16 + pgvector（业务数据与向量同一库） |
| 前端 | Vue 3.5 · Vite 8 · TypeScript · Element Plus · Pinia · vue-router |
| 观测 | Micrometer + Prometheus + Actuator，日志 MDC traceId 贯穿 |

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层与组件图、对话时序、RAG 数据流、权限三道关卡、可观测埋点、ER 图、前端结构、扩展点 |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 环境要求、本地开发、环境变量全表、Nginx SSE 配置、三种部署方式、数据库与向量索引运维、上线清单、故障排查 |
| [docs/TEST-PLAN.md](docs/TEST-PLAN.md) | 测试层次与策略、60+ 条用例（功能/权限/AI 质量/非功能/安全）、本轮实测记录与已修缺陷清单、残留风险 |
| [docs/FEATURES.md](docs/FEATURES.md) | 角色与页面功能逐项、接口请求/响应示例、权限矩阵、AI 能力清单、已知限制与二次开发速查 |

## 目录结构

```
ai-starter/
├── package.json                  # 根：只有 npm scripts，指向 tests/（零依赖）
├── docker-compose.yml            # pgvector 一键起
├── docs/                        # 架构 / 部署 / 测试计划 / 功能说明（文档只放这里）
├── tests/                       # 回归脚本（Node ESM）：smoke + 两个专项 + cases 语料
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/aistarter/
│       │   ├── ai/               # LlmClient 抽象、mock/openai、RAG、工具、护栏、对话编排
│       │   ├── config/           # AiProperties/AiConfig/SecurityConfig/WebConfig/JwtProperties
│       │   ├── controller/       # REST + SSE 接口、统一异常出口
│       │   ├── domain/ repository/  # 用户、知识文档、会话消息、示例业务表 orders
│       │   ├── eval/             # 离线评测（用例 + 启动即评测 + 手工触发）
│       │   ├── observability/    # TraceFilter、AiMetrics
│       │   └── security/         # JwtService / JwtAuthenticationFilter / AuthPrincipal
│       └── resources/
│           ├── application.yml
│           ├── prompts/          # system-chat.v1.md / v2.md / rag-answer.v1.md（文件名即版本）
│           ├── db/migration/     # Flyway SQL
│           └── eval/eval-cases.json
└── frontend/src/
    ├── api/                      # http（JWT/错误结构）、chat（SSE 逐 token）、knowledge、dashboard
    ├── views/                    # 登录 / 智能对话 / 知识库 / 运行看板 / 评测回归
    └── stores/ router/ layouts/
```

## 快速开始

```bash
# 1) 数据库（首次会拉镜像）
docker compose up -d

# 2) 后端（默认 mock provider，无需 API Key）
cd backend
mvn spring-boot:run            # http://localhost:8080

# 3) 前端
cd ../frontend
npm install
npm run dev                    # http://localhost:5173（/api 已代理到 8080）
```

演示账号（**生产必须改密**）：

| 账号 | 密码 | 角色 | 能看到 |
| --- | --- | --- | --- |
| admin | admin123 | `ROLE_ADMIN` | 对话、知识库读写、运行看板、评测 |
| user | user123 | `ROLE_USER` | 对话、知识库只读 |

登录后先试两句：`公司年假有几天？`（命中知识库 → RAG 作答）和 `帮我查一下我最近的订单状态`（触发工具读业务表）。

一行命令把主链路冒烟一遍（登录 → SSE 对话 → 护栏拦截 → RBAC → 知识库 → 看板指标 → 评测 → Prometheus 抓取）。
回归脚本用 Node ESM 写成（只依赖内置 `fetch`，零第三方包），Windows / macOS / Linux 同一条命令：

```bash
npm run smoke         # 主链路冒烟（11 步）
npm run verify:sse    # 专项：SSE 前置阶段出错必须回 error 帧（TC-CHAT-09/10）
npm run verify:rbac   # 专项：越权读他人会话 403 + 重建索引不产生重复片段（TC-AUTH-10 / TC-RAG-06）
npm test              # 三个脚本一次跑完
```

均面向已启动的后端（默认 `http://localhost:8080`，可用 `BASE_URL` 覆盖），退出码 0 即通过，
失败时末尾会列出具体步骤原因；具体用例清单见 [docs/TEST-PLAN.md](docs/TEST-PLAN.md)。

## AI 工程化能力对照表

| 能力 | 做法 | 代码位置 |
| --- | --- | --- |
| 模型可插拔 | `LlmClient` 抽象 + `ai.provider` 切换；`MockLlmClient` / `SpringAiLlmClient` | `ai/LlmClient.java`、`config/AiConfig.java` |
| Prompt 版本化 | 文件名即版本 `name.vN.md`，请求可指定 `promptVersion`（v1/v2/latest），可灰度可回滚 | `ai/PromptRegistry.java`、`resources/prompts/` |
| RAG | 切片 → 向量化 → pgvector（HNSW + 余弦）；按阈值判定命中，未命中走拒答降级；异常降级不阻断对话 | `ai/RagService.java`、`ai/KnowledgeService.java` |
| 工具调用 | `@Tool` 原生 function calling + 服务端关键词「前置调用」；**工具按角色下发**（USER 只能查自己订单，ADMIN 额外可检索知识库） | `ai/tool/`、`ChatService#prepare` |
| 智能体编排 | 护栏 → 历史 → RAG → 工具 → Prompt 渲染 → 流式生成 → 落库，一条响应式管道 | `ai/ChatService.java` |
| 输入护栏 | 空值 / 超长 / Prompt 注入特征；命中直接拒绝，不进模型，省钱且防越权 | `ai/GuardrailService.java` |
| 多轮记忆 | 会话与消息落业务库（可审计、可按 traceId 追溯），非内存 ChatMemory | `ai/ChatHistoryService.java` |
| 权限 | JWT 无状态 + RBAC（接口级 + 数据级：会话只能本人读删） | `security/`、`config/SecurityConfig.java` |
| 可观测 | `ai.*` 指标 + traceId（MDC、`X-Trace-Id` 响应头、错误响应体、落库消息） | `observability/` |
| 评测 | 关键词断言的回归集；`--eval=true` 启动即评测，未全通过直接卡住启动/CI | `eval/`、`resources/eval/eval-cases.json` |

## 接真实模型

```bash
# OpenAI 官方
set OPENAI_API_KEY=sk-xxx
set AI_PROVIDER=openai

# 或 OpenAI 兼容网关（DeepSeek / 通义 / vLLM / one-api ...）
set AI_PROVIDER=openai
set OPENAI_BASE_URL=https://api.deepseek.com/v1
set OPENAI_CHAT_MODEL=deepseek-chat
set OPENAI_EMBEDDING_MODEL=BAAI/bge-m3
set AI_EMBEDDING_DIMENSION=1024          # 必须与向量表列维度一致
```

> 换 embedding 模型或维度后，向量表需要重建：改 `AI_EMBEDDING_DIMENSION` → 删表或新建库 → 到「知识库」逐篇「重建索引」。
> 换模型不会破坏评测断言（断言只查关键词），但 **阈值要重调**：`ai.rag.min-score`（默认 0.65）语义不同源就重新标定。

主要环境变量：`AI_PROVIDER`、`OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_CHAT_MODEL`、`OPENAI_EMBEDDING_MODEL`、
`AI_EMBEDDING_DIMENSION`、`AI_MOCK_LATENCY_MS`、`PG_HOST/PG_PORT/PG_DB/PG_USER/PG_PASSWORD`、`JWT_SECRET`、`JWT_EXPIRE_MINUTES`。

## 评测与回归门禁

```bash
# 跑在流水线里：启动即评测，任一用例失败则进程非零退出
mvn spring-boot:run -Dspring-boot.run.arguments=--eval=true

# 或改完 Prompt 后手工触发（ADMIN）
curl -X POST http://localhost:8080/api/eval/run -H "Authorization: Bearer $TOKEN"
```

用例见 `backend/src/main/resources/eval/eval-cases.json`，四条覆盖了 RAG 命中、RAG 未命中降级、基础问答、护栏拦截。

## 可观测

```bash
curl http://localhost:8080/actuator/prometheus -H "Authorization: Bearer $ADMIN_TOKEN" | grep ^ai_
```

| 指标 | 含义 |
| --- | --- |
| `ai.llm.latency`（timer，tag: type/provider/status） | 模型调用耗时，首 token 与总耗时看 `type=stream` |
| `ai.llm.calls`（counter） | 调用次数与成功率 |
| `ai.llm.tokens`（counter，tag: provider/kind=prompt/completion） | token 用量，成本口径 |
| `ai.rag.latency` / `ai.rag.contexts` | 检索耗时、平均命中片段数（掉到 0 就是知识覆盖不足） |
| `ai.tool.calls`（tag: tool/success） | 工具调用量与失败率 |
| `ai.guardrail.blocked`（tag: reason） | 被拦截的输入 |
| `ai.degradation`（tag: stage=rag/llm/persist/prepare） | 降级次数，突增即告警 |

日志里的 `[%X{traceId}]` 与响应头 `X-Trace-Id`、错误响应体的 `traceId` 同源，前端「智能对话」每条回答下方也能直接看到，便于按一次请求串起日志与指标。

## 常用接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | 公开 | 换取 JWT |
| GET | `/api/auth/me` | 登录用户 | 当前身份 |
| POST | `/api/chat/stream` | 登录用户 | SSE 逐 token；body `{message, sessionId?, promptVersion?, useTools?}` |
| GET | `/api/chat/sessions`、`/api/chat/sessions/{id}/messages` | 本人 | 历史会话 |
| DELETE | `/api/chat/sessions/{id}` | 本人 | 删除会话 |
| GET | `/api/knowledge` | USER/ADMIN | 文档列表 |
| POST | `/api/knowledge`、`/api/knowledge/file`、`/api/knowledge/{id}/reindex`、DELETE `/api/knowledge/{id}` | ADMIN | 知识入库 / 重建索引 |
| POST | `/api/eval/run`、GET `/api/eval/report` | ADMIN | 评测 |
| GET | `/api/dashboard/metrics` | ADMIN | 看板数据（直读 MeterRegistry） |
| GET | `/actuator/health` | 公开 | 健康检查；其余 actuator 端点需 ADMIN |

## 二次开发扩展点

- **换文档解析**：`KnowledgeController#uploadFile` 现在只读 `.txt/.md` 文本，PDF/DOCX 分句解析接在这里，后面 `KnowledgeService.upload` 流程不用动。
- **加工具**：`ai/tool/` 加一个 `@Component`，方法标 `@Tool`，参数标 `@ToolParam`，然后注册进 `ToolRegistry` 的角色白名单即可；涉及写操作的工具请在 `GuardrailService` / 权限层再兜一次。
- **换向量库**：`RagService` 只依赖 `VectorStore` 接口，改 starter（milvus / pg-search / ES）即可，注意 `initialize-schema` 与维度参数。
- **成本与限流**：`ai.llm.tokens` 已按 provider 打点，接一层配额（用户/租户维度）在 `ChatService#prepare` 前拦截。

## 生产须知

1. `JWT_SECRET` 必须换成足够长的随机值，`demo` 账号（admin123 / user123）必须删除或改密。
2. `ddl-auto: validate` + Flyway：schema 只由迁移脚本演进，别改成 `update`。
3. 建议前端构建产物由 Nginx 托管并与后端同域，去掉 Vite 代理；SSE 需关闭反向代理缓冲（`proxy_buffering off`）。
4. 网关侧加限流与请求体上限；模型侧加超时与重试（`ai.timeout-ms`），并保留降级文案。
