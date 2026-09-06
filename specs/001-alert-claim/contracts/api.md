# Contract: 告警认领 API（V1 REST）

**Branch**: `001-alert-claim` | **Date**: 2026-09-06 | **Spec**: [spec.md](../spec.md) | **Data Model**: [data-model.md](../data-model.md)

## 0. 响应信封与错误码总表

沿用现有项目 `ApiResponse{code, message, data}` 形状，但 claim 模块**采用真实的 HTTP 状态码**（现 ChatController 对错误也回 HTTP 200 + code=500，见「开放问题 O1」）。V1 提供一个共享信封 `org.example.claim.dto.ApiResponse<T>`；遗留三处重复 `ApiResponse` 的收敛列为开放问题，不动旧代码。

| HTTP | body.code | code 含义 | message（示例） |
|---|---|---|---|
| 200 | 200 | 成功 | `ok` |
| 400 | 40001 | operator 为空 | `操作人标识不能为空` |
| 400 | 40002 | alertName 为空/非法 | `告警标识不能为空` |
| 404 | 40401 | 告警不存在或未诊断 | `告警不存在或尚未被诊断，无法认领` |
| 409 | 40901 | 已被他人认领 | `该告警已被 sre-bob 负责，无法重复认领` |
| 409 | 40902 | 已被本人认领（重复操作） | `该告警已由您负责，无需重复认领` |
| 409 | 40903 | 已结束告警不可认领 | `该告警已结束，无法认领` |
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
    "lastDiagnosedAt": "2026-09-06T09:55:00Z"
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
    "lastDiagnosedAt": "2026-09-06T09:55:00Z"
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
        "claimedAt": "2026-09-06T10:00:00Z", "lastDiagnosedAt": "2026-09-06T09:55:00Z" },
      { "alertName": "SlowResponse", "status": "DIAGNOSED", "claimedBy": null,
        "claimedAt": null, "lastDiagnosedAt": "2026-09-06T09:56:00Z" }
    ]
  }
}
```

非法 `status` 值 → 400（code 40003，`非法状态过滤值`）。

### 1.4 告警事件历史（审计/时间线，V1 只读可选）

```
GET /api/alerts/{alertName}/events
```

**200**：该告警的 `claim_events` 时间线（升序）：

```json
{
  "code": 200, "message": "ok",
  "data": { "events": [
    { "id": 1, "eventType": "CLAIM", "operator": "sre-alice", "createdAt": "2026-09-06T10:00:00Z" }
  ]}
}
```

告警不存在 → 404。此端点为 P3「诊断→认领→处置→结局 时间线」预留读路径。

## 2. 预留方向（P2 抑制窗口 / P3 处置记录，不做详细设计）

版本边界下**只给方向**，V1 不实现端点：

- **P2 抑制窗口**：`POST /api/alerts/{alertName}/suppress` body `{operator, until}`；仅当前负责人可用；窗口独立性（与诊断/级别不联动，见 research R6）。
- **P3 处置结局**：`POST /api/alerts/{alertName}/resolve`（或 `/close`）body `{operator, action, outcome}` → 触发 `IN_PROGRESS → RESOLVED/CLOSED`，仅当前负责人可用；事件写入 `claim_events.event_type`（已预留枚举位）。
- **Agent 化**（复用现有工具注册）：若将来要让 planner/SRE 在 chat 里认领，新增 `@Tool`（如 `claimAlert(alertName, operator)`），按 `AGENTS.md` 在 `ChatService.buildMethodToolsArray()` 与 `AiOpsService.buildMethodToolsArray()` **双注册**；方向同 QueryMetricsTools 风格，V1 不做。

## 3. 与现有系统的一致性注意

- 前端/演示入口：`src/main/resources/static/` 现有页面只覆盖 chat/RAG；claim 端点为纯 REST，可用 `curl` 或后续静态页。V1 不新增页面（范围外，可在 open question 确认）。
- 认领对象来自 ai_ops 前置 recorder 记录的 DIAGNOSED 行；**未跑过 `/api/ai_ops` 的 alert_name 一律 40401**，这是 FR-006 的正确行为而非缺陷。
