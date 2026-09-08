# ROADMAP · SuperBizAgent 学习/重构进度追踪

> **性质**：坐标文档（非工程真相）。事实以代码、`CLAUDE.md`、`specs/001-alert-claim/` 与各提交为准；本文件只回答「站在哪、下一步做什么、离 X 还有多久」。基准框架 = 《SuperBizAgent_Claude优化执行文档》四周期表 + Harness 六模块。最后更新 **2026-09-08**。

---

## 0. 一句话坐标

**「告警认领」这条竖线已超额走完（计划排在 W2、产物链验收在 W3），但横向的 RBAC / 真实数据源 / Playbook / 多模态 / CI / MCP 仍未铺开。** 课程 Phase 文档只到 MCP（Phase1-3）；上下文管理优化、多模态、Evaluation 在其后，仓库内唯一给出周槽位的是下方执行文档尺。

---

## 1. 主线里程碑（执行文档 §9 四周期表 + 实态）

| 周 | 阶段 | 关键交付 | 实态 | 证据 |
|---|---|---|---|---|
| W1 | Phase 0 | Claude Code 环境 / 重构版 CLAUDE.md / spec-kit 就绪 / ≥10 测试 | ✅ 超额 | CLAUDE.md 已 <200 行演进；spec-kit + speckit-* 全套；**claim 9 测试类 / 全仓 mvn verify 105 绿** |
| W1 | 分支+PR 工作流 | feat/* + PR | ⚪ 有意未用 | 直接推 main（学习节奏，PR 待 CI 引入后再开） |
| W2 | 告警认领模块（SDD 产出） | US1 认领 / US2 抑制 / US3 处置 端到端 | ✅ 全走完 | 06cb634(US1) → 5f94cef(US2) → 03de489+32f17bb(US3)；spec/plan/data-model/contracts/checklists 齐全 |
| W2 | 认领/抑制 **Agent 化写工具** | claimAlert / suppressAlert /（disposition 未做） | ◐ | fff41cc / 41aa210；chat-only + config-bound + 单测 |
| W2 | **工具分级 ToolLevel + ToolRegistry** | @ToolLevel + readOnlyOnly 只读闸 | ✅ 代码落地 | 4050644 + b3b156e（注：无 principal 层 → RBAC 仍是债） |
| W2 | RBAC 权限（§5.1） | Spring Security+JWT / 文档/接口/工具级隔离 | ❌ 未开始 | 复盘 §5 记为 out-of-scope 债；缺 identity/principal 层 |
| W2 | 真实 Prometheus / CLS | QueryMetricsTools 接真源 / QueryLogsTools→MCP | ❌ mock 仍在 | 接 MCP 是 Phase3 落地口（见 §2） |
| W2 | 多模态上传解析（§5.4） | /api/upload 收图 → Qwen-VL → 进诊断 | ❌ 未开始 | 距最近的一个独立切片 |
| W3 | Playbook（§5.3） | SRE 事件生命周期状态机 + 报告模板 | ❌ 未开始 | claim 责任环 ≠ AIOps 编排改造 |
| W3 | 六件套实例 | CLAUDE.md / Skill / SubAgent / MCP / Hooks / Plugin | ◐ 仅 CLAUDE.md + SDD skill | .claude/agents、hooks、.mcp.json、plugin 均不存在 |
| W3 | CI/CD | PR 流水线 + CD + headless | ❌ 未开始 | 无 .github/workflows |
| W4 | Harness 收口 | Redis 会话 / 案例记忆 / 评估集 / 复盘 | ❌ 未开始 | 见 §3 三话题 |

**判定**：认领主线已到 W3 验收线；横向停在 W1~W2 交界。整体 ≈ 四周期表的 **第 2~3 周之间**（但按切片数而非日历计时，见 §4）。

---

## 2. 课程主线（学习文档 Phase1-3）与执行文档对照

| 课程阶段 | 内容 | 本仓库映射 | 实态 |
|---|---|---|---|
| Phase1 Agent 基础 | 手写 SimpleAgent / Agent Loop | Agent Loop 概念已在 AIOps supervisor + ReactAgent | ◐ 概念已用，SimpleAgent 手写未留档（学习文档，非代码债） |
| Phase2 SpringAI 核心 | ChatClient / Structured Output / Tool / Memory / RAG / VectorStore | 全仓：ChatService / agent/tool / Milvus / SessionInfo | ◐ 多数已占位；ChatClient、官方 Structured Output、ChatMemory 三处仍是改进位 |
| Phase3 MCP | 写 3 个 MCP Server（mysql/git/project） | pom + application.yml 仅留骨架 | ❌ 未建（.mcp.json 不存在） |

→ 上下文管理优化 / 多模态 / Evaluation 是**课程 Phase3 之后**的主题；仓库内暂无对应期数文档，进度参照下方执行文档尺。

---

## 3. 进阶三话题坐标（你问的「还有多久」）

| 话题 | 槽位 | 前置 | 最小切片拆分 | 当前态 | 距离（按切片） |
|---|---|---|---|---|---|
| **多模态** | Phase1 §5.4 / W2 验收：上传截图产出解析并进诊断链路 | 无强依赖（与 RBAC 并列） | ①开发侧：Claude 原生 vision 直贴截图（≈0，演示）②产品侧：/api/upload 收图 → DashScope Qwen-VL 描述 → 进 AIOps/RAG | 零 | **1 切片**（≈1 session），最近 |
| **上下文管理优化** | Harness「上下文精细化」（W4 Redis 验收） | ②要 Redis 实例；③要 Playbook 先存在（§5.3） | ①工具结果截断（ToolRegistry 配套，独立）②会话迁 Redis + 超阈值转摘要（消 6 轮滑动窗债）③Playbook 移出 system prompt | 零（截断位预留） | ①≈0.5 切片可提前；全套在其后 **≈4-6 切片** |
| **Evaluation** | Harness「评估与观察」（W4 末：评估集 ≥20 例 + LLM-as-judge/规则校验 + 可观测） | 依赖真实告警源/真实工具（现全 mock）、有标注数据 | ①热身：以 recorder mock feed + 报告模板完整性做规则校验②正式：历史告警集 + 标注根因 + LLM-as-judge | 零 | 殿后，**≥4-6 切片 + 数据准备** |

**推荐到达顺序**：多模态（现在）→ [RBAC / MCP 真实数据源] → 上下文管理优化 → Evaluation。

---

## 4. 计时单位：切片，不是周

- 实测节奏：US1 = 2 天（复盘 09-06→09-07）；US3 整条（规划→码→测试→docs→双提交→push）≈ 1 次坐完。
- 里程碑若按「日历周」乐观排（W2/W3/W4）会被横切项稀释；**面试压缩时按执行文档 §9：优先 RBAC + 真实工具 + 认领 + SDD 产物链四件套**，本 ROADMAP 只保证随时能回答「差几个切片」。

---

## 5. 已记债（从 CLAUDE.md / spec plan O* / 复盘 §5 汇总，防丢失）

- **RBAC/账号**：无 identity/principal 层，ToolLevel 只有「角色≈READ/WRITE 槽位」的退化形态（agent 无身份）。
- **真 MySQL 权威复验**：并发恰一人 / 跨进程重启未实跑（H2 替身只证语义）——quickstart §2 Step E/F。
- **会话内存态**：6 轮滑动窗重启即丢（宪法「持久化」下的新老之别，对照 claim 已落 MySQL）。
- **ChatController / 旧 Chat 双 HTTP 风格**：claim 真 4xx/5xx vs 遗留 200 包错（plan O1）。
- **Instant 序列化字形漂移**：HTTP 输出数值时间戳，测试勿钉 ISO（WebConfig 全局债）。
- **executor 只执行首步 / 单线编排**：supervisor 循环无重试回退（Harness 执行编排改造位）。
