# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SuperBizAgent is a Spring Boot (Java 17) system with two AI capabilities — both backed by Alibaba Cloud DashScope (Qwen) via Spring AI Alibaba — plus one non-AI slice that builds on their output:

1. **RAG Q&A** — upload documents → chunk → embed → store in Milvus → retrieve + generate answers.
2. **AIOps** — a multi-agent pipeline that analyzes Prometheus alerts and produces a structured Markdown diagnostic report.
3. **告警认领（claim）责任闭环**（非 AI，V1 新增）— 值班 SRE 认领 AIOps 已诊断的告警：负责人立即可见、防重复接管（先到先得）、MySQL 持久化。详见下 Architecture 与 `specs/001-alert-claim/`。

## 学习模式（我是来学的，不只是要结果）

- 我在用这个项目学 Claude 六件套、SDD（spec-kit）和 Harness 架构；交付只是手段，学会是目的。
- 关键决策先解释"为什么"再动手，用通俗语言；新概念先一句话讲清。
- 不要一口气做完整个模块：每完成一个相对完整的子步骤就停下来，等我确认。
- 优先引导我自己做（先给思路），而不是直接代劳全部。

## Git 提交规范（强制）

- 每完成一个相对完整的模块，必须提交一次 GitHub，禁止攒多个模块一次性提交。
- 提交前跑 `mvn test`，`git status` + `git diff` 自查，确认只含本模块改动。
- 提交信息用 Conventional Commits：`feat:` / `fix:` / `test:` / `refactor:`。
- 在已推送分支上提交后执行 `git push`；`feat/*` 分支提交完即可，合并交给 PR。

## 测试约定（强制）

- 测试放标准 Maven 布局 `src/test/java/org/example/...`，类名 `XxxTest`；用 `mvn test` 运行，提交门是 `mvn verify`。
- 无测试 = 缺陷：新增/改动的代码必须携带可运行测试。
- 测试**不得依赖实连**的 DashScope / Prometheus / Milvus：优先 `@WebMvcTest` / `@DataJpaTest` / Mockito / H2（测试替身）切片；确需实连的冒烟/集成场景显式标 `IT` 或用 `specs/<feature>/quickstart.md` 手动验证。

## Build & Run

Requires `DASHSCOPE_API_KEY` in the environment and a running Milvus instance (default `localhost:19530`). 告警认领（claim）模块另需 **MySQL 8**（默认 `localhost:3306/superbiz`，账号走 `MYSQL_USER`/`MYSQL_PASSWORD` env）：`docker run --name sba-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=superbiz -e MYSQL_USER=sba -e MYSQL_PASSWORD=sba -p 3306:3306 -d mysql:8`（详见 `specs/001-alert-claim/quickstart.md`）。

```bash
# Start Milvus (etcd + minio + standalone + attu) via Docker Compose
docker compose -f vector-database.yml up -d

# Build / run the Spring Boot app (port 9900)
mvn clean install
mvn spring-boot:run
```

The `Makefile` wraps the full lifecycle — `make init` is the one-shot path (start Docker → start app → wait → upload `aiops-docs/*.md` into Milvus). Other targets: `make up/down/start/stop/restart/check/upload/clean`. The Makefile assumes Unix shell utilities (`curl`, `nohup`, `docker-compose`) — it will not work verbatim on a Windows shell.

Tests live under `src/test/java/org/example/` (claim 模块现有 8 个类：repository / service / controller / recorder / 并发 / 2× tool；US2 抑制窗口覆盖在 AlertClaimServiceTest / AlertSuppressionRepositoryTest / AlertClaimControllerTest / AlertDiagnosisRecorderTest 内；claim 写工具覆盖在 ClaimAlertToolTest / SuppressAlertToolTest)。跑 `mvn test` 即可 —— claim 套件跑在内存 H2(`MODE=MySQL`) 替身上，**无需 Docker / MySQL / DashScope**；`mvn verify` 是提交门。真 MySQL 的跨进程重启与并发权威复验是手动项，见 `specs/001-alert-claim/quickstart.md` §2（Step E/F）。

### Key HTTP endpoints

- `POST /api/chat` — non-streaming chat (ReactAgent with tool calling)
- `POST /api/chat_stream` — SSE streaming chat
- `POST /api/ai_ops` — trigger the multi-agent alert analysis (SSE)
- `POST /api/upload` — upload a `.txt`/`.md` file, auto-chunk + embed + index
- `GET /milvus/health` — Milvus health check
- 告警认领（claim）模块：`POST /api/alerts/{alertName}/claim`（认领）、`GET /api/alerts`（列表，可按 `?status=` 过滤）、`GET /api/alerts/{alertName}`（负责人可见）、`GET /api/alerts/{alertName}/events`（时间线）。⚠️ 认领对象必须是已跑过 `/api/ai_ops` 的告警（`ChatController.aiOps` 前置 recorder 打 DIAGNOSED）；对未诊断告警认领返回 40401。US2 抑制窗口：`POST /api/alerts/{alertName}/suppress` body `{operator, until}`（设置）、`DELETE /api/alerts/{alertName}/suppress?operator=`（取消；幂等）
- `POST /api/chat/clear`, `GET /api/chat/session/{id}` — session management

## Architecture

### RAG pipeline

`FileUploadController` → `VectorIndexService.indexSingleFile` → `DocumentChunkService` (splits on Markdown headings then paragraph boundaries, 800-char chunks with 100-char overlap) → `VectorEmbeddingService` (DashScope `text-embedding-v4`) → Milvus insert.

Retrieval: `VectorSearchService.searchSimilarDocuments` embeds the query and runs an L2-distance search against collection `biz` (top-K default 3). Both `RagService` (plain Qwen completion) and `InternalDocsTools` (an agent tool) consume this same retrieval path.

### AIOps multi-agent graph (the distinctive part)

`AiOpsService` builds a `SupervisorAgent` (from `spring-ai-alibaba-agent-framework`) that orchestrates two `ReactAgent`s:

- **planner_agent** — decomposes the alert, plans steps, and also acts as the *Replanner* (reads `executor_feedback` and re-plans). On `decision=FINISH` it must emit the final report as pure Markdown (not JSON), following a fixed template embedded in its system prompt.
- **executor_agent** — executes only the *first* step the planner proposed, collects evidence, writes JSON back to `executor_feedback`.

The supervisor loops planner→executor until `FINISH`. `ChatController.aiOps()` extracts the final report from the graph state key `planner_plan` and streams it over SSE in 50-char chunks.

### Agent tools (`@Tool` methods, in `agent/tool/`)

- `DateTimeTools` — current time.
- `InternalDocsTools` — RAG search over internal docs.
- `QueryMetricsTools` — queries Prometheus `/api/v1/alerts`; has a `prometheus.mock-enabled` flag that returns canned alerts (HighCPUUsage / HighMemoryUsage / SlowResponse).
- `QueryLogsTools` — queries Tencent CLS; only implements a **mock** mode (`cls.mock-enabled`). The real CLS path is a stub that returns "尚未实现". Real log querying is intended to come from an MCP server, not this tool.
- `ClaimAlertTool` — 认领一条已诊断告警（WRITE，薄适配 `AlertClaimService.claim`）。**位置例外**：不在 `agent/tool/`，放 `org.example.claim.tool`（与 claim 有界模块自洽，避免造出 `agent.tool ↔ claim.service` 双向包环）。默认不注册（`agent.claim-tool-enabled` 缺省 false）；operator 由 `agent.claim-operator` 部署绑定、**签名不收 operator**（模型无身份表达通道，D4）。**只接聊天 agent**，不接 AiOpsService（认领=人工接管，AIOps 不得无人值守自动认领）。设计见 `specs/001-alert-claim/contracts/agent-tool.md`。
- `SuppressAlertTool` — 抑制窗口姊妹工具（WRITE，薄适配 `AlertClaimService.suppress/cancelSuppression`），同一 bean 内两个 @Tool：`suppressAlert(alertName, until)` 设窗、`cancelSuppression(alertName)` 取消（幂等）。位置/注册/身份与 `ClaimAlertTool` **完全相同**（同 `agent.claim-tool-enabled` 闸 + 同 `agent.claim-operator`，同只接聊天 agent）。独有新面：`until` 翻译层——模型不心算当前时刻，接受 ISO-8601 时刻或相对时长（`2h`/`90m`/`1d`，自 now 起算）两种写法，解析失败转文案不抛。

### Tool registration model

Tools are wired two ways into each `ReactAgent`:
- `.methodTools(...)` — passes tool bean instances so the framework generates tool JSON from `@Tool`/`@ToolParam` annotations.
- `.tools(toolCallbacks)` — passes `ToolCallback[]` from `ToolCallbackProvider` (auto-scanned `@Tool` methods **and** any MCP tools).

`QueryLogsTools` is injected `@Autowired(required = false)` — but note its `@ConditionalOnProperty` is **only a comment on the class**, so the bean is always present and always exposed to agents; the `cls.mock-enabled` flag (via `@Value`) only switches its internals between mock and real. In "real" mode `ChatService.buildMethodToolsArray` / `AiOpsService.buildMethodToolsArray` omit it from the explicit array, on the assumption the MCP client supplies log tools.

`ClaimAlertTool` 与 `SuppressAlertTool`（claim 认领/抑制写工具）is the **corrected version of that lesson**: both carry a real `@ConditionalOnProperty(name="agent.claim-tool-enabled", havingValue="true")`（缺省关）——禁用时 bean 不存在，`ToolCallbackProvider` 的自动扫描（见上 `.tools` 一行）就不会把写工具泄漏给任何 agent。它们 `@Autowired(required=false)` 只注入 **ChatService**（`buildMethodToolsArray` / `buildSystemPrompt`）；AiOpsService 有意不接。

### Milvus schema

`MilvusClientFactory.createClient` lazily creates collection `biz` on first connect with fields: `id` (VarChar PK), `vector` (FloatVector), `content` (VarChar), `metadata` (JSON). Vector index is `IVF_FLAT` with `L2` metric.

### 告警认领（claim）责任闭环（V1 新增，独立于 RAG/AIOps）

给「诊断完即结束」补上责任闭环，包 `org.example.claim.*`，走 **MySQL 8** —— 这是本项目首个持久化模块（测试用 H2 `MODE=MySQL` 替身）。表：`alerts`（PK `alert_name`，状态在行内）+ `claim_events`（只追加，审计/时间线）。

- **状态机**：`DIAGNOSED → IN_PROGRESS` 是 V1 唯一合法迁移；RESOLVED/CLOSED 为 P3 预留。**「一条告警至多一个有效认领」由单行状态 + 条件 UPDATE（CAS）保证**，不引入部分唯一索引。
- **认领** `AlertClaimService.claim`：`@Transactional` 内调 `AlertRepository.claimIfDiagnosed`（`UPDATE alerts SET … WHERE status='DIAGNOSED'`）判受影响行数；返回 1 → 追加 CLAIM 事件并回读视图；返回 0 → re-read 映射 40401 / 40901（携 owner）/ 40902 / 40903。
- **诊断前置（FR-006 守卫）**：`ChatController.aiOps()` 在跑 supervisor **之前**调 `AlertDiagnosisRecorder.recordCurrentAlerts()` —— 读 `QueryMetricsTools` 的 mock feed，幂等 upsert 成 DIAGNOSED，**绝不降级**已 IN_PROGRESS 的行。认领对象必须是打过 DIAGNOSED 的告警（否则 40401）。
- **抑制窗口（US2，P2）**：`alerts.suppressed_until` 单列承载一个活动窗口（行内状态哲学延续）；`AlertClaimService.suppress/cancelSuppression` 调 `AlertRepository.setSuppression`（CAS 谓词：`status='IN_PROGRESS' AND claimed_by=:operator AND suppressed_until IS DISTINCT FROM :until`）→ 写 SUPPRESS / SUPPRESS_CANCEL 事件。守卫：until 需晚于当前（40004）、未认领（40904）、非负责人（40905）、已结束（40903）。**惰性失效**：活动性一律 `until > now` 判定，无调度器；抑制期内 recorder **跳过诊断记录**（不刷新 lastDiagnosedAt），到点自动恢复。
- 契约/设计/验证见 `specs/001-alert-claim/`（spec / plan / contracts / quickstart / checklists）。

## Gotchas

- **API key**: injected from `DASHSCOPE_API_KEY` env var (default `your-api-key-here`). `VectorEmbeddingService` fails fast at startup if it's not set.
- **Vector dimension mismatch risk**: `MilvusConstants.VECTOR_DIM = 1024`, but the comment says "豆包" (Doubao) model while the actual embedding model in `application.yml` is DashScope `text-embedding-v4`. If you switch embedding models, the collection dimension must be recreated to match — Milvus collections are immutable once created, so a dimension change requires dropping `biz` and letting it recreate (see `DropCollection.java`).
- **Milvus reconnect**: changing schema/collection requires dropping the collection (`biz`) or the persisted `volumes/` data; `MilvusClientFactory` only creates the collection if it doesn't already exist.
- **`DocumentChunkService` deletes old data** by metadata `_source` before re-indexing a file — path is normalized to forward slashes (`File.separator → "/"`), so keep that convention when querying/deleting by `_source`.
- **Session state is in-memory** (`ChatController` `ConcurrentHashMap` + `ReentrantLock`, max 6 message pairs per session, sliding window). It is lost on restart. — 对照：claim 状态已落 MySQL、重启不丢，两者是「宪法要求持久化」下的新老之别。
- **claim 返回真实 HTTP 状态码**（4xx/5xx + body `{code,message,data}`），与遗留 Chat 的「HTTP 200 包错误」是两套风格（有意为之，plan O1 记债）。`AlertClaimException` 的错误码 `code` 整除以 100 即标准 HTTP 状态（40901/100=409）。
- **Instant 序列化字形漂移**：`WebConfig` 自定义了 Jackson 消息转换器（未关闭 `WRITE_DATES_AS_TIMESTAMPS`）→ HTTP 响应里 `Instant` 实际输出**数值时间戳**（epoch 秒.纳秒，@WebMvcTest 实证），而 `api.md` 示例写 ISO 串（V1 claimedAt 即如此，US2 suppressedUntil 同）。测试断言勿钉死字形（controller 层只断言存在/非空）；若需 ISO 需改 WebConfig（超 claim 切片、全局配置债）。
- **claim 测试在 H2(`MODE=MySQL`) 替身跑**：DDL 全 ANSI、语义面窄；但并发「恰一人」与跨进程重启的**权威**证据需真 MySQL（quickstart §2 Step F/E），H2 替身不能证跨进程持久。
- **`/ai_ops` and `/chat_stream`** rely on `OutputType.AGENT_MODEL_STREAMING` / `AGENT_TOOL_FINISHED` etc. from the agent-framework's `StreamingOutput` — don't rename these enum usages without checking the framework version.
