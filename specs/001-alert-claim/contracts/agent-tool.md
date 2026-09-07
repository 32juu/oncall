# Contract: 认领 Agent 工具（claimAlert @Tool）· 纸上设计

**Branch**: `001-alert-claim` | **Date**: 2026-09-07 | **Status**: 设计就绪 · **未实现**（V1 不做，未来切片输入）
**Spec**: [../spec.md](../spec.md) | **REST Contract**: [api.md](api.md) §2（Agent 化预留方向，L139）

> 本文是**未来切片输入**，不是 V1 交付物。它把 api.md §2 的「Agent 化：新增 `@Tool claimAlert`」扩展成一页可执行的三件套设计骨架，供未来切片直接升级为 spec 输入。

## 0. 定位（一句话）

`claimAlert` = 让 planner / 值班 SRE 在 chat 里认领的 **WRITE 工具**——它是 `AlertClaimService.claim()` 的一层**薄适配器**：只把模型的话翻译成 service 调用、把结果翻译回模型可读的文案，**绝不重写认领逻辑**。

## 1. 设计决策（D1–D6，带为什么）

| # | 决策 | 为什么 |
|---|---|---|
| D1 | **薄适配器**：唯一依赖 `AlertClaimService`，不实现第二条认领路径 | 认领并发不变量（CAS「恰一人」）现在只有一个家（service，REST 也走它）。工具另起炉灶 = 复刻双清单/双注册漂移病：**两条路径各自漂移**。契约不变式只能有一个家 |
| D2 | `@ToolLevel(Level.WRITE)`（对齐执行文档 §5.2 草稿注解） | 非 READ_ONLY（改库）；非 HIGH_RISK（认领是值班常规操作且有业务审计 claim_events；HIGH_RISK 留给未来自愈/处置）。但认领 **sticky**（V1 无改派）→ 谨慎性交给 D3 注册闸表达 |
| D3 | **默认不注册**：`@ConditionalOnProperty("agent.claim-tool-enabled")=true` 才进 ChatService / AiOpsService 两处 `buildMethodToolsArray()` | 把"能改库"工具暴露给所有 chat 会话 = 任何对话可动认领状态，脱离值班语境。**QueryLogsTools 的教训的正确版**——它宣称条件注册、实际无 `@ConditionalOnProperty`（总是注册 + 死分支）；本设计让开关真实存在。ToolRegistry（统一注册入口）单列 P 债，不在此工具内建 |
| D4 | operator **不当模型自报参数**：签名预留 operator 参数，由调用侧上下文绑定 | 模型只是通道，让模型填 operator = 任何对话可冒名（spec Assumptions 已知局限的放大器）。但 Spring AI 工具回调拿不到会话用户（Chat 会话内存态、无账号）→ V1 无解。安全落地形态 = 只在**单 operator 值班会话**内暴露（operator 从会话绑，非模型填）。见 O1 |
| D5 | 返回 ≤500 字符、只回模型可读文案，不吐堆栈 | 认领视图/错误码本身短小；工具输出的"截断"靠注册面窄 + 结果短实现，不需要 QueryMetrics 式大 JSON |
| D6 | 审计**复用** `claim_events`（service 同事务已写 CLAIM），工具层不建第二张审计表 | 避免双记同一次认领。工具调用是入口、业务审计已在 service。工具级"哪个 agent/会话触发"溯源见 O2 |

## 2. 错误码映射（复用 api.md 总表，文案与 REST 同源）

| service 抛出 | 工具返回文案（要点） |
|---|---|
| 成功 | `认领成功: {alertName} IN_PROGRESS，负责人 {operator} @ {claimedAt}` |
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
@ToolLevel(Level.WRITE)                                     // D2
@Component
@ConditionalOnProperty(name = "agent.claim-tool-enabled",
                        havingValue = "true")               // D3 默认不注册（QueryLogs 教训的正确版）
public class ClaimAlertTool {
    private final AlertClaimService claimService;           // D1 薄适配器，CAS 单一来源

    @Tool(description = "认领一条已诊断且未被他人认领的告警（负责人先到先得、锁定到处理结束）")
    public String claimAlert(@ToolParam String alertName,
                             @ToolParam(required = false) String operator /* D4/O1 身份来源未定 */) {
        try {
            AlertView v = claimService.claim(alertName, resolveOperator(operator));
            return toModelText(v);                          // D5 ≤500 字符，只回可读文案
        } catch (AlertClaimException e) {
            return ErrorText.of(e.getCode(), e.getMessage());   // §2 映射，不抛（LLM 工具容错）
        }
    }
}
```

> ⚠️ `@ToolLevel` 注解当前**不存在于代码**（执行文档 §5.2 草稿）。本设计若转正，需先落地该注解 + 拦截器（读注解决定是否放行），否则 D2/D3 无执行载体——这本身就是未来切片的第一个 Task。

## 4. 测试清单（只测"翻译层"）

不重复 service 已测的 CAS 并发语义（那些归 `AlertClaimServiceTest`/`ConcurrencyTest` 管）——**测试职责也只有一个家**。

| 测什么 | 替身 | 断言 |
|---|---|---|
| 成功 → 返回 IN_PROGRESS 视图 | Mockito 替 `AlertClaimService.claim` 返 AlertView | 文案含 alertName / `IN_PROGRESS` / 负责人 |
| 40401/40901/40902/40903 | service 抛对应 `AlertClaimException` | 返回对应 message，**不抛异常** |
| 未知异常 | service 抛 RuntimeException | 返回通用错误文案，不抛 |
| `@ToolLevel(Level.WRITE)` 元数据 | 反射读注解 | 分级合规 |
| `@ConditionalOnProperty` 缺席 | — | 默认不注册（上下文无该 bean） |

## 5. 开放项（记债、不假装解决）

- **O1**（D4）：operator 身份绑定来源。候选：单 operator 值班会话（从会话绑）/ 未来账号体系注入；落地前不得让模型自报 operator。
- **O2**（D6）：工具级 agent 溯源审计（哪个 agent/会话触发了认领）是否需要——如需，独立 tool-call audit，不影响 claim_events。

## 6. 范围边界（此设计不做）

- 不建 `ToolRegistry`/统一注册入口（P 债，还执行文档 §5.2）。
- 不做工具级审计表（O2）。
- 不在 chat 会话引入账号体系（与 spec Assumptions 冒名局限同源，V1 信任自报）。
- 不实现 `@ToolLevel` 注解本体（见 §3 ⚠️）与拦截器。
