# 架构设计说明

> 对象：`ai-starter`（Spring Boot 4.1.2 + Spring AI 2.0.2 + Vue 3.5 + PostgreSQL/pgvector 16）
> 读者：接手二次开发的后端 / 全栈工程师
> 配套文档：[功能说明](FEATURES.md) · [部署文档](DEPLOYMENT.md) · [测试计划](TEST-PLAN.md)

---

## 1. 设计目标与约束

| 目标 | 落地方式 |
| --- | --- |
| 复用既有 Java 工程能力 | 纯 Java 单体：接口分层、JPA、Flyway、事务、Actuator 全是熟悉的东西，不引入 Python  sidecar |
| AI 能力可插拔 | 业务层只依赖 `LlmClient` 接口，`ai.provider` 一键切 mock / OpenAI 兼容网关 |
| 无 API Key 也能跑通全链路 | `MockLlmClient` + `MockEmbeddingModel`：护栏、RAG、工具、SSE、落库、指标、评测全部照常走 |
| 每个环节可观测、可降级 | 编排管道分段打点 `ai.*`；RAG 失败降级空上下文、LLM 失败返回兜底文案、落库失败不影响已推送答案 |
| 业务数据与向量同库 | PostgreSQL + pgvector，减少一致性问题的面 |
| 权限是硬约束 | JWT + RBAC，接口级 + 数据级（会话属主）+ 工具级（角色白名单）三层 |

**已知取舍**

- 单体而非「Java 网关 + Python AI 服务」：牺牲语言生态丰富度，换来部署链路、鉴权与事务的一致性。需要接 Python/Node 的算法服务时，把它做成一个 `@Tool` 工具即可，管道不变。
- 多轮记忆落业务库而非 Spring AI `ChatMemory`：换来可审计、可按 `traceId` 串联、进程重启不丢。代价是要自己控制携带条数（`ai.max-history`）。
- Prompt 存 classpath 文件（文件名即版本）而非数据库：换来 git 可审、发布即回滚。灰度需求（按流量比例切版本）需要另建 `prompt_version` 表 + 分配策略。

---

## 2. 总体分层

```mermaid
flowchart TB
    subgraph Client["前端 SPA（Vue 3.5 + Vite 8 + Element Plus）"]
        V["views：登录 / 智能对话 / 知识库 / 运行看板 / 评测回归"]
        S["stores/auth（Pinia）：JWT 与角色"]
        R["router：全局守卫（public / admin）"]
        API["api 层：http.ts（JWT·错误结构）· chat.ts（SSE 逐 token）"]
        V --> S
        V --> API
        R --> S
    end

    subgraph Edge["接入层"]
        NGINX["开发：Vite proxy `/api`→8080<br/>生产：Nginx（需关 SSE 缓冲）"]
    end

    subgraph App["后端单体（Spring Boot 4.1.2，:8080）"]
        direction TB
        subgraph Sec["安全"]
            JWT["JwtAuthenticationFilter"]
            SC["SecurityConfig：无状态 + RBAC"]
        end
        CTRL["controller：Auth / Chat(SSE) / Knowledge / Dashboard / Eval / 统一异常"]
        subgraph AICore["ai 包（工程化主战场）"]
            CHATSVC["ChatService 编排管道"]
            GUARD["GuardrailService 输入护栏"]
            HIST["ChatHistoryService 多轮记忆"]
            RAG["RagService 检索/入库"]
            TOOLREG["ToolRegistry 工具白名单 + 前置调用"]
            PROMPT["PromptRegistry 版本化模板"]
            LLM{{"LlmClient 接口"}}
        end
        OBS["observability：TraceFilter（MDC traceId）+ AiMetrics"]
        EVAL["eval：EvalService / EvalRunner（--eval=true 门禁）"]
        REPO["repository（Spring Data JPA）"]
        DOM["domain：AppUser / KnowledgeDoc / ChatSession / ChatMessage / CustomerOrder"]
    end

    subgraph Data["数据层 PostgreSQL 16 + pgvector"]
        PG[("业务表<br/>app_user · knowledge_doc<br/>chat_session · chat_message · orders")]
        VEC[("向量表 ai_document_embedding<br/>HNSW + COSINE")]
    end

    subgraph Ext["外部能力（可选）"]
        PROV["OpenAI 兼容模型网关<br/>chat + embedding"]
    end

    Client --> Edge --> Sec --> CTRL --> AICore --> REPO --> DOM --> Data
    LLM -. "provider=openai" .-> PROV
    OBS -.埋点.- AICore
    EVAL --> CHATSVC
```

**依赖方向**：`controller → ai → repository → domain`。`ai` 不依赖任何 Web 类型（除 SSE 用的 `Flux`）；`LlmClient` 是唯一的模型抽象边界，切 Provider / 加路由（多模型按任务分派）都只改 `config/AiConfig`。

---

## 3. 一次对话请求的完整时序

```mermaid
sequenceDiagram
    autonumber
    participant U as 浏览器
    participant C as ChatController
    participant S as ChatService
    participant G as GuardrailService
    participant H as ChatHistoryService
    participant RG as RagService
    participant T as ToolRegistry
    participant PR as PromptRegistry
    participant L as LlmClient(mock|openai)
    participant DB as PostgreSQL

    U->>C: POST /api/chat/stream（Bearer JWT）
    C->>C: TraceFilter 已备好 traceId（MDC + X-Trace-Id）
    C->>S: streamChat(request, principal, traceId)
    S->>G: checkInput(message)
    alt 命中空值/超长/注入特征
        G-->>U: data:{error:true, "输入被安全护栏拦截…"}（不进模型）
    end
    S->>H: openSession 或 requireOwned(属主校验)
    H->>DB: chat_session / chat_message
    S->>H: renderHistory(maxHistory)
    S->>RG: retrieve(question)
    RG->>DB: similaritySearch(topK + 阈值 + document 元数据)
    RG-->>S: 命中片段（异常→空列表 + ai.degradation{stage=rag}）
    S->>T: preInvoke(question, principal)（订单意图命中才打库）
    T->>DB: orders where user_id = 当前用户
    S->>PR: get(system-chat@版本) + render(rag-answer)
    S->>L: stream(LlmRequest{system,user,tools,toolContext,traceId})
    loop 每个 token / 分片
        L-->>U: SSE data: ChatChunk{content, traceId, sessionId, provider}
    end
    L-->>U: SSE data: ChatChunk{done:true}
    S->>DB: persist(user 问题 + assistant 全文, traceId)
    Note over S,DB: 落库失败只记 ai.degradation{stage=persist}，不追回已推送答案
```

**关键点**

1. 护栏在最前面：被拦截的输入不产生任何模型调用与 token 成本。
2. `Flux.defer(...)`：订阅时才执行编排，保证异常能走响应式 `onErrorResume`，SSE 语义不被破坏。
3. 工具两层：服务端「关键词前置调用」保证链路稳定（Mock 也能演示），`@Tool` 原生 function calling 让真实模型自主补充。
4. `done` 帧是前端的结束信号——不必等连接关闭（Vite/keep-alive 下连接可能延迟断开）。
5. 错误不毁流：护栏命中、会话不存在、越权访问、模型超时均回一帧 `error:true` 的可读错误，HTTP 仍为 200（`streamChat` 外层两级 `onErrorResume`）。

---

## 4. RAG 数据流

```mermaid
flowchart LR
    subgraph 入库
        A["知识文本 / .txt .md 上传"] --> B["KnowledgeDoc 落库<br/>status=INDEXED"]
        B --> C["TokenTextSplitter<br/>chunk-size=400"]
        C --> D["EmbeddingModel<br/>mock: 本地二元组哈希<br/>openai: 远端模型"]
        D --> E[("ai_document_embedding<br/>+ metadata: documentId/title/source")]
    end
    subgraph 检索
        Q["用户问题"] --> QE["同一 EmbeddingModel"]
        QE --> VS["VectorStore.similaritySearch<br/>topK=4, threshold=阈值"]
        VS --> TH{"score ≥ 阈值?"}
        TH -- 是 --> CTX["注入 rag-answer 模板<br/>[1] 来源/相似度/正文"]
        TH -- 否 --> MIS["（无命中片段）→ 提示词要求明确拒答"]
    end
    E -.-> VS
```

| 设计点 | 说明 |
| --- | --- |
| 双阈值 | `ai.rag.min-score=0.65`（语义向量）/ `ai.rag.mock-min-score=0.12`（Mock 的字面重合度量级低）。`RagService#threshold()` 按 `llmClient.provider()` 选择 |
| 重新入库 = 先删后写 | `deleteByDocumentId` 用 `Filter.Expression` 按 `documentId` 清旧向量，避免同文档重复片段污染召回 |
| 检索异常不阻断 | `catch` → 空上下文 + `ai.degradation{stage=rag}`，答案走「未找到相关信息」，宁可拒答不可编造 |
| 换 embedding 模型 | 向量维度与来源分布都变，必须重建向量表并「重建索引」（见部署文档 §6） |

---

## 5. 工具调用与权限

```mermaid
flowchart TB
    P["AuthPrincipal(userId, username, role)"] --> D{"useTools 开启?"}
    D -- 否 --> NONE["本轮不暴露工具、不前置调用"]
    D -- 是 --> ROLE{"role"}
    ROLE -- ROLE_USER --> T1["queryMyRecentOrders<br/>queryOrderDetail"]
    ROLE -- ROLE_ADMIN --> T2["+ searchKnowledgeBase"]
    T1 --> CTXC["ToolContext: userId / admin"]
    T2 --> CTXC
    CTXC --> IMPL["工具实现内再做数据级过滤<br/>USER 只能查自己 userId 的订单"]
```

三条独立的权限关卡（缺一层都可能被绕过）：

| 关卡 | 位置 | 拦截效果 |
| --- | --- | --- |
| 接口级 | `SecurityConfig`（`hasRole/hasAnyRole` + `dispatcherTypeMatchers(ASYNC, ERROR).permitAll()`） | USER 调知识库写接口 / 评测 / 看板 → 403 |
| 数据级 | `ChatHistoryService#requireOwned`、`OrderQueryTool` 内 `userId` 过滤 | 换 sessionId 读别人会话 → 越权异常 |
| 工具级 | `ToolRegistry#toolsFor/toolNames` 按角色白名单 | 模型侧拿不到超出角色的能力 |

意图路由：`ORDER_INTENT = (订单|我的.{0,4}单|order|发货|退款|物流|花了多少|金额)`，命中才 `preInvoke`，避免每轮无谓打库。

---

## 6. 可观测架构

```mermaid
flowchart LR
    REQ["请求进入"] --> TF["TraceFilter<br/>复用/生成 12 位 traceId → MDC"]
    TF --> MDC["日志 pattern [%X{traceId}]"]
    TF --> HDR["响应头 X-Trace-Id"]
    TF --> ERR["错误响应体 traceId"]
    TF --> MSG[("chat_message.trace_id")]
    subgraph Metrics["MeterRegistry"]
        AI["ai.* 自定义指标"]
        JV["JVM / HTTP / Hikari 内置指标"]
    end
    AI --> P1["/actuator/prometheus（文本）"]
    AI --> P2["/api/dashboard/metrics（看板直读）"]
```

| 指标 | 类型 | tag | 用途 / 告警建议 |
| --- | --- | --- | --- |
| `ai.llm.latency` | Timer | type(complete/stream)、provider、success | 模型耗时；stream 的总量≈尾包时间，`max` 看长尾 |
| `ai.llm.calls` | Counter | type、provider、success | 成功率 = success:true / 总数，掉到 99% 以下告警 |
| `ai.llm.tokens` | Counter | provider、kind(prompt/completion) | 成本口径（Mock 为估算值，不做计费依据） |
| `ai.rag.latency` | Timer | — | 检索耗时 |
| `ai.rag.contexts` | Summary | — | 平均/最大命中片段数；均值趋 0 = 知识覆盖不足或阈值过高 |
| `ai.tool.calls` | Counter | tool、success | 工具用量与失败率 |
| `ai.guardrail.blocked` | Counter | reason(EMPTY/TOO_LONG/INJECTION) | 攻击与异常输入观测 |
| `ai.degradation` | Counter | stage(rag/llm/persist/prepare) | 任一 stage 突增即降级发生，优先看 llm；prepare 集中在会话不存在 / 越权 |

注册名与实现见 `observability/AiMetrics.java`，`application` tag 由 `management.metrics.tags` 统一附加。

---

## 7. 数据模型

```mermaid
erDiagram
    app_user ||--o{ chat_session : "拥有"
    app_user ||--o{ knowledge_doc : "创建"
    app_user ||--o{ orders : "下单"
    chat_session ||--o{ chat_message : "包含(级联删除)"
    knowledge_doc ||--o{ ai_document_embedding : "切片向量(按 documentId 关联)"

    app_user {
        bigint id PK
        varchar username UK
        varchar password_hash "BCrypt"
        varchar role "ROLE_USER / ROLE_ADMIN"
        boolean enabled
    }
    knowledge_doc {
        bigint id PK
        varchar title
        varchar source
        text content "原文，可重建索引"
        int chunk_count
        varchar status "PENDING / INDEXED / FAILED"
    }
    chat_session {
        bigint id PK
        bigint user_id FK
        varchar title
        varchar prompt_key "system-chat"
        varchar prompt_ver "v1 / v2 / latest 解析结果"
    }
    chat_message {
        bigint id PK
        bigint session_id FK
        varchar role "user / assistant"
        text content
        varchar trace_id "与日志/指标同源"
    }
    orders {
        bigint id PK
        varchar order_no UK
        bigint user_id FK
        varchar product
        numeric amount
        varchar status
    }
    ai_document_embedding {
        uuid id PK
        vector embedding "维度=AI_EMBEDDING_DIMENSION"
        text content
        jsonb metadata "documentId/title/source"
    }
```

- 业务表由 **Flyway** `V1__init_schema.sql` 建（`CREATE EXTENSION vector` 在最前）；向量表由 `spring.ai.vectorstore.pgvector.initialize-schema=true` 自动建。
- JPA 是 `ddl-auto: validate`：**任何实体字段变更都要配一个新的迁移脚本**，别改成 `update`。
- `@Lob` 在 Hibernate 7 + PG 下会映射成 large object（`oid`）与 `TEXT` 列冲突 → 正文字段一律纯 `@Column`。

---

## 8. 前端架构

```mermaid
flowchart TB
    M["main.ts：挂载 + 全局注册 Element Plus 图标"] --> RT["router（401/权限守卫）"]
    RT --> L["layouts/MainLayout（菜单按角色过滤）"]
    L --> CV["ChatView（SSE·会话列表·promptVersion·useTools）"]
    L --> KV["KnowledgeView（列表·录入·上传·重建索引·删除）"]
    L --> DV["DashboardView（指标·5s 自动刷新）"]
    L --> EV["EvalView（跑评测集·最近报告）"]
    L --> LV["LoginView（public）"]
    CV --> CS["stores/auth（Pinia）"]
    KV --> API1["api/*"]
    DV --> API1
    EV --> API1
    CS --> API1
    API1 --> H["api/http.ts：注入 Bearer·统一错误结构·401 回调→登出跳登录"]
    API1 --> S["api/chat.ts：fetch + ReadableStream 解析 data: 帧，done 即收束"]
```

- **令牌存储**：`localStorage`（`ai-starter-token` / `ai-starter-user`），刷新续期；真正的鉴权在后端，前端路由守卫只是体验层。
- **SSE 不用 EventSource**：对话需要 POST body + `Authorization` 头，故用 `fetch` + `ReadableStream` 手工按 `\n\n` 分帧。
- **401 单向解耦**：`http.ts` 只暴露 `setUnauthorizedHandler`，由 `router/index.ts` 注册「清身份 + 跳登录并带 redirect」。

---

## 9. 部署拓扑

```mermaid
flowchart LR
    B["浏览器"] --> N["Nginx<br/>静态 dist + /api 反代<br/>proxy_buffering off"]
    N --> J["后端 jar :8080<br/>（可多实例，无会话状态）"]
    J --> PG[("PostgreSQL + pgvector :5432<br/>pg-data 卷")]
    J --> GW["模型网关（OpenAI 兼容）"]
    J --> PROM["Prometheus 抓取 /actuator/prometheus"]
    PROM --> G["Grafana 趋势图"]
```

无状态：JWT + 会话落库 ⇒ 后端可水平扩容，不需要会话粘滞；只需注意**并发实例的向量重建索引别同时跑**（按 `documentId` 删写，非幂等）。

---

## 10. 包与扩展点速查

| 包 | 职责 | 常见改法 |
| --- | --- | --- |
| `config/AiConfig` | Provider 装配（mock/openai + mock Embedding `@Primary`） | 加 `provider=azure/ollama` 分支：新增一个 `LlmClient` bean + 一个 `@ConditionalOnProperty` |
| `ai/ChatService` | 编排管道 | 加配额/限流在 `prepare` 前；加输出侧护栏在 `persist` 前后（`GuardrailService` 预留 `checkOutput`） |
| `ai/PromptRegistry` | `classpath:prompts/name.vN.md` | 新增版本即加文件，不改代码；DB 化需替换本类 |
| `ai/RagService` | 只依赖 `VectorStore` | 换 milvus/ES：换 starter + 配置，代码不变 |
| `ai/tool/*` | `@Tool`/`@ToolParam` | 加工具：`@Component` + 注册进 `ToolRegistry` 角色白名单 |
| `observability` | TraceFilter + AiMetrics | 加维度（tenant/scenario）在 `AiMetrics` 的 tag 上动 |
| `eval` | 回归集与门禁 | 加用例即改 `eval-cases.json`；接入 CI 见测试计划 §7 |
| `bootstrap/DataSeeder` | 演示账号/订单/知识文档 | 生产用环境变量开关（建议 `ai.seed.enabled=false`） |
