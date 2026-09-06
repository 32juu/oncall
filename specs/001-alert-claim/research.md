# Research: 告警认领与处置 — Phase 0 决策记录

**Branch**: `001-alert-claim` | **Date**: 2026-09-06 | **Spec**: [spec.md](spec.md)

本文件记录 Phase 0 对 spec 中开放项与代码现实冲突的裁决。每个条目：**Decision / Rationale / Alternatives considered**。

---

## R1. 持久化：MySQL（开发/生产同一引擎），测试用 H2 的 MySQL 模式作嵌入式替身；用 Spring Data JPA

**Decision**: 引入 `spring-boot-starter-data-jpa` + `com.mysql:mysql-connector-j`(runtime)；**数据源统一为 MySQL**（开发与生产同一引擎，无 H2 双轨）。`spring.jpa.ddl-auto=update` 仅限开发/演示，生产形态 `validate`。测试引入 H2 驱动(test scope)，以 `MODE=MySQL` 作嵌入式替身；不引入 Testcontainers。

**Rationale**:
- 选 MySQL：行级锁 + 真实并发语义；「重启不丢」(FR-005) 由 DB 持久性天然满足；开发/生产同构 → 并发保证（条件更新 CAS）最终在真库上验证，而非仅在替身上。
- 开发跑库成本：项目本就要用 Docker 起 Milvus，加一个 `docker run mysql` 即得开发库（见 plan §4 / quickstart），不算引入新负担。
- 测试为何用替身而非真 MySQL：保持 `mvn test` 免 Docker、快、CI 友好；本特性 SQL 全 ANSI 化、无方言依赖，替身足以覆盖「受影响行数 1/0」「状态机守卫」等语义。**并发「恰一人」的权威断言另在真 MySQL 上手动复验**（quickstart §真实并发）。
- 现有项目零持久化、零 ORM；但约束点名「controller + service + repository」「实体与表结构、唯一性约束与索引」——Spring Data JPA 是 Spring 标准表达，不算引入生态外框架（MyBatis/Flyway/Redis 一概不加）。「不引入新框架」= 不引入 Spring 生态之外的栈；不同意可回退 JdbcTemplate（见备选）。

**Alternatives considered**:
- H2(dev) + MySQL(prod) 双轨：两套运行环境 + 方言差异处处维护；用户已明确只要 MySQL → 不选。
- H2 作开发运行时：省 Docker，但并发正确性只在替身上验证、与生产引擎不同构 → 不选。
- Testcontainers MySQL 作测试：最真实，但引入 testcontainers 依赖并强制测试期 Docker → 不作为默认；列为可选增强（plan O2）。
- 纯 `JdbcTemplate`（`spring-boot-starter-jdbc`）：能做到，但「repository+实体」表达力弱、样板多，与 repository 心智不符 → 不选。
- 复用 Milvus 当 KV：把「关系型状态机」塞进向量库是错误抽象 → 明确不选。

---

## R2. 并发安全「一条告警至多一个有效认领」：单行状态 + 条件 UPDATE，不用「部分唯一索引」

**Decision**: 当前归属/状态作为**单行字段**存在 `alerts` 表（PK=alert_name，行内 status/claimed_by/claimed_at）；认领 = 一条带状态谓词的原子 UPDATE（`WHERE status='DIAGNOSED'`），以「受影响行数==1」判定成功。不建部分唯一索引。

**Rationale**:
- 一行一告警，「至多一个有效认领」是**行内状态不变量**，天然成立，无需跨行约束。
- 并发下两条事务同时 UPDATE 同一行 → MySQL **行锁（Record Lock）串行化**；第二条获得锁后**重估 WHERE 谓词**，发现已是 IN_PROGRESS → 0 行 → 判失败。数据库原生原子 CAS，READ COMMITTED 即正确，不依赖应用锁。
- MySQL 没有可移植的部分唯一索引语义下最稳的等价比对：MySQL 无通用 partial index（8.0 无，需生成列技巧），H2 亦不支持 → 靠「独立 claim 表 + 部分唯一索引」不可移植（见备选）。

**Alternatives considered**:
- 独立 `claims` 表 + 「active 唯一」：需 partial unique 或应用层 active 维护，H2/MySQL 移植性差 → 不选。
- 应用层锁（synchronized/分布式锁）：单实例能过，多实例失效，把并发安全押在应用层 → 不选。
- 乐观锁 `@Version` + 重试：冲突率低时纯浪费；条件 UPDATE 更直接 → 不选。

---

## R3. 告警「稳定标识」的现实答案：alert_name 作主键

**Decision**: V1 用 **`alert_name` 作为 claim 的稳定标识**（`alerts.alert_name` PK），并把「标识来自当前 Prometheus 数据源、已按 alertname 去重」写进假设。

**Rationale**:
- 代码探索证实 `SimplifiedAlert` 无 id，真实模式按 `alertname` 去重、mock 固定 3 个名字。故「稳定标识」在现有模型下唯一诚实的候选就是 alert_name。
- spec 假设「当前 mock 告警需补充/暴露该标识」→ 结论：**无需给 mock 造 id**，暴露语义直接定为 alert_name，成本为零。
- 已知局限（诚实入 plan/风险）：无「同名单多实例」粒度。将来接真实 Prometheus 用 `fingerprint` 换键来源时，仅换来源，表结构不变。

**Alternatives considered**:
- 给 `SimplifiedAlert`/feed 增加独立 `id` 字段：改真实模式返回 + mock + 诊断链，范围膨胀、V1 无新增价值 → 不选。
- 认领时自造 id：无来源、无法与 AIOps 输出对上 → 不选。

---

## R4. FR-006「未诊断不可认领」的接缝：ai_ops 前置薄记录，不改诊断引擎

**Decision**: 在 `ChatController.aiOps()` 调用 supervisor **之前**，注入 claim 模块 recorder 组件，读取当前 Prometheus 告警清单（复用现有 `QueryMetricsTools.queryPrometheusAlerts()`，JSON 解析出 alert_name 集合），对每个名字**幂等 upsert**：不存在则插入 `status=DIAGNOSED`；已存在**只刷新 last_diagnosed_at、绝不降级**（IN_PROGRESS/RESOLVED 不被重新诊断改回 DIAGNOSED）。

**Rationale**:
- 「仅已诊断可认领」需要一个「诊断过」信号；`/api/ai_ops` 报告是自由 Markdown、无结构化覆盖清单 → 薄记录是必要接缝。
- recorder 在 supervisor 调用之外做一次读+写，不触碰 planner/executor/SupervisorAgent/工具注册 → 满足「不改诊断引擎本身」；对 ChatController 的改动 = +1 注入 +1 行调用。
- 复用对象是 planner 同样看到的 `queryPrometheusAlerts()` → 「认领目标 = 诊断会话实际面对的告警」，语义闭合。
- **不降级规则**保住排他性：认领后的告警再次被 ai_ops 命中，不会把 IN_PROGRESS 打回 DIAGNOSED（spec 开放项在此裁决，与严重级别无关，见 R6）。

**Alternatives considered**:
- 放宽为「凡在当前 feed 出现即可认领」（完全不动 ai_ops）：实现最省，但丢 FR-006「必须诊断过」语义 → 不选。
- 在 `AiOpsService` 内记录：改动落在引擎编排类，比 ChatController 更接近「引擎」红线 → 不选，接缝放 controller 入口。

---

## R5. MySQL 运行与测试替身配置

**Decision**: 运行时数据源指向 MySQL（`jdbc:mysql://localhost:3306/superbiz`，账号走 yml 占位 + env 覆盖）；`ddl-auto=update` 限开发/演示，生产形态 `validate`。H2 仅为 test-scope 依赖，测试在内存 H2 并以 `MODE=MySQL` 启动。

**Rationale**: 运行时单一 MySQL 引擎避免双轨方言维护；测试替身 H2-MySQL 模式只为让 `mvn test` 免 Docker。`@DataJpaTest` 会自动用内存 H2 替换数据源，测试配置声明 `MODE=MySQL` 使类型/语义贴近 MySQL（本特性 DDL 全 ANSI，偏差面很小）。

**Alternatives considered**: 测试直连开发 MySQL（污染数据 + 需常驻库）→ 不选；Testcontainers（最真但要 Docker）→ 可选增强，非默认。

---

## R6. spec「待 plan 细化」开放项裁决汇总

| spec 开放项 | 裁决 | 落在 |
|---|---|---|
| 重复诊断是否影响已认领状态（更高严重级别） | **不降级**：IN_PROGRESS/RESOLVED 不被 recorder 重置；抑制（P2）默认不与级别联动、不自动取消——留待 P2 | recorder upsert + data-model 状态机 |
| 抑制窗口时间/与级别耦合 | P2 范围，V1 不实现；仅预留 `event_type` 扩展位 | 契约预留方向 |
| 认领对象粒度（同名单多实例） | V1 = alert_name 粒度；fingerprint 级留待真实数据源接入 | R3 |
| 并发重复认领的原子语义 | DB 条件 UPDATE，受影响行数判定 | R2 |
| 已诊断语义 | = 被至少一次 ai_ops 运行记录过（DIAGNOSED 行存在） | R4 |
| 冒名（自报标识） | 接受，文档化，属后续账号/RBAC 片 | spec Assumptions |
| 存储选型（用户裁决） | **MySQL**：开发/生产同一引擎；测试 H2-MySQL 替身 | R1/R5 |

**Decision**: 全部按上表落定，无遗留 NEEDS CLARIFICATION。
