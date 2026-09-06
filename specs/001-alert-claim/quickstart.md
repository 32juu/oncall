# Quickstart: 认领闭环 端到端验证向导

**Branch**: `001-alert-claim` | **Date**: 2026-09-06

这是**运行/验证向导**（不是实现代码）。契约见 [contracts/api.md](contracts/api.md)，数据模型见 [data-model.md](data-model.md)，测试清单见 [plan.md](plan.md#6-测试计划)。

## 0. 前提

- JDK 17 + Maven。
- **数据源 = MySQL 8**（开发/生产同一引擎）。开发库一条命令起（数据落 volume，重启不丢）：
  ```bash
  docker run --name sba-mysql \
    -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=superbiz \
    -e MYSQL_USER=sba -e MYSQL_PASSWORD=sba \
    -p 3306:3306 -d mysql:8 \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
  # 若你要清空重来：docker rm -f sba-mysql 再跑一次；保留 volume 数据即持久
  ```
  （账号可用环境变量覆盖：`MYSQL_USER`/`MYSQL_PASSWORD`/`MYSQL_URL`。）
- DashScope key：`DASHSCOPE_API_KEY` 需非空（`VectorEmbeddingService` 启动即 fail-fast）。跑完整「诊断→认领」需**真实可用** key（AIOps 走 LLM）；仅验证 claim 行为可只设占位 + 依赖测试。
- mock 告警：`prometheus.mock-enabled: true`（3 条固定告警，无需真 Prometheus）。

## 1. 自动化验收（免 Docker，首选）

```bash
mvn test
```

测试在 **H2(MODE=MySQL) 嵌入式替身**上跑（test-scope H2），无需 MySQL 在跑。覆盖 spec 全部 Acceptance Scenarios 的清单见 plan.md §6。

## 2. 手动端到端（HTTP，需 MySQL 已起）

```bash
mvn spring-boot:run        # 连 MySQL(3306) + 自动建表 ddl-auto=update；端口 9900
```

**Step A — 诊断并记录（必须先跑，否则认领得 404）**

```bash
# 触发一次 AIOps（走 supervisor+LLM，需真实 key；成功后给 feed 中 3 条告警打上 DIAGNOSED）
curl -N -X POST http://localhost:9900/api/ai_ops
```

> 当前不想调 LLM 可跳过 Step A，改走 §3 的纯行为演示——此时认领任意未诊断告警返回 40401，恰是 FR-006 的预期展示。

**Step B — 认领**

```bash
curl -s -X POST http://localhost:9900/api/alerts/HighCPUUsage/claim \
  -H 'Content-Type: application/json' -d '{"operator":"sre-alice"}'
# → 200, status=IN_PROGRESS, claimedBy=sre-alice
```

**Step C — 负责人可见性（FR-003）**

```bash
curl -s http://localhost:9900/api/alerts/HighCPUUsage        # → claimedBy=sre-alice, IN_PROGRESS
curl -s "http://localhost:9900/api/alerts?status=IN_PROGRESS" # → 列表含 HighCPUUsage
```

**Step D — 重复认领被拒（FR-004 / FR-006）**

```bash
curl -s -X POST http://localhost:9900/api/alerts/HighCPUUsage/claim -H 'Content-Type: application/json' -d '{"operator":"sre-bob"}'
# → 409 code=40901，提示已被 sre-alice 负责
curl -s -X POST http://localhost:9900/api/alerts/HighCPUUsage/claim -H 'Content-Type: application/json' -d '{"operator":"sre-alice"}'
# → 409 code=40902，已由您负责
curl -s -X POST http://localhost:9900/api/alerts/NoSuchAlert/claim -H 'Content-Type: application/json' -d '{"operator":"sre-alice"}'
# → 404 code=40401，不存在或未诊断
```

**Step E — 重启不丢（FR-005）**

```bash
# Ctrl+C 停应用 → mvn spring-boot:run 重启
curl -s http://localhost:9900/api/alerts/HighCPUUsage
# → 仍是 IN_PROGRESS + sre-alice（MySQL 数据在容器 volume，跨应用重启存活）
```

**Step F — 真库并发复验「恰一人成功」（权威断言）**

测试替身已证条件更新的 1/0 语义；这一步在**真 MySQL** 上目击并发收敛：

```bash
# 先让 HighMemoryUsage 处于 DIAGNOSED（跑过 Step A，或重开一个）
# 同时并发两个认领：
(curl -s -X POST http://localhost:9900/api/alerts/HighMemoryUsage/claim -H 'Content-Type: application/json' -d '{"operator":"sre-alice"}' &) \
(curl -s -X POST http://localhost:9900/api/alerts/HighMemoryUsage/claim -H 'Content-Type: application/json' -d '{"operator":"sre-bob"}' &) ; wait
# 期望：恰一个 200 + 一个 409（无论谁赢，不会两个都 200、不会出现双负责人）
curl -s http://localhost:9900/api/alerts/HighMemoryUsage   # 复查：claimedBy 只有一个
```

## 3. 纯 claim 行为演示（不调 LLM）

claim 的验收逻辑（409/404/并发收敛）由 `mvn test` 直接证明，不依赖 LLM。手动复现时认领一个**未诊断**名看 40401 即可验证守卫（§D），不必真的跑 AIOps。

## 4. 预期结果对照

| 验收（spec） | 演示/测试落点 |
|---|---|
| US1-1 认领成功、状态/归属记录 | Step B；ClaimServiceTest happy path |
| US1-2 二次认领被拒并示负责人 | Step D；repo 条件更新 0 行断言 |
| US1-3 负责人立即可见 | Step C；GET 单条/列表 |
| US1-4 重启后仍可查 | Step E（真 MySQL 跨重启） |
| US1-5 本人重复认领不产生二主 | Step D；40902 |
| 边界：不存在/未诊断/已结束认领被拒 | Step D；40401/40903 测试 |
| 边界：并发认领恰一人成功 | Step F（真库）+ ConcurrencyIT（替身） |

## 5. 常见坑

- **MySQL 未起/账号不对** → 启动即连库失败：先 `docker ps` 看 `sba-mysql` 是否 Up；URL/账号见 §0。
- **删容器卷 = 清库**：`docker rm -f sba-mysql` 不带 volume 参数会丢数据；要保数据别删卷。
- **未跑 ai_ops 就认领 → 40401**：FR-006 特性，非 bug；认领对象必须是 recorder 打上 DIAGNOSED 的告警。
- **`ddl-auto=update` 只用于开发/演示**：生产形态 `validate` + 正式迁移属后续（plan O4）。
- **换真实 fingerprint 作键**：V1 以 alert_name 为键，接真实 Prometheus 时换键来源、表结构不变（research R3）。
