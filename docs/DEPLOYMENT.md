# 部署与运维文档

> 对象：`ai-starter` 生产/测试环境部署与日常运维
> 相关：[架构说明](ARCHITECTURE.md) · [测试计划](TEST-PLAN.md) · [功能说明](FEATURES.md)

---

## 1. 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17（实测 17.0.12） | Spring Boot 4.1.2 的 `java.version` 基线；Boot 4 最低即 17 |
| Maven | 3.8+（实测 3.8.9） | 需能访问中央仓 / 镜像仓拉取 Spring AI 2.0.2 |
| Node.js | ≥ 20.19 或 ≥ 22.12（实测 22.x） | Vite 8 的 `engines` 要求 |
| npm | 10+ | — |
| PostgreSQL | 16 + pgvector | 镜像 `pgvector/pgvector:pg16` |
| Docker | 24+（实测 28.3.2，Docker Desktop） | 仅用于起数据库；生产可选全量容器化 |

版本号确认方式（勿凭记忆）：`repo1.maven.org` 上对应 artifact 的 `maven-metadata.xml`（`lastUpdated` 反映真实 GA），Node 侧 `npm view <pkg> version`。

---

## 2. 端口与依赖清单

| 组件 | 端口 | 暴露范围 | 备注 |
| --- | --- | --- | --- |
| 后端 jar | 8080 | 内网 | `server.port`，无状态可多实例 |
| 前端 dev（Vite） | 5173 | 仅本机/内网 | 生产不用，改由 Nginx 托管 dist |
| PostgreSQL | 5432 | 内网 | 生产建议不给公网 |
| 模型网关 | 443 出向 | 出网 | 需要在防火墙放行到 `OPENAI_BASE_URL` 的出向流量 |
| Prometheus 抓取 | 8080 `/actuator/prometheus` | 内网 | 需 ADMIN Basic 或反向代理收口 |

---

## 3. 本地开发（Windows PowerShell）

> PowerShell 5.1 不支持 `&&`，多命令用 `;` 分隔。

```powershell
# 1) 数据库（Docker Desktop 需已由使用者启动）
cd e:\codes\ai-starter
docker compose up -d
docker compose ps                      # STATUS 应为 healthy

# 2) 后端（默认 AI_PROVIDER=mock，无需 API Key）
cd backend
$env:JAVA_HOME='D:\myapp\Java\jdk-17'
mvn -B spring-boot:run                 # http://localhost:8080

# 3) 前端（另开终端）
cd e:\codes\ai-starter\frontend
npm install
npm run dev                            # http://localhost:5173，/api 已代理到 8080
```

启动成功的判据（日志顺序）：Flyway `Successfully applied ... now at version v1` → `Initialized JPA EntityManagerFactory` → `Loaded prompt: system-chat v1/v2` → `PgVectorStore ... Initializing PGVectorStore schema` → `Tomcat started on port 8080` → `Seeded user admin / demo knowledge doc`。

> 注意：启动日志里**不应**再出现 `Using generated security password`（说明 `AuthService` 已作为 `UserDetailsService` 生效）。

冒烟一把梭（脚本集中在仓库根目录 `tests/`）：

```powershell
cd e:\codes\ai-starter\tests ; .\smoke.ps1
```

覆盖：登录 → SSE 对话（RAG 命中）→ 工具调用 → 护栏拦截 → RBAC 403 → 知识库列表 → 会话列表 → 看板指标 → 评测 4/4 → Prometheus 抓取 `ai_*`。

另有 `verify-sse-errors.ps1`（SSE 前置阶段异常必须回 error 帧）与 `verify-rbac-rag.ps1`（数据级越权 + 向量重建幂等）两个专项脚本，同样从 `tests/` 直接执行。

---

## 4. 配置项与环境变量

配置分两层：`spring.*` / `management.*`（框架），`ai.*` / `jwt.*`（本项目）。全部可用环境变量覆盖。

| 环境变量 | 对应配置 | 默认值 | 生产建议 |
| --- | --- | --- | --- |
| `AI_PROVIDER` | `ai.provider` | `mock` | 接真实模型设为 `openai`；演示/CI 保持 `mock` |
| `AI_MOCK_LATENCY_MS` | `ai.mock.latency-ms` | 200 | 让 `ai.llm.latency` 在 mock 下也是真实量级 |
| `OPENAI_API_KEY` | `spring.ai.openai.api-key` | `sk-demo` | **必须注入**，勿入镜像/仓库 |
| `OPENAI_BASE_URL` | `spring.ai.openai.base-url` | `https://api.openai.com` | 兼容网关填根地址（如 `https://api.deepseek.com/v1`） |
| `OPENAI_CHAT_MODEL` | `spring.ai.openai.chat.options.model` | `gpt-4o-mini` | 按网关实际模型名 |
| `OPENAI_EMBEDDING_MODEL` | `spring.ai.openai.embedding.options.model` | `text-embedding-3-small` | 换模型后必须重建索引 |
| `AI_EMBEDDING_DIMENSION` | `spring.ai.vectorstore.pgvector.dimensions` + `ai.embedding.dimension` | 1536 | **必须与向量表列维度一致**，两处同源 |
| `AI_RAG_TOP_K` / 配置文件 `ai.rag.top-k` | `ai.rag.top-k` | 4 | 召回不足先加 topK，再看阈值 |
| — | `ai.rag.min-score` | 0.65 | 语义向量阈值，换模型需重新标定 |
| — | `ai.rag.mock-min-score` | 0.12 | 仅 mock provider 生效 |
| — | `ai.rag.chunk-size` | 400 | 长文档可 300–800 |
| — | `ai.timeout-ms` | 30000 | 流式整体超时，配网关 SLA |
| — | `ai.max-history` | 8 | 太大推高 token 成本 |
| — | `ai.guardrail.max-input-chars` | 4000 | 与前端输入上限、网关限制对齐 |
| `PG_HOST`/`PG_PORT`/`PG_DB`/`PG_USER`/`PG_PASSWORD` | `spring.datasource.*` | localhost/5432/ai_starter/ai_starter/ai_starter | 生产改强口令 |
| `JWT_SECRET` | `jwt.secret` | 占位串 | **必改**：≥256bit 随机值，轮换即失效全部令牌 |
| `JWT_EXPIRE_MINUTES` | `jwt.expire-minutes` | 120 | 按业务会话策略 |

向量表相关（`spring.ai.vectorstore.pgvector.*`）：`initialize-schema: true`、`index-type: HNSW`、`distance-type: COSINE_DISTANCE`、`table-name: ai_document_embedding`、`schema-name: public`。

---

## 5. 生产部署

### 5.1 构建产物

```bash
# 后端可执行 jar
cd backend && mvn -B clean package -DskipTests
# → backend/target/ai-starter-backend-1.0.0.jar

# 前端静态资源
cd frontend && npm ci && npm run build
# → frontend/dist/（含 index.html + assets）
```

`npm run build` 会先跑 `vue-tsc --noEmit` 做类型门禁；构建末尾关于 chunk > 500kB 的提示来自 Element Plus 全量引入，属可接受项（要压缩体积再改按需引入 + `manualChunks`）。

### 5.2 方案 A：后端 jar + Nginx（推荐起点）

```bash
# systemd 服务片段（/etc/systemd/system/ai-starter.service）
[Unit]
Description=ai-starter backend
After=network.target postgresql.service

[Service]
User=apistarter
WorkingDirectory=/opt/ai-starter
EnvironmentFile=/etc/ai-starter/env        # 存 KEY/SECRET，权限 600
ExecStart=/usr/bin/java -jar /opt/ai-starter/ai-starter-backend-1.0.0.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Nginx（**SSE 必须关缓冲**，否则前端看不到逐 token）：

```nginx
server {
  listen 443 ssl;
  server_name ai.example.com;

  ssl_certificate     /etc/ssl/fullchain.pem;
  ssl_certificate_key /etc/ssl/privkey.pem;

  root /var/www/ai-starter/dist;
  index index.html;

  location / {                       # SPA history 回退
    try_files $uri $uri/ /index.html;
  }

  location /api/ {                   # 反代后端，含 SSE
    proxy_pass         http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header   Connection "";
    proxy_set_header   Host              $host;
    proxy_set_header   X-Real-IP         $remote_addr;
    proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header   X-Forwarded-Proto $scheme;

    proxy_buffering    off;          # 关键：不缓冲，token 才能流式到浏览器
    proxy_cache        off;
    gzip               off;          # text/event-stream 不宜 gzip
    add_header         X-Accel-Buffering no;
    proxy_connect_timeout 5s;
    proxy_read_timeout    180s;      # 需 > ai.timeout-ms
    proxy_send_timeout    180s;
    chunked_transfer_encoding on;
  }

  # 生产不要把 actuator 暴露给公网：仅允许内网/带鉴权的抓取
  location = /internal/prometheus {
    allow 10.0.0.0/8;
    deny all;
    proxy_pass http://127.0.0.1:8080/actuator/prometheus;
  }
}
```

### 5.3 方案 B：全量 Docker Compose

`docker-compose.yml` 目前只含数据库。需要一把起全栈时，追加以下服务（`app` 镜像用 5.4 的 Dockerfile 构建）：

```yaml
  app:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: ai-starter-app
    environment:
      AI_PROVIDER: ${AI_PROVIDER:-openai}
      OPENAI_API_KEY: ${OPENAI_API_KEY:?必填}
      OPENAI_BASE_URL: ${OPENAI_BASE_URL:-https://api.openai.com}
      AI_EMBEDDING_DIMENSION: ${AI_EMBEDDING_DIMENSION:-1536}
      PG_HOST: postgres
      PG_PASSWORD: ${PG_PASSWORD:?必填}
      JWT_SECRET: ${JWT_SECRET:?必填}
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "8080:8080"
    restart: unless-stopped

  web:
    image: nginx:1.27-alpine
    volumes:
      - ./frontend/dist:/usr/share/nginx/html:ro
      - ./deploy/nginx.conf:/etc/nginx/conf.d/default.conf:ro
    depends_on: [app]
    ports:
      - "80:80"
```

### 5.4 后端镜像（`backend/Dockerfile`，多阶段）

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD ["sh","-c","curl -fsS localhost:8080/actuator/health || exit 1"]
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
```

---

## 6. 数据库与向量索引

| 事项 | 做法 |
| --- | --- |
| 建表 | Flyway 启动自动迁移（`db/migration/V1__init_schema.sql`）；`CREATE EXTENSION vector` 在同脚本首行 |
| 权限 | `CREATE EXTENSION` 需要特权账号。若生产用受限账号：先用超级用户建扩展，再把 `vector` 归入目标 schema 并授予 `USAGE` |
| 向量表 | 由 `initialize-schema=true` 自动创建，不在 Flyway 里管（升级 Spring AI 时留意其 DDL 差异） |
| schema 变更 | 实体字段变了 → 新增 `V2__xxx.sql`，保持 `ddl-auto: validate`。**禁止**改历史脚本 |
| 换 embedding 模型/维度 | ① 备份 ② 删向量表（或建新库）③ 改 `AI_EMBEDDING_DIMENSION` ④ 重启使 `initialize-schema` 建表 ⑤ 知识库逐篇「重建索引」（或脚本调 `POST /api/knowledge/{id}/reindex`）⑥ 重跑评测并重新标定 `ai.rag.min-score` |
| 备份 | `pg_dump` 同时含业务表与向量表；恢复后 `chunk_count`/`status` 与向量一致性可用「重建索引」兜底 |
| HNSW 参数 | 默认 `m=16, ef_construction=64`；数据量到百万级片段再考虑调优或换专用向量库 |

重建索引批量脚本示例：

```bash
TOKEN=$(curl -s -X POST https://ai.example.com/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"***"}' | jq -r .token)

curl -s https://ai.example.com/api/knowledge -H "Authorization: Bearer $TOKEN" \
  | jq -r '.[].id' \
  | xargs -I{} curl -s -X POST "https://ai.example.com/api/knowledge/{}/reindex" \
              -H "Authorization: Bearer $TOKEN"
```

---

## 7. 上线前检查清单

- [ ] `JWT_SECRET` 已换成长随机值（≥32 字节），且不入库不入镜像层
- [ ] `OPENAI_API_KEY` 通过 `EnvironmentFile` / secret 注入，`docker-compose` 场景用 `${VAR:?}` 强制必填
- [ ] 删除或禁用演示数据：`bootstrap/DataSeeder`（建议加 `@ConditionalOnProperty` 开关，生产 `ai.seed.enabled=false`）；至少改掉 admin/user 口令
- [ ] `PG_PASSWORD` 强口令；5432 不对公网开放
- [ ] 前端 `dist` 由 Nginx 托管并同域反代 `/api`，不用 Vite dev server
- [ ] Nginx `proxy_buffering off` 生效（否则 SSE 表现为「一次性出结果」或长时间空白）
- [ ] actuator 只暴露 `health,info,metrics,prometheus`，且 `/actuator/**` 除 health/info 外需 ADMIN
- [ ] 反向代理/网关加：请求体上限、每分钟每用户对话数限流、出站模型调用熔断
- [ ] 监控：`ai.degradation{stage=llm}` 突增、`ai.llm.calls` success=false、`ai.llm.latency` P95、`ai.rag.contexts` 均值趋 0、`ai.guardrail.blocked` 异常放大
- [ ] HTTPS + HSTS；`X-Frame-Options`/CSP 由网关统一加
- [ ] 日志接入：保留 `traceId` 字段，便于按一次问答串起「前端 → 日志 → 指标 → 库内消息」

---

## 8. 健康检查、探活与容量

| 用途 | 地址 | 说明 |
| --- | --- | --- |
| 探活 | `GET /actuator/health` | 公开；含 db/向量库连接状态（`show-details: when_authorized`） |
| 指标 | `GET /actuator/metrics`、`/actuator/prometheus` | 需 ADMIN |
| 业务自检 | `POST /api/eval/run` → `passed == total` | 比 health 更能反映 AI 链路是否退化 |
| 前端存活 | `GET /`（Nginx 静态） | — |

容量经验值（`AI_PROVIDER=openai` 时按你的网关 SLA 重估）：单实例 Tomcat 默认 200 工作线程；SSE 属长连接，**并发对话数受异步请求数与网关连接数限制**，建议 `server.tomcat.threads.max` 与 `spring.mvc.async.request-timeout` 一起压测后再定，水平扩容优先。数据库连接池 Hikari 默认 10，长对话多时先调 `spring.datasource.hikari.maximum-pool-size`。

---

## 9. 升级与回滚

| 变更类型 | 升级 | 回滚 |
| --- | --- | --- |
| Prompt 模板 | 新增 `system-chat.v3.md`，请求带 `promptVersion=v3` 灰度；观察 `ai.*` 与评测后把默认版本切过去 | 把请求参数改回 `v2`/`latest` 即回滚（无需发版）；模板本身在 git 里可追溯 |
| 模型 | 改 `OPENAI_CHAT_MODEL` 重启（配置级） | 同上改回；成本敏感可同时降低 `ai.max-history` / `top-k` |
| 检索参数 | 改 `ai.rag.top-k` / `min-score` 重启 | 改回；`min-score` 变更必须重跑评测（rag-hit / rag-miss 两条直接受阈值影响） |
| 应用版本 | jar / 镜像 tag 递增，滚动替换 | 切回上一 tag；Flyway **只向前**，回滚版本要确认新迁移是向后兼容的（只加列不删列） |
| schema | 新增 `Vn__*.sql`，启动自动执行 | 不支持自动 down：先备份，或写成向后兼容变更（加列/加索引） |
| 向量维度 | 见 §6「换 embedding 模型/维度」 | 同流程反向，成本最高，变更前列入评审 |

---

## 10. 故障排查

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| 登录返回 415 | 请求缺 `Content-Type: application/json`（fetch 发字符串体默认 `text/plain`） | 前端已修；自己调接口时补头 |
| 前端对话一直转圈 / 第一条能出、第二条发不动 | SSE 只等连接关闭而没认 `done` 帧；或反代缓冲 | 客户端收到 `done` 即收束（已实现）；Nginx `proxy_buffering off` |
| 日志刷 `AuthorizationDeniedException` + `Cannot render error page ... response has already been committed`，连接被硬切断 | Flux 完成时的 ASYNC dispatch 重入安全链，`OncePerRequestFilter` 不在 ASYNC 上跑导致上下文为空 | `SecurityConfig` 放行 `DispatcherType.ASYNC/ERROR`（已实现，首次 REQUEST 已鉴权） |
| 启动报 `wrong column type ... found [text], but expecting [oid]` | Hibernate 7 把 `@Lob String` 映射成 PG large object | 正文字段去掉 `@Lob`（已修）；若你自己加实体，同理 |
| 启动报 `required a single bean, but 2 were found: mockEmbeddingModel, openAiEmbeddingModel` | OpenAI 自动配置的 `@ConditionalOnMissingBean` 按具体类型推导，不会因为自定义 `EmbeddingModel` 而退让 | mock 实现加 `@Primary`（已修） |
| 回答里「知识库命中：0 段」但文档确实存在 | 阈值高于相似度（mock 字面量 or 换了 embedding 模型未标定）；或问题与文档用词差异大 | 看 `ai.rag.contexts`；调 `min-score` / `top-k`，或把文档写成条目化含关键词 |
| 换模型后检索全空 | 向量表维度与新模型不符 | 按 §6 重建向量表与索引 |
| `CREATE EXTENSION vector` 失败 | 账号无权限 / 镜像不带 pgvector | 用 `pgvector/pgvector:pg16`；或预先由特权账号建扩展 |
| 中文在日志/响应里乱码 | 终端编码，非服务端问题 | PowerShell：`[Console]::OutputEncoding=[Text.Encoding]::UTF8`；接口本身 UTF-8 |
| 评测未通过但启动失败于 CI | `--eval=true` 是门禁设计 | 修 Prompt/阈值/知识库，别关掉门禁 |

---

## 11. 建议 CI 流水线

```yaml
# 伪 YAML：按自家 CI 语法落地
stages: [build, test, eval, package, deploy]

backend_build:  script: [cd backend, mvn -B clean package -DskipTests]
frontend_build: script: [cd frontend, npm ci, npm run build]     # vue-tsc 即类型门禁
eval_gate:                                                   # AI 回归门禁
  services: [postgres:pgvector]
  variables: { AI_PROVIDER: mock, PG_HOST: postgres, PG_PASSWORD: ai_starter }
  script:
    - cd backend
    - mvn -B spring-boot:run -Dspring-boot.run.arguments=--eval=true   # 任一用例失败即非零退出
```

`eval_gate` 用 mock provider 跑，结果完全确定、零成本、秒级；真实模型的质量评测另跑定时任务（见测试计划 §6）。
