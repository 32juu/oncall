# Contract: 认领 Agent 工具（claimAlert @Tool）· 已落地（V1，chat-only）

**Branch**: `001-alert-claim` | **Design**: 2026-09-07 | **Impl**: 2026-09-08（commit fff41cc）| **Status**: 已实现 ✅
**Spec**: [../spec.md](../spec.md) | **REST Contract**: [api.md](api.md) §2（Agent 化预留方向）

> 本文原为**未来切片输入**的设计骨架；2026-09-08 已按 D1/D3/D4/D5/D6 落地为 `org.example.claim.tool.ClaimAlertTool`。
> 落地对原纸面有两处裁决偏离，记录在案：**D3 注册面收敛为仅聊天 agent**、**D4 operator 改为部署配置绑定**（见 §1 / §5 O1）。

## 0. 定位（一句话）

`claimAlert` = 让值班 SRE 在聊天 agent（`/api/chat`）里认领的 **WRITE 工具**——它是 `AlertClaimService.claim()` 的一层**薄适配器**：只把模型的话翻译成 service 调用、把结果翻译回模型可读的文案，**绝不重写认领逻辑**。

## 1. 设计决策（D1–D6，带为什么）

| # | 决策 | 为什么 |
|---|---|---|
| D1 | **薄适配器**：唯一依赖 `AlertClaimService`，不实现第二条认领路径 | 认领并发不变量（CAS「恰一人」）现在只有一个家（service，REST 也走它）。工具另起炉灶 = 复刻双清单/双注册漂移病：**两条路径各自漂移**。契约不变式只能有一个家 |
| D2 | `@ToolLevel(Level.WRITE)`（对齐执行文档 §5.2 草稿注解） | 非 READ_ONLY（改库）；非 HIGH_RISK（认领是值班常规操作且有业务审计 claim_events；HIGH_RISK 留给未来自愈/处置）。但认领 **sticky**（V1 无改派）→ 谨慎性交给 D3 注册闸表达 |
| D3 | **默认不注册**：`@ConditionalOnProperty("agent.claim-tool-enabled")=true` 才进 `ChatService.buildMethodToolsArray()`。**落地裁决：仅聊天 agent，不接 AiOpsService** | 把"能改库"工具暴露给所有会话 = 任何对话可动认领状态，脱离值班语境。**QueryLogsTools 的教训的正确版**——它宣称条件注册、实际无 `@ConditionalOnProperty`（总是注册 + 死分支）。不接 AiOpsService：认领=人工接管，AIOps 诊断运行不得**无人值守自动认领**（对原「双注册」措辞的有意偏离，见 commit fff41cc）。ToolRegistry（统一注册入口）单列 P 债，不在此工具内建 |
| D4 | operator **由部署配置 `agent.claim-operator` 绑定**，工具签名**不收 operator** 参数 | 让模型填 operator = 任何对话可冒名（spec Assumptions 已知局限的放大器）。Spring AI 工具回调拿不到会话用户 → 落地裁决：以**单 operator 值班会话**形态暴露（operator 从部署配置绑、非模型填；语义 = 本实例即该值班人工位）；未配置则 fail-closed 拒绝。会话绑定/账号体系（区分多用户）留后续账号/RBAC 切片，见 O1 |
| D5 | 返回 ≤500 字符、只回模型可读文案，不吐堆栈 | 认领视图/错误码本身短小；工具输出的"截断"靠注册面窄 + 结果短实现，不需要 QueryMetrics 式大 JSON |
| D6 | 审计**复用** `claim_events`（service 同事务已写 CLAIM），工具层不建第二张审计表 | 避免双记同一次认领。工具调用是入口、业务审计已在 service。工具级"哪个 agent/会话触发"溯源见 O2 |

## 2. 错误码映射（复用 api.md 总表，文案与 REST 同源）

| service 抛出 | 工具返回文案（要点） |
|---|---|
| 成功 | `认领成功：{alertName} 状态 IN_PROGRESS，负责人 {operator} @ {claimedAt}`（operator = 配置绑定的值班负责人） |
| 40001 operator 空 | `操作人标识不能为空` |
| 40002 alertName 空 | `告警标识不能为空` |
| 40401 不存在/未诊断 | `告警不存在或尚未被诊断，无法认领` |
| 40901 已被他人认领 | `该告警已被 {claimedBy} 负责，无法重复认领` |
| 40902 已被本人认领 | `该告警已由您负责，无需重复认领` |
| 40903 已结束 | `该告警已结束，无法认领` |
| 未知异常 | `认领失败（内部错误），请稍后重试`——**不抛** |

> 与 REST 同文案 → 值班人无论走 curl 还是 chat 得到一致语义；工具层只在 service 抛 `AlertClaimException` 时翻译 code→文案，其余异常兜底为通用错误。

## 3. 骨架（紧凑形）

```java
@Component                                                       // 实际实现见 org.example.claim.tool.ClaimAlertTool
@ConditionalOnProperty(name = "agent.claim-tool-enabled",
                        havingValue = "true")                    // D3 默认不注册（QueryLogs 教训的正确版）
public class ClaimAlertTool {
    private final AlertClaimService claimService;                // D1 薄适配器，CAS 单一来源
    private final String dutyOperator;                           // D4 部署绑定，构造注入

    public ClaimAlertTool(AlertClaimService claimService,
                          @Value("${agent.claim-operator:}") String dutyOperator) { ... }

    @Tool(description = "认领一条已诊断且未被他人认领的告警（负责人先到先得、锁定到处理结束）…")
    public String claimAlert(@ToolParam(description = "告警名称，如 HighCPUUsage") String alertName) {
        // operator 空 → fail-closed 返回「未配置值班负责人」；
        // 否则 claimService.claim(alertName, dutyOperator) → 成功 / 守卫文案；
        // 业务与运行时异常全部捕获转文案（D5），绝不抛给 LLM
    }
}
```

> 落地时**没有**实现原纸面的 `@ToolLevel(Level.WRITE)` 注解——该注解当前不存在于代码（执行文档 §5.2 草稿），本切片遵循 §6 范围边界不建注解本体/拦截器。「写权限」由两件已落地的事实表达：①条件注册缺省关（bean 缺省不存在）；②operator 服务端解析（模型无身份表达通道）。`@ToolLevel` + `ToolRegistry`（统一注册入口）作为后续切片第一个 Task 单列 P 债。

## 4. 测试清单（只测"翻译层"）

不重复 service 已测的 CAS 并发语义（那些归 `AlertClaimServiceTest`/`ConcurrencyTest` 管）——**测试职责也只有一个家**。

| 测什么 | 替身 | 断言 | 落点 |
|---|---|---|---|
| 成功 → 返回 IN_PROGRESS 视图 | Mockito 替 `AlertClaimService.claim` 返 AlertView | 文案含 alertName / `IN_PROGRESS` / 负责人 | ✅ `ClaimAlertToolTest.claim_success_returnsReadableText` |
| 40001/40401/40901/40902/40903 | service 抛对应 `AlertClaimException` | 返回对应 message，**不抛异常** | ✅ `claim_errorCodes_becomeTextWithoutThrowing` |
| 未知异常 | service 抛 RuntimeException | 返回通用错误文案，不抛 | ✅ `claim_runtimeException_becomesGenericTextWithoutThrowing` |
| 空 operator（D4 fail-closed） | 构造传空白 operator | 返回「未配置值班负责人」，`verify never` 调 service | ✅ `claim_blankDutyOperator_failsClosed_neverCallsService` |
| `@ConditionalOnProperty` 缺省 / false / true | `ApplicationContextRunner` | 缺省与 false 无 bean；true 有 bean（防自动扫描泄漏） | ✅ `bean_absent_whenPropertyMissing/False` + `bean_present_whenPropertyTrue` |
| `@ToolLevel(Level.WRITE)` 元数据 | 反射读注解 | 分级合规 | ⛔ 注解未实现（§6 边界），转后续切片 |

## 5. 开放项（记债、不假装解决）

- **O1**（D4）：~~operator 身份绑定来源~~ → **已裁决（2026-09-08）**：operator 由部署配置 `agent.claim-operator` 绑定（V1 占位），工具签名不收 operator；未配置则拒绝。会话绑定/账号体系（区分多用户）归后续账号/RBAC 切片——落地时把配置替换为会话 principal 即可，工具签名不受影响。
- **O2**（D6）：工具级 agent 溯源审计（哪个 agent/会话触发了认领）是否需要——如需，独立 tool-call audit，不影响 claim_events。

## 6. 范围边界（已落地，此边界内不做）

- **不建 `ToolRegistry` / `@ToolLevel` 注解本体与拦截器**（D2 的执行载体，后续切片第一个 Task）。
- **不做工具级审计表**（O2）。
- **不在 chat 会话引入账号体系**（与 spec Assumptions 冒名局限同源，V1 以配置绑定占位；区分多用户归账号/RBAC 切片）。
- **不接 AiOpsService**（注册面 = 仅聊天 agent，见 §1 D3 裁决）。

### 落地清单（2026-09-08）

- `src/main/java/org/example/claim/tool/ClaimAlertTool.java`（新）
- `src/test/java/org/example/claim/tool/ClaimAlertToolTest.java`（新，7 例）
- `src/main/java/org/example/service/ChatService.java`：字段注入 + `buildMethodToolsArray()` + 系统提示词路由
- `src/main/resources/application.yml`：`agent.claim-tool-enabled`（缺省 false）/ `agent.claim-operator`（缺省空）
