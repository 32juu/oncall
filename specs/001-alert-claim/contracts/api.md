# Contract: 告警认领 API（V1 认领闭环 + US2 抑制窗口）

**Branch**: `001-alert-claim` | **Date**: 2026-09-06 | **Spec**: [spec.md](../spec.md) | **Data Model**: [data-model.md](../data-model.md)

## 0. 响应信封与错误码总表

沿用现有项目 `ApiResponse{code, message, data}` 形状，但 claim 模块**采用真实的 HTTP 状态码**（现 ChatController 对错误也回 HTTP 200 + code=500，见「开放问题 O1」）。共享信封 `org.example.claim.dto.ApiResponse<T>`；遗留三处重复 `ApiResponse` 的收敛列为开放问题，不动旧代码。

**说明**：HTTP 状态 = `body.code / 100`（40901→409、40004→400…），由 `@ExceptionHandler(AlertClaimException)` 统一映射。`AlertView` 现含 `suppressedUntil`（US2；无窗口为 `null`）。

| HTTP | body.code | code 含义 | message（示例） |
|---|---|---|---|
| 200 | 200 | 成功 | `ok` |
| 400 | 40001 | operator 为空 | `操作人标识不能为空` |
| 400 | 40002 | alertName 为空/非法 | `告警标识不能为空` |
| 400 | 40004 | 抑制窗口结束时刻非法（缺失/不晚于当前） | `抑制结束时刻必须晚于当前时间` |
| 404 | 40401 | 告警不存在或未诊断 | `告警不存在或尚未被诊断，无法认领` |
| 409 | 40901 | 已被他人认领 | `该告警已被 sre-bob 负责，无法重复认领` |
| 409 | 40902 | 已被本人认领（重复操作） | `该告警已由您负责，无需重复认领` |
| 409 | 40903 | 已结束告警不可操作（认领 / 抑制） | `该告警已结束，无法认领` / `该告警已结束，无法设置或取消抑制窗口` |
| 409 | 40904 | 未认领告警不可设抑制窗口 | `仅处理中（已认领）的告警可设置抑制窗口` |
| 409 | 40905 | 非当前负责人不可设/取消抑制窗口 | `仅当前负责人可设置或取消抑制窗口` |
| 500 | 50000 | 内部错误 | `服务器内部错误` |

## 1. 核心端点

### 1.1 认领告警

```
POST /api/alerts/{alertName}/claim
Content-Type: application/json

{
  "operator": "sre-alice"
}
```

- `alertName`：告警稳定标识（= Prometheus alertname），如 `HighCPUUsage`。
- `operator`：值班人自报标识，V1 不做认证（spec Assumptions）。

**200 成功**（HTTP 200）：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "alertName": "HighCPUUsage",
    "status": "IN_PROGRESS",
    "claimedBy": "sre-alice",
    "claimedAt": "2026-09-06T10:00:00Z",
    "lastDiagnosedAt": "2026-09-06T09:55:00Z",
    "suppressedUntil": null
  }
}
```

**失败分支**（同一路径的裁决顺序）：

| 场景 | HTTP | body.code | body.message 要点 |
|---|---|---|---|
| operator 为空 | 400 | 40001 | `操作人标识不能为空` |
| alertName 为空 | 400 | 40002 | `告警标识不能为空` |
| 告警从未被诊断（行不存在） | 404 | 40401 | `告警不存在或尚未被诊断` |
| 已被他人认领 | 409 | 40901 | 提示当前负责人（来自行内 claimed_by） |
| 已被本人认领（重复提交） | 409 | 40902 | `已由您负责` |
| 已 RESOLVED / CLOSED | 409 | 40903 | `告警已结束` |

> **并发语义**：多人同时认领同一告警 → 数据库条件 UPDATE 保证**恰一人**成功（受影响行数判定），其余人按上述 409 分支返回，不会出现双主（详见 data-model §4）。因此该端点天然幂等收敛：N 次并发认领 = 1 成功 + (N-1) 个 409。

### 1.2 查询单条告警（负责人可见性，FR-003）

```
GET /api/alerts/{alertName}
```

**200**：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "alertName": "HighCPUUsage",
    "status": "IN_PROGRESS",
    "claimedBy": "sre-alice",
    "claimedAt": "2026-09-06T10:00:00Z",
    "lastDiagnosedAt": "2026-09-06T09:55:00Z",
    "suppressedUntil": null
  }
}
```

**404**：告警不存在或未诊断（body.code 40401）。

### 1.3 列出告警（找「谁负责 / 哪些可认领」，演示与值班用）

```
GET /api/alerts?status=DIAGNOSED        # 可选过滤：DIAGNOSED | IN_PROGRESS | RESOLVED | CLOSED
```

**200**：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "alerts": [
      { "alertName": "HighCPUUsage", "status": "IN_PROGRESS", "claimedBy": "sre-alice",
        "claimedAt": "2026-09-06T10:00:00Z", "lastDiagnosedAt": "2026-09-06T09:55:00Z",
        "suppressedUntil": "2026-09-06T12:00:00Z" },
      { "alertName": "SlowResponse", "status": "DIAGNOSED", "claimedBy": null,
        "claimedAt": null, "lastDiagnosedAt": "2026-09-06T09:56:00Z", "suppressedUntil": null }
    ]
  }
}
```

非法 `status` 值 → 400（code 40003，`非法状态过滤值`）。

### 1.4 告警事件历史（审计/时间线，V1 只读可选）

```
GET /api/alerts/{alertName}/events
```

**200**：该告警的 `claim_events` 时间线（升序）。事件类型已覆盖 `CLAIM`（认领）与 `SUPPRESS`/`SUPPRESS_CANCEL`（US2 抑制窗口设置/取消）：

```json
{
  "code": 200, "message": "ok",
  "data": { "events": [
    { "id": 1, "eventType": "CLAIM", "operator": "sre-alice", "createdAt": "2026-09-06T10:00:00Z" },
    { "id": 2, "eventType": "SUPPRESS", "operator": "sre-alice", "createdAt": "2026-09-06T10:05:00Z" }
  ]}
}
```

告警不存在 → 404。此端点为 P3「诊断→认领→处置→结局 时间线」预留读路径。

### 1.5 设置 / 取消抑制窗口（US2，FR-007~009）

设置（覆盖式替换：重复设置 = 换新窗，不累计）：

```
POST /api/alerts/{alertName}/suppress
Content-Type: application/json

{
  "operator": "sre-alice",
  "until": "2026-09-06T12:00:00Z"
}
```

- `until`：窗口结束时刻（ISO Instant），**必须晚于当前**，否则 40004。
- 生效范围：仅「处理中 + 本人负责」的告警（谓词见 data-model §4.2）。

**200 成功**：

```json
{
  "code": 200, "message": "ok",
  "data": {
    "alertName": "HighCPUUsage", "status": "IN_PROGRESS", "claimedBy": "sre-alice",
    "claimedAt": "2026-09-06T10:00:00Z", "lastDiagnosedAt": "2026-09-06T09:55:00Z",
    "suppressedUntil": "2026-09-06T12:00:00Z"
  }
}
```

取消（幂等：本无窗口也成功、不记事件，避免脏审计）：

```
DELETE /api/alerts/{alertName}/suppress?operator=sre-alice
```

**200 成功**：同上，`suppressedUntil` 为 `null`。

**失败分支**：

| 场景 | HTTP | body.code | 要点 |
|---|---|---|---|
| operator 为空 | 400 | 40001 | `操作人标识不能为空` |
| until 缺失 / 不晚于当前 | 400 | 40004 | `抑制结束时刻必须晚于当前时间` |
| 告警不存在或未诊断 | 404 | 40401 | `告警不存在或尚未被诊断` |
| 未认领（DIAGNOSED）不可设窗 | 409 | 40904 | `仅处理中（已认领）的告警可设置抑制窗口` |
| 非当前负责人 | 409 | 40905 | `仅当前负责人可设置或取消抑制窗口` |
| 已 RESOLVED / CLOSED | 409 | 40903 | `该告警已结束，无法设置或取消抑制窗口` |

> **惰性失效（FR-008 / SC-005）**：窗口到点无调度器清除；活动性一律 `suppressed_until > now` 判定。抑制期内同告警再次触发，AIOps 前置 recorder **跳过诊断记录**（不刷新 lastDiagnosedAt）；到点后自动恢复。多用户并发设置同一告警 → 同认领：条件 UPDATE「恰一人」赢，其余 40905（见 data-model §4.2）。

## 2. 预留方向（P3 处置记录；US2 抑制窗口已实现见 1.5）

版本边界下**只给方向**，尚未实现端点：

- **P3 处置结局**：`POST /api/alerts/{alertName}/resolve`（或 `/close`）body `{operator, action, outcome}` → 触发 `IN_PROGRESS → RESOLVED/CLOSED`，仅当前负责人可用；事件写入 `claim_events.event_type`（预留 `RESOLVE/CLOSE` 位）。
- **Agent 化**（复用现有工具注册）：若将来要让 planner/SRE 在 chat 里认领或设抑制窗口，新增 `@Tool`（如 `claimAlert(alertName, operator)` / `suppressAlert(...)`），按 `CLAUDE.md`（Tool registration model）在 `ChatService.buildMethodToolsArray()` 与 `AiOpsService.buildMethodToolsArray()` **双注册**；方向同 QueryMetricsTools 风格，尚未实现（纸上设计见 [agent-tool.md](agent-tool.md)）。

## 3. 与现有系统的一致性注意

- 前端/演示入口：`src/main/resources/static/` 现有页面只覆盖 chat/RAG；claim 端点为纯 REST，可用 `curl` 或后续静态页。V1 不新增页面（范围外，可在 open question 确认）。
- 认领对象来自 ai_ops 前置 recorder 记录的 DIAGNOSED 行；**未跑过 `/api/ai_ops` 的 alert_name 一律 40401**，这是 FR-006 的正确行为而非缺陷。
