# 验收就绪自查 Checklist：本切片（US1 · 告警认领闭环）

**Purpose**: 以「验收就绪」为视角，对本切片（V1 = US1，P1 认领闭环）的需求文档做一次 Reviewer-owned 自查：每条验收场景是否写得**可测**且**可追溯到证据落点**；边界/状态机/范围的口径是否完整、清晰、一致；并把尚未实跑的真库/真 LLM 复验显式列为「已知验证债」供验收人知情自决。审的是 spec/plan/tasks 这层「英文」写得好不好，**不是**代码跑没跑对。

**Created**: 2026-09-07
**Feature**: [spec.md](../spec.md) · [plan.md](../plan.md) · [tasks.md](../tasks.md) · [contracts/api.md](../contracts/api.md)

**Note**: 自定义清单，由 `/speckit-checklist` 基于 feature 上下文生成（定位经用户拍板 = 混合·验收就绪视角）。
**Review Ownership**: reviewer-owned 需求质量自查产物。`[x]` 仅当 reviewer（你，学习者/验收人）判定该条准则满足时勾选。
**Marker Semantics**: `[x]` = 该条「验收就绪/需求质量」准则已审阅并满足；≠ 实现工作已完成。§7「已知验证债」内的条目，请在你**补跑或显式接受**后再勾。

## 1. 验收场景就绪度：US1 五条主场景（主轴）

- [ ] CHK001 US1-1「认领成功 → 处理中 + 负责人/时间被记录」的判据是否客观可测，并有明确证据落点（ClaimService happy path + repo 条件更新返回 1 行）？[Acceptance Quality + Traceability, Spec §US1-AC1, plan §6]
- [ ] CHK002 US1-2「二次认领被拒且被告知当前负责人 A」是否以可判定口径表述（拒绝 + 携 owner），并映射到自动化断言？[Acceptance Quality + Traceability, Spec §US1-AC2, plan §6]
- [ ] CHK003 US1-3「同班立即可见负责人与认领时间」——「立即可见」的验收载体（查单条/列表的响应含负责人字段）是否在契约层明确到可判定？[Clarity, Spec §US1-AC3, contracts/api.md]
- [ ] CHK004 US1-4「重启后仍完整可查」——真·跨进程重启只能靠真 MySQL 手动证（quickstart Step E），该判据的证据边界是否在文档写清（H2 替身自动化不能证跨进程）？[Traceability + Gap, Spec §US1-AC4, quickstart §2-E]
- [ ] CHK005 US1-5「本人重复认领不产生二主」与 US1-2 的差异（他人占用 vs 本人重复）是否在需求/契约中被显式区分为不同提示？[Consistency, Spec §US1-AC5, contracts/api.md 错误码 40901/40902]
- [ ] CHK006 五条验收场景是否逐条映射到 plan §6 测试矩阵（无孤儿验收、无未被验收覆盖的 FR-001~006）？[Traceability, plan §6]

## 2. 边界与异常场景覆盖（Exception / Edge / Alternate）

- [ ] CHK007 「不存在 / 未诊断 / 已结束」三类不可认领是否全部被需求覆盖（FR-006 + Edge Cases），每类有对应守卫分支与错误语义？[Coverage, Spec §Edge, §FR-006]
- [ ] CHK008 「不存在」与「未诊断」合并为单一用户提示（40401）是显式决策并文档化，而非疏漏？[Clarity, contracts/api.md, quickstart §5]
- [ ] CHK009 并发双认领「恰一人成功、无双主」作为必守不变量，其语义是否跨 spec（Edge）/plan（D2）/data-model 一致表述？[Consistency, Spec §Edge, plan §1-D2, data-model §4]
- [ ] CHK010 空操作人标识被拒（400）是否在需求与契约层写清（blank operator）？[Completeness, Spec §Edge, contracts/api.md]
- [ ] CHK011 冒名认领（A 冒用 B 的标识）的已知局限是否在 Assumptions 显式记录，并被验收人知情接受为 v1 非缺陷？[Assumption, Spec §Assumptions]
- [ ] CHK012 「处理中再触发且被诊断为更高严重级别 → 抑制不自动取消」的裁决是否已由 plan 的开放项裁决落地（而非悬在 spec 待细化）？[Consistency, Spec §Edge, plan §1]
- [ ] CHK013 认领写路径的原子性（状态更新 + CLAIM 事件追加同事务）与失败回滚语义是否在 plan 中明确，无「状态变了但事件没记」的半成功缺口？[Coverage/Gap, plan §5, data-model]

## 3. 状态机与词汇一致性（跨文档）

- [ ] CHK014 状态词汇跨文档是否讲清：spec「已诊断/处理中/已解决」、Assumptions「未诊断/处理中/已结束」、实体枚举 DIAGNOSED/IN_PROGRESS/RESOLVED/CLOSED 三者关系（尤其「未诊断」= 行不存在 vs DIAGNOSED 行的歧义）？[Consistency/Ambiguity, Spec §Key Entities vs §Assumptions, data-model]
- [ ] CHK015 V1 唯一合法迁移 DIAGNOSED→IN_PROGRESS 是否显式声明，其余迁移为非法并被谓词拦截？[Clarity, plan §2.1]
- [ ] CHK016 RESOLVED/CLOSED 为 P3 预留、V1 无路径进入——该「预留接缝」是否文档化为有意未建，而非可触达的死分支？[Completeness, plan §2.1, tasks 文首]
- [ ] CHK017 「重复诊断不撤销认领 / recorder 绝不降级」与状态机图表述一致（无 IN_PROGRESS→DIAGNOSED 迁移）？[Consistency, plan §2.1, research R4]

## 4. 成功判据可测量性（SC-001~004）

- [ ] CHK018 SC-001「100% 认领成功」在确定性测试语境下是否被解释为可判定口径（用例全覆盖 vs 采样），避免不可测的百分比表述？[Measurability, Spec §SC-001]
- [ ] CHK019 SC-002「二次认领 100% 被拒并示负责人」可否由客观判据判定（拒绝响应 + 携 owner 字段）？[Measurability, Spec §SC-002]
- [ ] CHK020 SC-003「重启零丢失」的权威证据仅真 MySQL 跨进程可证——其验收口径是否与 US1-4（CHK004）一致并已归入已知债（§7）？[Consistency, Spec §SC-003, quickstart §2-E]
- [ ] CHK021 SC-004「一次查询内确定负责人」落到哪个查询契约（GET 单条/列表），判据是否可判定为「响应含负责人」？[Measurability, Spec §SC-004, contracts/api.md]

## 5. 范围纪律与预留接缝（P2/P3 / Out-of-Scope）

- [ ] CHK022 US2（抑制,P2）、US3（处置,P3）未实现，是否在 spec 优先级、plan Scope、tasks 文首三处口径一致（无一处暗示已交付）？[Consistency, Spec §US2/US3, plan §Scope, tasks 文首]
- [ ] CHK023 Out of Scope（自愈执行/真实数据源/RBAC/改诊断引擎/复盘导出/改派）是否逐项显式、未混入 V1 交付口径？[Coverage, Spec §范围边界]
- [ ] CHK024 FR-007~011（P2/P3）与 FR-001~006（P1）的优先级标注（含 SC-005/006 带 P 标）在 spec 内是否一致、无「幽灵 P1」？[Consistency, Spec §FR/SC]
- [ ] CHK025 「改派/移交不做、锁定到处理结束」是否在需求（FR-004）与状态机层面一致约束（V1 无变更负责人路径）？[Consistency, Spec §FR-004, §范围边界]

## 6. 非功能与约束在需求层的体现

- [ ] CHK026 持久性（FR-005）在 spec 层是否以用户可感语言表达（重启后仍可查），而非技术耦合（MySQL/JPA）？[Non-Functional, Spec §FR-005]
- [ ] CHK027 并发正确性（恰一人成功）作为唯一显式正确性目标——「无吞吐指标」的取舍是否在 plan 显式声明？[Non-Functional, plan §Technical Context]
- [ ] CHK028 数据源选择（开发/生产同引擎 MySQL）与测试替身（H2-MySQL）的语义差异，是否作为已知风险在 plan 记录并有缓释路径（真库复验）？[Dependency/Risk, plan §7-O2]
- [ ] CHK029 测试约定（切片测试无实连、提交门）是否与 CLAUDE.md / 宪法对齐，需求文档未引入违背项？[Consistency, constitution III, CLAUDE.md 测试约定]

## 7. 已知验证债 / 验收边界（不阻塞 · 知情自决）

- [ ] CHK030 真 MySQL 并发复验（quickstart Step F，权威断言「恰一人」）未实跑——是否已知情，并自决「补跑」或「接受替身证据」？[已知债, quickstart §2-F]
- [ ] CHK031 真 MySQL 跨进程重启（quickstart Step E，SC-003/US1-4 的权威证据）未实跑——是否知情自决？[已知债, quickstart §2-E]
- [ ] CHK032 真 LLM 诊断链路（quickstart Step A，AIOps 打 DIAGNOSED 前置）未实跑（缺真实 DASHSCOPE key）——由此需意识到「未跑 ai_ops 认领得 40401 是 FR-006 特性而非 bug」？[已知债 + Assumption, quickstart §5]
- [ ] CHK033 收尾任务 T023（按 quickstart 全量手动跑通 + `mvn verify` 提交门）未执行——是否知情其对「完整验收证据」的影响？[已知债, tasks T023, CLAUDE.md 测试约定]
- [ ] CHK034 除上述外，是否还有其它被你搁置/延后的验证项（如演示页、性能目标、非 P1 场景）需要在此显式列债，避免「验收时遗忘」？[已知债, Gap]

## Notes

- 定位（用户拍板）：混合·验收就绪视角 —— 主轴是「验收场景写得可测 + 可追溯到测试/手动落点」，需求质量维度作旁注；§7 把未实跑的真库/真 LLM 复验列为**不阻塞**的已知债。
- 勾选语义：`[x]` = 你判定该条「验收就绪 / 需求质量」准则满足，**不是**代码已通过测试。对照证据时看 plan §6 测试矩阵、contracts/api.md、quickstart.md，而非直接运行验证。
- §7 为「知情自决」区：任一条若你想让它阻塞验收，请先补跑 quickstart 对应步骤，再回来勾选。
- 只读、不要勾选本文件里已内置的 spec 质量清单 [requirements.md](requirements.md) 的条目（那是 `/speckit-specify` 维护的生命周期，与本文互不覆盖）。
- 本清单审的是需求文档本身（完整/清晰/一致/可测/覆盖/边界）；对 CHK 的逐条评估，若需协助可由 reviewer 显式要求，否则勾选权在 reviewer。
