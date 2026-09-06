# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SuperBizAgent is a Spring Boot (Java 17) system with two AI capabilities, both backed by Alibaba Cloud DashScope (Qwen) models via Spring AI Alibaba:

1. **RAG Q&A** — upload documents → chunk → embed → store in Milvus → retrieve + generate answers.
2. **AIOps** — a multi-agent pipeline that analyzes Prometheus alerts and produces a structured Markdown diagnostic report.

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

Requires `DASHSCOPE_API_KEY` in the environment and a running Milvus instance (default `localhost:19530`).

```bash
# Start Milvus (etcd + minio + standalone + attu) via Docker Compose
docker compose -f vector-database.yml up -d

# Build / run the Spring Boot app (port 9900)
mvn clean install
mvn spring-boot:run
```

The `Makefile` wraps the full lifecycle — `make init` is the one-shot path (start Docker → start app → wait → upload `aiops-docs/*.md` into Milvus). Other targets: `make up/down/start/stop/restart/check/upload/clean`. The Makefile assumes Unix shell utilities (`curl`, `nohup`, `docker-compose`) — it will not work verbatim on a Windows shell.

There is no test suite (no `src/test`). Verification is manual: hit the endpoints below or use `src/main/resources/static/` at `http://localhost:9900`.

### Key HTTP endpoints

- `POST /api/chat` — non-streaming chat (ReactAgent with tool calling)
- `POST /api/chat_stream` — SSE streaming chat
- `POST /api/ai_ops` — trigger the multi-agent alert analysis (SSE)
- `POST /api/upload` — upload a `.txt`/`.md` file, auto-chunk + embed + index
- `GET /milvus/health` — Milvus health check
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

### Tool registration model

Tools are wired two ways into each `ReactAgent`:
- `.methodTools(...)` — passes tool bean instances so the framework generates tool JSON from `@Tool`/`@ToolParam` annotations.
- `.tools(toolCallbacks)` — passes `ToolCallback[]` from `ToolCallbackProvider` (auto-scanned `@Tool` methods **and** any MCP tools).

`QueryLogsTools` is injected `@Autowired(required = false)` — it only exists in the context when `cls.mock-enabled=true` (see `ChatService.buildMethodToolsArray` / `AiOpsService.buildMethodToolsArray`, and the commented `@ConditionalOnProperty` on the class). In "real" mode the tool array omits it, on the assumption the MCP client supplies log tools instead.

### Milvus schema

`MilvusClientFactory.createClient` lazily creates collection `biz` on first connect with fields: `id` (VarChar PK), `vector` (FloatVector), `content` (VarChar), `metadata` (JSON). Vector index is `IVF_FLAT` with `L2` metric.

## Gotchas

- **API key**: injected from `DASHSCOPE_API_KEY` env var (default `your-api-key-here`). `VectorEmbeddingService` fails fast at startup if it's not set.
- **Vector dimension mismatch risk**: `MilvusConstants.VECTOR_DIM = 1024`, but the comment says "豆包" (Doubao) model while the actual embedding model in `application.yml` is DashScope `text-embedding-v4`. If you switch embedding models, the collection dimension must be recreated to match — Milvus collections are immutable once created, so a dimension change requires dropping `biz` and letting it recreate (see `DropCollection.java`).
- **Milvus reconnect**: changing schema/collection requires dropping the collection (`biz`) or the persisted `volumes/` data; `MilvusClientFactory` only creates the collection if it doesn't already exist.
- **`DocumentChunkService` deletes old data** by metadata `_source` before re-indexing a file — path is normalized to forward slashes (`File.separator → "/"`), so keep that convention when querying/deleting by `_source`.
- **Session state is in-memory** (`ChatController` `ConcurrentHashMap` + `ReentrantLock`, max 6 message pairs per session, sliding window). It is lost on restart.
- **`/ai_ops` and `/chat_stream`** rely on `OutputType.AGENT_MODEL_STREAMING` / `AGENT_TOOL_FINISHED` etc. from the agent-framework's `StreamingOutput` — don't rename these enum usages without checking the framework version.
