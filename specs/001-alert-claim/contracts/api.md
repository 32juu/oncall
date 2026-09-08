# Contract: 告警认领 API（V1 认领闭环 + US2 抑制窗口 + US3 处置结局）

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
| 400 | 40006 | 处置结局非法（缺失/非 RESOLVED/CLOSED） | `处置结局必须是 RESOLVED（已解决）或 CLOSED（误报/关闭）` |
| 400 | 40007 | 处置动作文案超长（>500 字） | `处置动作描述不能超过 500 字` |
| 404 | 40401 | 告警不存在或未诊断 | `告警不存在或尚未被诊断，无法认领` |
| 409 | 40901 | 已被他人认领 | `该告警已被 sre-bob 负责，无法重复认领` |
| 409 | 40902 | 已被本人认领（重复操作） | `该告警已由您负责，无需重复认领` |
| 409 | 40903 | 已结束告警不可操作（认领 / 抑制 / 处置） | `该告警已结束，无法认领` / `…无法设置或取消抑制窗口` / `…无法再记录处置` |
| 409 | 40904 | 未认领告警不可抑制 / 不可处置 | `仅处理中（已认领）的告警可设置抑制窗口` / `该告警尚未被认领，无法记录处置，请先认领` |
| 409 | 40905 | 非当前负责人不可设/取消抑制窗口 | `仅当前负责人可设置或取消抑制窗口` |
| 409 | 40906 | 非当前负责人不可记录处置 | `仅当前负责人可记录处置动作与结局` |
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

**200**：该告警的 `claim_events` 时间线（升序）。事件类型已覆盖 `CLAIM`（认领）、`SUPPRESS`/`SUPPRESS_CANCEL`（US2 抑制窗口设置/取消）与 `RESOLVE`/`CLOSE`（US3 处置终局，携带 `note`=处置动作文案）：

```json
{
  "code": 200, "message": "ok",
  "data": { "events": [
    { "id": 1, "eventType": "CLAIM", "operator": "sre-alice", "createdAt": "2026-09-06T10:00:00Z", "note": null },
    { "id": 2, "eventType": "SUPPRESS", "operator": "sre-alice", "createdAt": "2026-09-06T10:05:00Z", "note": null },
    { "id": 3, "eventType": "RESOLVE", "operator": "sre-alice", "createdAt": "2026-09-06T10:30:00Z", "note": "已重启故障服务并观察 10 分钟" }
  ]}
}
```

告警不存在 → 404。SC-006 的「诊断→认领→处置→结局」完整时间线读法：诊断头 = GET alert 的 `lastDiagnosedAt`；本端点给 `CLAIM → SUPPRESS → RESOLVE/CLOSE(+note)`（recorder 不写 DIAGNOSED 事件，见 data-model §3）。

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

### 1.6 处置终局（US3，FR-010/011，2026-09-08）

负责人对**自己负责、处理中**的告警记录处置动作并一次进入终态（`RESOLVED` 已解决 / `CLOSED` 误报或关闭）；记录后该告警不可再认领、抑制或处置（终态单向）。

```
POST /api/alerts/{alertName}/disposition
Content-Type: application/json

{
  "operator": "sre-alice",
  "outcome": "RESOLVED",
  "action": "已重启故障服务并观察 10 分钟"
}
```

- `outcome`：必填，`RESOLVED` | `CLOSED`（大小写不敏感）。**刻意走 String 而非枚举反序列化**：垃圾值经 Jackson 进 String 后由 service 集中解析校验 → 40006（若用枚举，垃圾值在绑定期即 HttpMessageNotReadable → 500，守卫分散）。无「观察中」中间结局（spec 裁决，见 spec.md US3）。
- `action`：可选处置动作文案，≤ 500 字（超长 40007）；写入事件的 `note`，时间线可读。
- 生效范围：仅「处理中 + 本人负责」的行（谓词见 data-model §4.3）；成功时**顺手清掉抑制窗口**（结局 = 抑制无意义）。

**200 成功**：

```json
{
  "code": 200, "message": "ok",
  "data": {
    "alertName": "HighCPUUsage", "status": "RESOLVED", "claimedBy": "sre-alice",
    "claimedAt": "2026-09-06T10:00:00Z", "lastDiagnosedAt": "2026-09-06T09:55:00Z",
    "suppressedUntil": null
  }
}
```

**失败分支**：

| 场景 | HTTP | body.code | 要点 |
|---|---|---|---|
| operator 为空 | 400 | 40001 | `操作人标识不能为空` |
| outcome 缺失 / 非 RESOLVED/CLOSED | 400 | 40006 | `处置结局必须是 RESOLVED 或 CLOSED` |
| action 超过 500 字 | 400 | 40007 | `处置动作描述不能超过 500 字` |
| 告警不存在或未诊断 | 404 | 40401 | `告警不存在或尚未被诊断` |
| 未认领（DIAGNOSED）不可处置 | 409 | 40904 | `该告警尚未被认领，无法记录处置，请先认领` |
| 非当前负责人 | 409 | 40906 | `仅当前负责人可记录处置动作与结局` |
| 已 RESOLVED / CLOSED | 409 | 40903 | `该告警已结束，无法再记录处置` |

> **并发语义**：对同一告警并发处置（含与认领/抑制竞争）→ 条件 UPDATE「恰一人」赢；先被他人/本人处置成功者回读即 40903（终态单向，见 data-model §4.3）。

## 2. 预留方向（写端点均已实现见 1.1/1.5/1.6；Agent 化进展如下）

范围内 REST 写端点（认领/抑制/处置）均已实现；余下的是**处置暴露给 agent**：

- **处置 Agent 化**：US3 以单个 `POST /disposition` 落地（body `{outcome, action?}` 合一，替代早期设想的 `/resolve`|`/close` 分端）；对照 `claimAlert`/`suppressAlert` 把处置做成聊天 agent 写工具属后续切片（同 `claim-tool-enabled` 闸 + `ToolLevel.WRITE`，operator 仍走部署绑定）。
- **Agent 化**（复用现有工具注册）——两只 claim 写工具都已落地，均仅注册到聊天 agent（ChatService，认领/抑制=人工接管，不接 AiOpsService），operator 由部署配置 `agent.claim-operator` 绑定、签名不收 operator：
  - `claimAlert(alertName)` **已实现**（2026-09-08，commit fff41cc）——`org.example.claim.tool.ClaimAlertTool`，认领一条已诊断告警，见 [agent-tool.md](agent-tool.md)。
  - `suppressAlert(alertName, until)` + `cancelSuppression(alertName)` **已实现**（2026-09-08，commit 41aa210）——`org.example.claim.tool.SuppressAlertTool`，设/取消抑制窗口。`until` 接受 ISO-8601 时刻或相对时长（`2h`/`90m`/`1d`，自 now 起算），守卫（须晚于当前 40004、未认领 40904、非负责人 40905、已结束 40903）复用 service、翻译层单测在 `SuppressAlertToolTest`。

## 3. 与现有系统的一致性注意

- 前端/演示入口：`src/main/resources/static/` 现有页面只覆盖 chat/RAG；claim 端点为纯 REST，可用 `curl` 或后续静态页。V1 不新增页面（范围外，可在 open question 确认）。
- 认领对象来自 ai_ops 前置 recorder 记录的 DIAGNOSED 行；**未跑过 `/api/ai_ops` 的 alert_name 一律 40401**，这是 FR-006 的正确行为而非缺陷。
