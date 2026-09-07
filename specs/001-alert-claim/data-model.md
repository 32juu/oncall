# Data Model: 告警认领与处置（V1 认领闭环 + US2 抑制窗口）

**Branch**: `001-alert-claim` | **Date**: 2026-09-06 | **Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md)

覆盖 V1（FR-001~006，P1）与 **US2 抑制窗口（FR-007~009，P2）**：US2 在 `alerts` 单行上新增 `suppressed_until` 列承载一个活动抑制窗口（行内状态哲学延续，见 §4.2）。P3 处置结局仍仅预留扩展位、不做表字段。

---

## 1. 设计原则（一句话）

**当前归属是单行状态，不单独建模「活动认领」行**：`alerts` 一行 = 一条告警，行内 `status / claimed_by / claimed_at` 描述当前归属。并发安全由「带状态谓词的单行原子 UPDATE」保证，见 §4。

## 2. 实体

### 2.1 Alert（告警）
映射表 `alerts`。一条已进入系统、可能被认领的告警；被认领则进入「处理中」并带归属。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| alertName | VARCHAR(128) | **PK** | 稳定标识 = Prometheus `alertname`（R3） |
| status | VARCHAR(20) | NOT NULL | 生命周期状态，见 §3 状态机 |
| claimedBy | VARCHAR(64) | NULL | 当前负责人（自报标识）；仅 `IN_PROGRESS` 时有值 |
| claimedAt | TIMESTAMP | NULL | 认领时间；仅 `IN_PROGRESS` 时有值 |
| lastDiagnosedAt | TIMESTAMP | NOT NULL | 最近一次被 AIOps 诊断记录的时间（recorder 刷新） |
| suppressedUntil | TIMESTAMP | NULL | **抑制窗口结束时刻（US2）**：非空且晚于当前 = 抑制中；惰性失效（now ≥ until 视为已结束，见 §4.2） |
| createdAt | TIMESTAMP | NOT NULL | |
| updatedAt | TIMESTAMP | NOT NULL | |

### 2.2 ClaimEvent（认领/事件历史，只追加）
映射表 `claim_events`。每次写动作的审计轨迹；已写 `CLAIM`（US1）、`SUPPRESS` / `SUPPRESS_CANCEL`（US2）。它是 spec 的「认领记录/时间线」落点，也是 FR-003/复盘（P3）与宪法「写操作可审计」的载体。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | BIGINT | PK，自增 | |
| alertName | VARCHAR(128) | NOT NULL, FK → alerts.alertName | |
| operator | VARCHAR(64) | NOT NULL | 动作执行人标识 |
| eventType | VARCHAR(20) | NOT NULL | 已写: `CLAIM`/`SUPPRESS`/`SUPPRESS_CANCEL`；预留 `RESOLVE/CLOSE` |
| createdAt | TIMESTAMP | NOT NULL | |

## 3. 状态机

状态（Enum `AlertStatus`）：`DIAGNOSED`（已诊断）→ `IN_PROGRESS`（处理中）→ `RESOLVED`（已解决）/ `CLOSED`（已关闭）。

```
                claim（本功能，原子条件更新）
  DIAGNOSED ───────────────────────────────▶ IN_PROGRESS
      ▲                                         │
      │ upsert（recorder，幂等，绝不降级）          │  RESOLVE/CLOSE 由 P3 处置记录实现（V1 不做，仅预留）
  无行 / 新告警                                  ▼
                                            RESOLVED / CLOSED
```

### 迁移表与非法迁移拦截

| 迁移 | 触发 | V1 是否实现 | 拦截/守卫 |
|---|---|---|---|
| (不存在) → DIAGNOSED | recorder 在 ai_ops 入口对 feed 中每个 alert_name 幂等 upsert | ✅ | 仅插入缺失行 / 刷新 lastDiagnosedAt；**不改变已存在行的 status/claimed\***；US2：抑制中的行连刷新也跳过 |
| DIAGNOSED → IN_PROGRESS | `claim` | ✅ | `UPDATE ... WHERE status='DIAGNOSED'`，受影响行数==1 才算成功 |
| IN_PROGRESS → RESOLVED/CLOSED | 处置记录（P3） | ⛔ 预留 | 仅当前负责人可发起 |
| 其它 → 其它 | — | — | 不存在入口即被拦截；claim 的状态谓词天然拒绝所有非 DIAGNOSED 迁移 |

**关键守卫语义（claim 失败分支，均落 REST 错误码）**：
- 行不存在（从未诊断）→ 404 `ALERT_NOT_FOUND`。
- 行存在但 `claimedBy == operator` → 409 `ALERT_SELF_CLAIMED`（同一人重复操作，不产生二主）。
- 行存在、`IN_PROGRESS` 且 `claimedBy != operator` → 409 `ALERT_ALREADY_CLAIMED`（先到先得，返回当前负责人）。
- 行存在、`RESOLVED/CLOSED` → 409 `ALERT_ENDED`。

**spec 开放项裁决（写进状态机语义）**：已认领（IN_PROGRESS）告警再次被 ai_ops 命中时，recorder **不得**把它降级回 DIAGNOSED（重新诊断不撤销认领）；RESOLVED/CLOSED 同理。

**US2 抑制闸（recorder）**：抑制窗口生效中的告警（IN_PROGRESS + suppressed_until > now）被 ai_ops 再次命中时，recorder **连诊断时间也不刷新**（FR-008 静音期内不产生新诊断记录）；窗口到点（惰性失效）后自动恢复刷新。抑制只附着于已认领行（仅负责人能设），故该闸永不作用于 DIAGNOSED 新行。

## 4. 并发安全「一条告警至多一个有效认领」

不变量由「行内状态」表达，无需跨行/部分唯一索引：

- `alerts.alert_name` 主键保证一告警一行。
- 认领 = 单行原子条件更新（repository 方法 `claimIfDiagnosed`）：

```sql
UPDATE alerts
   SET status = 'IN_PROGRESS', claimed_by = :operator,
       claimed_at = :now,      updated_at = :now
 WHERE alert_name = :alertName AND status = 'DIAGNOSED';
```

- 并发两条认领：数据库对同一主键行加锁串行化；第二条获得锁后**重估 WHERE**，发现 status 已是 `IN_PROGRESS` → 受影响 0 行 → 判失败。READ COMMITTED 下即正确，无需应用锁/版本号。
- 判定成功后在同一事务内追加一条 `claim_events(CLAIM)`。
- **为什么不做「部分唯一索引 + 独立 active 认领行」**：H2 与 MySQL 均无可移植的部分唯一索引（`UNIQUE ... WHERE` 不一致），见 research R2。

### 4.2 抑制窗口 CAS（US2，同样单行原子）

「一条告警至多一个活动抑制窗口」同样由**单行状态表达**：`alerts.suppressed_until` 一个列即当前窗口，覆盖式替换（再设 = 换窗，不累计）。设置/取消共用 repository 方法 `setSuppression`：

```sql
UPDATE alerts
   SET suppressed_until = :until, updated_at = :now
 WHERE alert_name = :alertName AND status = 'IN_PROGRESS' AND claimed_by = :operator
   AND suppressed_until IS DISTINCT FROM :until;
```

- 谓词三重守卫一次到位：仅「处理中 + 本人负责 + 值确有变更」的行被改到（affected==1 → 成功并记 SUPPRESS/SUPPRESS_CANCEL 事件）。
- `IS DISTINCT FROM :until` 保证【值真的变了才命中】：取消无窗口 / 重复设置同值 → 0 行，使 H2 与 MySQL 受影响行数语义一致（NULL→NULL 在 MySQL 计 0、H2 语义可能不同，少了它审计会分叉）。
- affected==0 → service 回读分类：本人且处理中 = 幂等成功（值未变，不记事件，避免脏审计）；非本人 → 40905；未认领（DIAGNOSED）→ 40904；已结束 → 40903；行不存在 → 40401。
- **惰性失效（无调度器）**：活动性一律 `suppressed_until > now` 判定；到点后列保留历史值，后续「判活动性」自然视为已结束，recorder 恢复刷新、不再需要清除任务。设窗口时校验 `until` 必须晚于当前（否则 40004）。
- **不做部分唯一索引的独立窗口行**：与 §4 同因（H2/MySQL 无通用部分唯一索引）；窗口生命周期由单列 + 覆盖写表达最简。

### 索引

| 表 | 索引 | 用途 |
|---|---|---|
| alerts | PK `(alert_name)` | 认领/查询按名定位、行锁 |
| alerts | `(status)` | `GET /api/alerts?status=...` 过滤（演示/列表） |
| claim_events | `(alert_name, created_at)` | 单告警时间线查询 |
| claim_events | FK `(alert_name)` | 引用完整性 |

可选防御：`alerts.status IN (...)` 的 CHECK 约束（H2/MySQL 8 均支持）；非必需但推荐加。

## 5. DDL（MySQL 8+；测试替身 H2 `MODE=MySQL` 亦兼容，SQL 全 ANSI）

```sql
CREATE TABLE IF NOT EXISTS alerts (
  alert_name         VARCHAR(128) NOT NULL,
  status             VARCHAR(20)  NOT NULL,
  claimed_by         VARCHAR(64)  NULL,
  claimed_at         TIMESTAMP    NULL,
  last_diagnosed_at  TIMESTAMP    NOT NULL,
  suppressed_until   TIMESTAMP    NULL,   -- US2 抑制窗口（单列活动窗口，惰性失效）
  created_at         TIMESTAMP    NOT NULL,
  updated_at         TIMESTAMP    NOT NULL,
  CONSTRAINT pk_alerts PRIMARY KEY (alert_name),
  CONSTRAINT ck_alerts_status CHECK (status IN ('DIAGNOSED','IN_PROGRESS','RESOLVED','CLOSED'))
);

CREATE TABLE IF NOT EXISTS claim_events (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  alert_name  VARCHAR(128) NOT NULL,
  operator    VARCHAR(64)  NOT NULL,
  event_type  VARCHAR(20)  NOT NULL,
  created_at  TIMESTAMP    NOT NULL,
  CONSTRAINT pk_claim_events PRIMARY KEY (id),
  CONSTRAINT fk_claim_events_alert FOREIGN KEY (alert_name) REFERENCES alerts(alert_name)
);

CREATE INDEX IF NOT EXISTS idx_alerts_status        ON alerts(status);
CREATE INDEX IF NOT EXISTS idx_claim_events_alert   ON claim_events(alert_name, created_at);
```

（实际落地由 JPA `ddl-auto=update` 从实体生成，此处 DDL 用于审阅与生产形态 `validate` 对照。开发/演示 `update`；生产 `validate` + 正式迁移另列风险，见 plan O4。）

## 6. 实体关系

```
Alert 1 ──── 0..* ClaimEvent
   行内字段承载当前归属       只追加审计/时间线（V1 仅 CLAIM）
```

- 每次成功认领：`alerts` 行状态变更（1 条 UPDATE）+ `claim_events` 追加（1 条 INSERT），同一事务。
- 认领后「当前负责人」查询直接读 `alerts.claimed_by/claimed_at`，不扫历史表 → FR-003「立即可见」低成本满足。

## 7. 校验规则（映射 FR）

| 规则 | 来源 | 落点 |
|---|---|---|
| alert_name / operator 非空 | FR-001 / 边界 | service 入参校验 → 400 |
| 仅 `DIAGNOSED` 可认领 | FR-006 | `claimIfDiagnosed` 状态谓词 |
| 已结束不可认领 | FR-006 / FR-011 | 同上（RESOLVED/CLOSED 不匹配谓词） |
| 重启后记录可查 | FR-005 | 默认 H2 file 持久化（R1） |
| 负责人不可在结束前变更（无改派） | FR-004 | V1 无任何改派入口；唯一写路径是 claim |
| 仅当前负责人可设/取消抑制窗口 | FR-009 | `setSuppression` 谓词 `claimed_by=:operator` + 守卫 40905 |
| 抑制窗口 `until` 必须晚于当前 | FR-009 | service `requireUntilInFuture` → 40004 |
| 抑制期再次触发不产生新诊断 | FR-008 | recorder 抑制闸（不刷新 lastDiagnosedAt） |
