# Implementation Plan: 告警认领与处置（Alert Claim & Disposition）

**Branch**: `001-alert-claim` | **Date**: 2026-09-06 | **Spec**: [spec.md](spec.md)

> 本文是 /speckit-plan 的输出（spec-kit 现行命名为每 feature 目录下的 `plan.md`，即早期版本的 PLANS.md）。六段结构：**架构决策 → 数据模型 → API 契约 → 文件改动清单 → 测试计划 → 风险与开放问题**。详细设计与论证见 [research.md](research.md)、[data-model.md](data-model.md)、[contracts/api.md](contracts/api.md)、[quickstart.md](quickstart.md)。

## Summary

为 AIOps 补上「诊断后」的责任闭环：值班 SRE 认领一条已被诊断的告警（稳定标识 = alert_name），认领状态落库、对同班立即可见、先到先得防重复接管、重启不丢。技术核心：给本无持久化的项目补一层 **Spring Data JPA + MySQL（开发/生产同引擎）**；「一条告警至多一个有效认领」由 **alerts 单行状态 + 条件 UPDATE（CAS）** 保证，不引入部分唯一索引。测试用 H2(`MODE=MySQL`) 嵌入式替身，免 Docker。

## Technical Context

- **Language/Version**: Java 17 · Spring Boot **3.2.0**
- **Primary Dependencies**: 沿用 spring-ai-alibaba `1.1.0.0-RC2`（dashscope + agent-framework）+ mcp-client。**新增**：`spring-boot-starter-data-jpa`、`com.mysql:mysql-connector-j`(runtime)、`com.h2database:h2`(**test** scope)、`spring-boot-starter-test`(test)。不引入 Spring 生态外框架。
- **Storage**: **MySQL 8**（统一数据源；开发经 `docker run mysql`，见 quickstart §0）。新表 `alerts`、`claim_events`。
- **Testing**: JUnit5 + Mockito；`@WebMvcTest`/`@DataJpaTest` 切片（@DataJpaTest 自动落内存 H2-MySQL 模式），避免拉起 DashScope/Milvus；并发另做集成测试 + 真库手动复验。
- **Target Platform**: 单实例本地服务（:9900），Linux/Windows 皆可。
- **Project Type**: web-service（现有 Spring Boot REST 单体）。
- **Performance Goals**: 演示规模无吞吐指标；**并发下恰一人认领成功**是唯一正确性要求。
- **Constraints**: 不新增 Spring 生态外框架；**不改 AIOps 诊断引擎**（planner/executor/SupervisorAgent/工具注册一律不动）；alert_name 为键（research R3）；版本边界 V1 只实现 P1（FR-001~006）。
- **Scale/Scope**: V1 单实例、单值班团队、3 条 mock 告警；为「诊断→认领→复盘」叙事服务。

## Constitution Check

*GATE 通过。无违规，Complexity Tracking 留空。*

| 原则 | 判定 |
|---|---|
| I 技术无关（spec 无 HOW） | spec 纯 WHAT；HOW 全落在 plan/文档层，分层正确 ✅ |
| II 特性切片独立交付 | P1 单 slice = 可用 MVP，repo/测试/契约齐备 ✅ |
| III 测试必带 | 仓库当前 0 测试（探索确认）。本 feature 一并补测试基建并把 spec 全部 Acceptance 转成测试——对既有缺口的正当实现，非违规 ✅ |
| IV/V 工具三件套/mock | V1 不新增 @Tool，不动工具注册；复用 QueryMetricsTools 既有 mock 开关 ✅ |
| 硬约束·状态不放内存 | claim 状态落 MySQL 持久化 ✅（ChatController 会话内存属既有遗留，见 O3） |

## 1. 架构决策

| # | 决策 | 一句话理由 | 详 |
|---|---|---|---|
| D1 | **Spring Data JPA + MySQL**（开发/生产同引擎） | 仓库零持久化；「repository+实体+表结构」即用户点名方式；MySQL 满足 FR-005 持久性 + 真并发语义；测试用 H2-MySQL 替身免 Docker | research R1/R5 |
| D2 | **单行状态 CAS**：`alerts` 行承载 status/claimed_by；认领=`UPDATE … WHERE status='DIAGNOSED'` 判受影响行数 | MySQL 无通用部分唯一索引；行锁 + 重估谓词 = 原生原子「恰一人」 | research R2 + data-model §4 |
| D3 | **稳定标识 = alert_name** | `SimplifiedAlert` 无 id 且真实模式已按 alertname 去重；无需给 mock 造 id | research R3 |
| D4 | **ai_ops 前置薄记录**（ChatController 入口注入 recorder，读 QueryMetricsTools feed，幂等 upsert，绝不降级） | 满足 FR-006「未诊断不可认领」，不触碰诊断引擎；全项目仅改 1 个业务类、+约 2 行 | research R4 |
| D5 | claim 模块独立包 `org.example.claim.*` | 模块内聚、便于后续抽取/演进；与既有扁平 RAG/AIOps 包并存 | §4 树 |

**spec「待 plan 细化」开放项裁决**：重复诊断**不撤销认领**（IN_PROGRESS/RESOLVED 不被 recorder 降级，与严重级别/抑制无关，抑制为 P2 主题）；认领粒度 = alert_name（fingerprint 级留待真实数据源）；并发语义 = DB 条件更新（research R6 全表）。

## 2. 数据模型

**详**: [data-model.md](data-model.md)。要点：

- **`alerts`**（PK `alert_name`）：`status` / `claimed_by` / `claimed_at` / `last_diagnosed_at` / 时间戳。
- **`claim_events`**（PK `id`，FK→alerts，只追加）：`alert_name` / `operator` / `event_type`(V1=`CLAIM`，预留扩展) / `created_at`。写动作审计 + 时间线。
- **索引**：`alerts(status)`（列表过滤）；`claim_events(alert_name, created_at)`（时间线）。**无部分唯一索引**——「至多一个有效认领」是不变量由行内状态保证。
- **校验映射**：data-model §7（FR-001/004/005/006/011 一一对应）。

### 2.1 状态机

```
  DIAGNOSED ──claim(原子条件更新,唯一V1迁移)──▶ IN_PROGRESS ──P3: RESOLVE/CLOSE(预留)──▶ RESOLVED/CLOSED
      ▲  (recorder upsert, 绝不降级)              │
```

- V1 唯一可执行迁移：`DIAGNOSED → IN_PROGRESS`，谓词 `WHERE status='DIAGNOSED'`。
- 非法迁移被谓词天然拦截 + 守卫分支返回明确错误：行不存在→40401；本人重复→40902；他人占用→40901（携当前负责人）；已结束→40903。
- 重新诊断不撤销认领（recorder 只刷新 `last_diagnosed_at`）。

## 3. API 契约

**详**: [contracts/api.md](contracts/api.md)。信封沿用 `ApiResponse{code,message,data}`，但 claim 用真实 HTTP 状态码（旧 Chat 的「HTTP 200 包错误」收敛见 O1）。

| 方法/路径 | 用途 | 成功 | 主要失败 |
|---|---|---|---|
| `POST /api/alerts/{alertName}/claim` | 认领 | 200 → AlertView | 400 空参 / 40401 未诊断 / 40901 他人 / 40902 本人 / 40903 已结束 |
| `GET /api/alerts/{alertName}` | 负责人可见性(FR-003) | 200 → AlertView | 404 |
| `GET /api/alerts?status=` | 列表/找可认领 | 200 → {alerts} | 40003 非法过滤值 |
| `GET /api/alerts/{alertName}/events` | 历史时间线（可选） | 200 → {events} | 404 |

并发语义收敛：N 次并发认领 = 恰 1 成功 + (N−1) 个 409。P2/P3 预留方向（不实现端点）：`suppress` / `resolve`；Agent 化 `@Tool` 双注册方向见 contracts §2。

## 4. 文件改动清单

### 新增 — 生产代码 `src/main/java/org/example/claim/`

```text
claim/
├── dto/        ApiResponse<T>      # 共享信封
│               ErrorCode           # 枚举：200/40001/40002/40401/40901/40902/40903/50000
│               AlertClaimException # 携带 ErrorCode，controller 统一转 HTTP
│               ClaimRequest        # { operator }
│               AlertView           # 对外告警视图（status/claimedBy/claimedAt…）
├── entity/     AlertStatus         # DIAGNOSED/IN_PROGRESS/RESOLVED/CLOSED
│               Alert  (@Entity, table=alerts)
│               ClaimEvent (@Entity, table=claim_events)
├── repository/ AlertRepository     # claimIfDiagnosed(@Modifying 条件UPDATE)、findById、upsert 辅助
│               ClaimEventRepository
├── service/    AlertClaimService   # @Transactional 认领编排 + 守卫分支 → 异常/视图
│               AlertDiagnosisRecorder  # 读 QueryMetricsTools feed → 幂等 upsert(DIAGNOSED)
└── controller/ AlertClaimController   # 4 个端点
```

### 修改 — 现有代码（仅 3 处，其中 2 处是配置）

| 文件 | 改动 |
|---|---|
| `pom.xml` | + `spring-boot-starter-data-jpa`、`mysql-connector-j`(runtime)、`h2`(**test**)、`spring-boot-starter-test`(test) |
| `src/main/resources/application.yml` | + `spring.datasource.url=jdbc:mysql://localhost:3306/superbiz?...`（账号走 `${MYSQL_USER:sba}`/`${MYSQL_PASSWORD:sba}` 占位+env）、`spring.jpa`(`ddl-auto: update` 开发、`open-in-view: false`)；开发设 `prometheus.mock-enabled: true`。无 profile 分支——单一 MySQL 引擎 |
| `controller/ChatController.java` | `aiOps()` 前置注入 `AlertDiagnosisRecorder`，invoke 前调用一次记录（R4 接缝，**唯一动到的业务类**，约 +2 行） |

### 新增 — 测试 `src/test/java/org/example/claim/`（见 §6 清单）
### 其它
- 测试资源：`src/test/resources/application.yml`（或 properties）声明 `spring.datasource` 指向内存 H2 并 `MODE=MySQL`，供 @DataJpaTest 使用。
- `AGENTS.md`/`CLAUDE.md`：新增 claim 端点/表的口径与「未跑 ai_ops 认领得 40401」说明（可选，随实现）。
- 开发库启动命令（非仓库文件，写进 quickstart/CLAUDE.md）：`docker run --name sba-mysql … mysql:8`。

## 5. 状态机守卫与非法迁移

已并入 §2.1；运行时统一在 `AlertClaimService`：先校验入参 → 条件 UPDATE 判行数 → 失败按 re-read 分支映射 40901/40902/40903/40401 → 成功追加 `claim_events(CLAIM)`。

## 6. 测试计划（覆盖 spec 全部 Acceptance Scenarios）

测试基建：新增 `spring-boot-starter-test`；`@DataJpaTest` 自动用内存 H2(`MODE=MySQL`)；`@WebMvcTest`/Mockito 避免拉起 DashScope/Milvus。

| spec 验收 | 测试 | 类型 |
|---|---|---|
| US1-1 认领成功、状态+归属记录 | `AlertClaimServiceTest.claimSuccess` + `AlertRepositoryTest.claimIfDiagnosedReturns1` | 单测 + @DataJpaTest |
| US1-2 二次认领被拒并示负责人 | `AlertClaimServiceTest.rejectOtherShowsOwner` + `AlertRepositoryTest.claimIfDiagnosedReturns0` | 单测 + @DataJpaTest |
| US1-3 负责人立即可见 | `AlertClaimControllerTest.getAlertShowsOwner`（含列表） | @WebMvcTest |
| US1-4 重启后记录仍可查 | 自动化：service 写入→新事务/re-read 断言持久化；**真·跨进程重启**：quickstart §Step E（MySQL 容器持久）手动 | 集成 + 手动 |
| US1-5 本人重复认领不产生二主 | `AlertClaimServiceTest.selfReclaimReturnsSelfClaimed` | 单测 |
| 边界·不存在/未诊断认领 | `AlertClaimServiceTest.unknownAlert404` + controller 404 | 单测 + @WebMvcTest |
| 边界·已结束不可认领 | seed RESOLVED → `AlertClaimServiceTest.endedAlert409`（repo 0 行 + 映射 40903） | 单测 + @DataJpaTest |
| 边界·空参 | `AlertClaimControllerTest.blankOperator400` / `blankAlert400` | @WebMvcTest |
| 边界·并发双认领恰一人 | `AlertClaimConcurrencyIT`（替身）+ quickstart §Step F（真库手动复验） | 集成 + 手动 |
| 边界·重复诊断不撤销认领 | `AlertDiagnosisRecorderTest.reDiagnosisDoesNotDowngrade`（IN_PROGRESS 行经 recorder 后仍 IN_PROGRESS） | 集成(@DataJpaTest) |

新增测试类：`AlertClaimServiceTest`、`AlertRepositoryTest`、`AlertClaimControllerTest`、`AlertDiagnosisRecorderTest`、`AlertClaimConcurrencyIT`。运行 `mvn test` 全绿（免 Docker），`mvn verify` 作为提交门。

## 7. 风险与开放问题

| # | 风险/开放 | 影响 | 处置 |
|---|---|---|---|
| O1 | 遗留 3 处重复 `ApiResponse` + Chat「HTTP200 包错误」惯例与 claim 的真实状态码不一致 | 代码库两套风格 | V1 claim 用新共享信封；旧类收敛列为后续 refactor，不动旧行为 |
| O2 | 测试替身(H2-MySQL)与真 MySQL 语义差异 | 理论偏差 | DDL 全 ANSI、偏差面小；并发「恰一人」另在真库手动复验（quickstart Step F）。可选增强：Testcontainers 真库 IT（引入 testcontainers + 测试期 Docker），默认不启用 |
| O3 | ChatController 会话仍在内存（宪法硬约束项） | 违反宪法字面 | 属既有 RAG 会话债，非本 feature；已在 CLAUDE.md 记债，另开切片处理 |
| O4 | `ddl-auto=update` 仅限开发；生产 `validate` 需 schema 与实体一致；无正式迁移工具 | 生产形态不完整 | 本 feature 目标 = 可演示 + 可测；Flyway 迁移链留后续（明确 out of scope） |
| O5 | alert_name 粒度 = 无「同名单多实例」区分；真实模式按 alertname 去重 | 语义上限 | 文档化为已知局限；接真实 Prometheus 换 fingerprint 作键来源、表结构不变 |
| O6 | 新 claim 包 vs 既有扁平包布局分叉 | 结构两种风格 | 有意为之（模块内聚）；若强求统一再议 |
| — | 是否需要静态演示页（static/） | 范围外 | 默认不做，纯 REST + curl/测试即可证；需要则另开 |
