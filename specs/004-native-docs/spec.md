# Feature Specification: 原生文档入 RAG（语料扩宽收编）

**Feature**: `004-native-docs` | **Created**: 2026-09-08 | **Branch**: main | **Impl**: commit `8ab2748` | **Data Model**: 无（复用既有 Milvus `biz` 管道，零 schema 变更）

**Input**: RAG 知识库一直只吃 txt/md（图片在切片 1 补齐），真实的 PDF/Word/PPT 运行手册进不了库。让 `/api/upload` 收原生文档（pdf/docx/pptx）→ PDFBox/POI 抽纯文本 → 走与图片同一 `indexParsedText` 缝入 RAG → 聊天/`InternalDocsTools` 可检索文档内容。这是 ROADMAP §5「原生 PDF/Office 语料扩宽」债的**收编**（不再独立推进）。

## 一句话设计

**文档与图片走同一条已抽好的公共缝 `indexParsedText(sourceId, text)`，缺的还是「文档 → 文本」这段前置。** 新增 `DocumentTextExtractor`（pdf=PDFBox / docx,pptx=POI）把文档抽成纯文本，输出前 `normalize`（统一换行、折叠空行、**超长行断行**），`FileUploadController` 落盘后三分支（图片 caption / 文档 extract / txt-md 原 `indexSingleFile`）。

## User Scenarios & Testing

### User Story 1 — 上传 PDF/Word/PPT 运行手册，文档内容可被检索（Priority: P1，已实现）

值班把一份 `runbook.pdf` 上传知识库；之后在聊天里问「内部文档有提到认领流程吗」→ agent 的 `queryInternalDocs` 命中该文档解析出的段落。

**Independent Test**：上传含 `告警认领` 字样的 pdf → 200 → 问检索「告警认领」命中 `_source` 为 pdf 路径的 content。纯手动项（需 DashScope + Milvus）。

**Acceptance Scenarios**:
1. **Given** 一份含文字层的 pdf/docx/pptx, **When** 经 `/api/upload` 上传, **Then** HTTP 200、文件落 `./uploads`、抽出的文本入 `biz`（`metadata._source` = 文件路径）。
2. **Given** 同名文件再次上传（覆盖）, **When** 更新后查询, **Then** `deleteExistingData(_source)` 清旧、只留新解析（无重复）。
3. **Given** 无文字层文档（如扫描件/图片型 PDF）, **When** 上传, **Then** HTTP 500 + `{code:500, message:"文档解析或入库失败…（文件已保存，未入库）"}` —— 抽不出文本即无可入库内容，与图片同哲学、不与 txt 的「索引失败仍 200」混淆。
4. **Given** 白名单外类型（如 `.exe`）, **When** 请求, **Then** HTTP 400（维持既有）。

## Requirements

- **FR-001**: `/api/upload` 必须接受 `pdf/docx/pptx`，与 txt/md/图片同入白名单——**四门面一致**（controller `DOC_EXTENSIONS` + `application.yml` + index.html `accept` + app.js `validateFileType`）。
- **FR-002**: 文档必须经 `DocumentTextExtractor.extract` 抽纯文本，走与图片同一 `indexParsedText` 缝入 RAG，`_source` = 文件路径（metadata 形状一致，覆盖更新/溯源相同）。
- **FR-003**: 抽取输出必须 `normalize`——把超长单行断成 ≤ ~600 字符，且**断点保留空格**（去掉换行可还原原文）。原因：`DocumentChunkService.chunkSection` 按 `\n\n` 切段落、对「单段落超 max-size」**不硬切**；不断行会把整份 200KB 无换行文本当一段塞进一个 chunk（远超 800 上限）；断点丢空格会把词粘一起（`cpu usage`→`cpuusage`），检索 token 不命中。
- **FR-004**: 文档解析/入库失败（含空文本 = 无文字层）必须返回显式 HTTP 500，不得静默成功；文件已落盘保留便于排查。
- **FR-005**: 只支持有文字层的文档，不引入 OCR；纯文本本地解析、不实连任何外部服务（离线可单测）。

## 关键决策注记

| 问题 | 决策 | 为什么 |
|---|---|---|
| 解析库 | PDFBox 3.0.3（PDF）+ poi-ooxml 5.3.0（docx/pptx） | poi-ooxml 一条依赖覆盖 XWPF(docx) + XSLF(pptx) 两格式，最省依赖面 |
| 文档进 RAG 的缝 | 复用 `indexParsedText` | 与切片 1 图片同构：管道已在、只换「前置抽文」，`_source`=文件路径语义统一 |
| 超长行要不要断 | **必须断**（normalize 硬要求） | chunkSection 对单段落超 max-size 不硬切（见 FR-003），不断 = 单段整块入一个 chunk，分块与检索同时失效 |
| 解析失败语义 | HTTP 500（同图片） | 抽不出文本=无内容可入库，假 200 会误导演示；区别于 txt「文件本身是内容源、吞错仍 200」 |
| 测试 | 测试内 PDFBox/POI 现造 pdf/docx/pptx fixture | 离线、零实连；锁「抽取到关键字 + 断行内容不丢」两个不变量 |

## Out of Scope

- `.xlsx`（POI 顺手可做，留债）。
- 扫描件/图片型 PDF 的 OCR（无文字层 → extractor 空 → 500 诚实报，留债）。
- 文档进 **AIOps 诊断链路**。
- 聊天直发文档（只支持贴图，见 `specs/003-chat-image/`）。
- RBAC/账号。
