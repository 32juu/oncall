# Feature Specification: 图片入 RAG（多模态 · 知识库切片 1）

**Feature**: `002-image-rag` | **Created**: 2026-09-08 | **Branch**: main | **Impl**: commit `1aa727c` | **Data Model**: 无（复用既有 Milvus `biz` 管道，零 schema 变更）

**Input**: 知识库当前只吃 `.txt/.md`，图片进不来。让 `/api/upload` 收图片（jpg/jpeg/png/webp）→ DashScope `qwen-vl-max` 看图产出 Markdown → 走**既有** chunk/embed/insert 管道落 Milvus → 聊天/`InternalDocsTools.queryInternalDocs` 可检索到图内内容（指标名/告警名/阈值）。

## 一句话设计

**管道与图片无关，缺的只有「图片 → 文本」这段新前置。** 抽取公共索引缝 `VectorIndexService.indexParsedText(sourceId, text)`（文本与图片共用 delete→chunk→embed→insert），图片分支先 `ImageCaptionService.caption` 转 Markdown 再走该缝，`_source` = 图片路径（覆盖更新/溯源一致）。

## User Scenarios & Testing

### User Story 1 — 上传监控截图，图内内容可被内部文档检索命中（Priority: P1，已实现）

值班/面试演示把一张含指标与告警名的监控截图经 `/api/upload` 上传；系统经 Qwen-VL 把图转成可检索 Markdown 入知识库。之后问聊天 agent「内部文档里有提到 CPU 85% 吗」→ `queryInternalDocs` 命中该图的解析文本。

**Independent Test**：上传含 `CPU 85%` 字样的 png → 200 → 问检索「CPU 85%」命中 `_source` 为图片路径的 content。纯手动项（需 DashScope + Milvus）。

**Acceptance Scenarios**:
1. **Given** 一张 png 截图, **When** 经 `/api/upload` 上传, **Then** HTTP 200、文件落 `./uploads`、解析文本入 `biz`（`metadata._source` = 图片路径）。
2. **Given** 该图再次上传（同名覆盖）, **When** 更新后查询, **Then** 旧内容被 `deleteExistingData(_source)` 清除、只留新解析（无重复）。
3. **Given** 视觉模型解析失败/不可用, **When** 上传图片, **Then** HTTP 500 + `{code:500, message:"图片解析或入库失败…（文件已保存，未入库）"}` —— 不与 txt 的「索引失败仍 200」混淆（txt 文件本身是内容源；图无解析即无可入库内容）。
4. **Given** 上传白名单外类型（如 `.pdf`）, **When** 请求, **Then** HTTP 400（维持既有）。

## Requirements

- **FR-001**: `/api/upload` 必须接受 `jpg/jpeg/png/webp`，与 txt/md 同入白名单（controller + yml + 前端三处一致）。
- **FR-002**: 系统必须调用视觉模型（qwen-vl 系，`spring.ai.dashscope.vision.model` 可配）把图片转为**可检索的纯 Markdown**（一级标题带源文件名，供 `DocumentChunkService` 按标题分块）。
- **FR-003**: 图片解析文本必须走与 txt/md 相同索引管道（同一 `indexParsedText` 缝），`_source` = 图片路径，metadata 形状一致。
- **FR-004**: 图片解析或入库失败必须返回显式错误（HTTP 500），不得静默成功。
- **FR-005**: 传图调用不得新增依赖或改动 pom（Spring AI `UserMessage`+`Media` → `DashScopeChatModel` 已桥接 base64）。

## 关键决策注记

| 问题 | 决策 | 为什么 |
|---|---|---|
| 图片解析文本去哪 | 入 RAG 知识库 | 用户拍板；落 `biz` 后被 InternalDocs 检索 → 「截图进库可查」主场景最短 |
| 实现缝 | 抽 `indexParsedText(sourceId, text)` 公共缝 | 管道已有、与图片无关；只切开「给定 source+text」的复用口，文本/图片一条管道 |
| 演示面 | 前后端一起 | controller 分流 + yml 白名单 + index.html accept + app.js validateFileType 同步放行 |
| 测试 | mock `ChatModel` / spy Milvus 内部方法 / `@WebMvcTest`+`@TempDir` | 不实连 DashScope/Milvus；锁定「图片 bytes→UserMessage+Media」桥接输入端 |

## Out of Scope

- 原生 PDF/Word/PPT 解析（另一条「语料扩宽」独立切片——现状 txt/md 外都不支持，与多模态无关）。
- **对话侧直贴图**（用户贴图给 agent 当场看图问答）：候选切片 2。推荐**形态 B**（`ImageCaptionService` 图转述 → 文本 agent 照常调工具/认领；与切片 1 同构、风险最低），备选形态 A（纯 qwen-vl 看图问答，不进工具链——VL 在该 agent 框架工具调用不成熟）。
- 图片进 **AIOps 诊断链路**（§5.4 后续）。
- RBAC/账号。
