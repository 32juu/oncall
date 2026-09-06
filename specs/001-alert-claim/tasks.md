# Tasks: 告警认领与处置（Alert Claim & Disposition）

**Input**: Design documents from `/specs/001-alert-claim/`

**Prerequisites**: plan.md（required）、spec.md（user stories）、research.md、data-model.md、contracts/api.md、quickstart.md

**Tests**: 本特性按项目宪法「测试必带」+ plan §6 显式要求测试，US1 内含 TDD 优先的测试任务。

**Organization**: 任务按 User Story 组织。**V1 只实现 US1（P1：认领+归属+状态+持久化，FR-001~006）**；US2 抑制窗口（P2）与 US3 处置记录（P3）不在 V1 范围，仅契约预留，**本清单不为其展开任务阶段**。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（仅 US1 阶段有）
- 描述含精确文件路径

## 路径约定

- 生产代码：`src/main/java/org/example/claim/...`（base package `org.example`）
- 测试代码：`src/test/java/org/example/claim/...`（新增，仓库当前无 src/test）
- 根配置：`pom.xml`、`src/main/resources/application.yml`、`src/test/resources/application.yml`

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 项目初始化与基础结构

- [ ] T001 添加持久化与测试依赖到 `pom.xml`：`spring-boot-starter-data-jpa`、`com.mysql:mysql-connector-j`(runtime)、`com.h2database:h2`(test)、`spring-boot-starter-test`(test)
- [ ] T002 [P] 在 `src/main/resources/application.yml` 配置 MySQL 数据源（`jdbc:mysql://localhost:3306/superbiz`，账号走 `${MYSQL_USER:sba}`/`${MYSQL_PASSWORD:sba}`）与 `spring.jpa`（`ddl-auto: update`、`open-in-view: false`）；确保开发默认 `prometheus.mock-enabled: true`
- [ ] T003 [P] 新增 `src/test/resources/application.yml`：数据源指向内存 H2 并 `MODE=MySQL`（供 @DataJpaTest 用；其余继承主配置）

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**: US1 开始前必须完成的共享构件

**CRITICAL**: 本阶段未完成前不开始任何 US 任务

- [ ] T004 [P] 创建状态枚举 `AlertStatus`（DIAGNOSED/IN_PROGRESS/RESOLVED/CLOSED）于 `src/main/java/org/example/claim/entity/AlertStatus.java`
- [ ] T005 [P] 创建共享响应信封 `ApiResponse<T>{code,message,data}` 于 `src/main/java/org/example/claim/dto/ApiResponse.java`（成功 code=200，含 `success(data)`/`error(code,message)` 工厂）
- [ ] T006 [P] 创建错误码枚举 `ErrorCode`（200/40001/40002/40401/40901/40902/40903/50000 + 对应 message）于 `src/main/java/org/example/claim/dto/ErrorCode.java`
- [ ] T007 [P] 创建运行时异常 `AlertClaimException`（携带 `ErrorCode`）于 `src/main/java/org/example/claim/dto/AlertClaimException.java`

**Checkpoint**: 基础构件就绪，可开始 US1。

---

## Phase 3: User Story 1 - 值班 SRE 认领一条已诊断告警（Priority: P1）★ MVP

**Goal**: 值班 SRE 认领一条已被 AIOps 诊断（DIAGNOSED）的告警；认领后告警进入 IN_PROGRESS、记录负责人与时间、对同班可见；他人/本人二次认领被拒并提示；数据持久化，重启后可查。

**Independent Test**: 对一条未认领的 DIAGNOSED 告警，operator A 认领成功 → operator B 认领同一告警被拒且看到负责人 A → （进程重启后）GET 仍显示 IN_PROGRESS + A。

### Tests for User Story 1（TDD：先写、先红，再实现）

> 测试矩阵来源：plan.md §6 / spec Acceptance / 宪法「测试必带」。

- [ ] T008 [P] [US1] 写 `AlertRepositoryTest`（@DataJpaTest）于 `src/test/java/org/example/claim/repository/AlertRepositoryTest.java`：断言 `claimIfDiagnosed` 对 DIAGNOSED 返回 1、对 IN_PROGRESS/RESOLVED/CLOSED/不存在返回 0；upsert-seed 幂等
- [ ] T009 [P] [US1] 写 `AlertClaimServiceTest`（Mockito 单测）于 `src/test/java/org/example/claim/service/AlertClaimServiceTest.java`：认领成功 / 他人占用→40901 / 本人重复→40902 / 已结束→40903 / 不存在或未诊断→40401 / 空参→400
- [ ] T010 [P] [US1] 写 `AlertClaimControllerTest`（@WebMvcTest）于 `src/test/java/org/example/claim/controller/AlertClaimControllerTest.java`：4 端点 HTTP 状态码与信封；blank operator/alertName → 400
- [ ] T011 [P] [US1] 写 `AlertDiagnosisRecorderTest`（@DataJpaTest + mock QueryMetricsTools）于 `src/test/java/org/example/claim/service/AlertDiagnosisRecorderTest.java`：从 feed JSON 幂等 upsert 出 DIAGNOSED；对已 IN_PROGRESS 的行**不降级**
- [ ] T012 [US1] 写 `AlertClaimConcurrencyIT` 于 `src/test/java/org/example/claim/AlertClaimConcurrencyIT.java`：2 线程并发认领同一 DIAGNOSED 告警 → 恰 1 成功 + 1 个 409、无双主（在 H2-MySQL 替身上断言；真库复验见 quickstart §Step F）

### Implementation for User Story 1

- [ ] T013 [P] [US1] 创建 `Alert` 实体（表 `alerts`：PK `alert_name`；status/claimed_by/claimed_at/last_diagnosed_at/created_at/updated_at）于 `src/main/java/org/example/claim/entity/Alert.java`
- [ ] T014 [P] [US1] 创建 `ClaimEvent` 实体（表 `claim_events`：id 自增 PK、FK→alerts、operator、event_type、created_at）于 `src/main/java/org/example/claim/entity/ClaimEvent.java`
- [ ] T015 [P] [US1] 创建请求/响应 DTO：`ClaimRequest{operator}`、`AlertView`（alertName/status/claimedBy/claimedAt/lastDiagnosedAt）于 `src/main/java/org/example/claim/dto/`（ClaimRequest.java、AlertView.java）
- [ ] T016 [P] [US1] 创建 `AlertRepository` 于 `src/main/java/org/example/claim/repository/AlertRepository.java`：`findById`、`findByStatus`、`@Modifying(clearAutomatically=true) claimIfDiagnosed(alertName, operator, now)`（`UPDATE alerts SET status='IN_PROGRESS',claimed_by=:operator,claimed_at=:now,updated_at=:now WHERE alert_name=:name AND status='DIAGNOSED'`，返回 int）、upsert/recordDiagnosis 辅助（插缺失行 DIAGNOSED + 刷新 lastDiagnosedAt，**不改已有行 status/claimed**）
- [ ] T017 [P] [US1] 创建 `ClaimEventRepository` 于 `src/main/java/org/example/claim/repository/ClaimEventRepository.java`：`save`、`findByAlertNameOrderByCreatedAtAsc`
- [ ] T018 [US1] 实现 `AlertClaimService` 于 `src/main/java/org/example/claim/service/AlertClaimService.java`（`@Service`，依赖 T013-T017）：入参校验→`@Transactional` 内调 `claimIfDiagnosed`；返回 1 则存 CLAIM 事件并回读视图；返回 0 则按 re-read 映射 40401/40901/40902/40903 抛 `AlertClaimException`
- [ ] T019 [US1] 实现 `AlertDiagnosisRecorder` 于 `src/main/java/org/example/claim/service/AlertDiagnosisRecorder.java`：注入现有 `QueryMetricsTools`，调用 `queryPrometheusAlerts()`，用 ObjectMapper JsonNode 解析 `alerts[].alert_name`（解析失败则视为空集），对每个名走 `AlertRepository.recordDiagnosis`（幂等、绝不降级）
- [ ] T020 [US1] 接线：在 `src/main/java/org/example/controller/ChatController.java` 的 `aiOps()` 中，注入 `AlertDiagnosisRecorder` 并在调用 supervisor **之前**执行一次记录（R4 接缝；约 +2 行，不改诊断引擎）
- [ ] T021 [US1] 实现 `AlertClaimController`（`@RestController`）于 `src/main/java/org/example/claim/controller/AlertClaimController.java`：`POST /api/alerts/{alertName}/claim`、`GET /api/alerts/{alertName}`、`GET /api/alerts?status=`、`GET /api/alerts/{alertName}/events`；`@ExceptionHandler(AlertClaimException)` 统一转 HTTP 状态码与信封

**Checkpoint**: US1 独立可用。执行 `mvn test` 全绿（免 Docker）；quickstart §Step B–D 手动通过即达 MVP。

---

## Phase 4: Polish & 跨切面

**Purpose**: 收尾与文档一致性（US1 完成后的清理）

- [ ] T022 [P] 更新 `CLAUDE.md`：登记 claim 端点与表、开发 MySQL 启动命令（`docker run mysql:8`）、约定「未跑 ai_ops 认领告警得 40401」
- [ ] T023 按 `specs/001-alert-claim/quickstart.md` 跑通验证：起 MySQL 容器 → `mvn spring-boot:run` → Step A–F（含真库并发复验与跨进程重启 Step E）；确认 `mvn verify` 通过

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup（Phase 1）**: 无依赖，可先做（T001 须先于依赖生效，T002/T003 可与其后并行）
- **Foundational（Phase 2）**: 依赖 Setup 完成；**阻塞所有 US**
- **US1（Phase 3）**: 依赖 Phase 1+2 完成
- **Polish（Phase 4）**: 依赖 US1 完成

### User Story Dependencies（V1 内）

- **US1（P1）**: Phase 1+2 完成后即可开始；无其它 US 依赖
- **US2/US3**: 不在 V1（见文首说明）

### Within US1

- Tests 先写、先红（T008-T012），再实现（T013-T021），最后全绿
- 实体/仓库（T013-T017）先行 → Service（T018/T019）→ 接线/Controller（T020/T021）
- US1 完成并独立验证后才进 Polish

### Parallel Opportunities

- Phase 1：T002、T003 并行（T001 之后）
- Phase 2：T004-T007 全并行
- US1 测试：T008-T011 并行；T012 依赖实现落地
- US1 实现：T013/T014/T015 并行；T016/T017 并行（依赖 T013/T014 实体）；T018 依赖仓库；T019 依赖 T016；T020 依赖 T019；T021 依赖 T018/T015

---

## Parallel Example: User Story 1

```bash
# 一起写实体/DTO：
Task: "Create Alert entity .../claim/entity/Alert.java"
Task: "Create ClaimEvent entity .../claim/entity/ClaimEvent.java"
Task: "Create ClaimRequest/AlertView .../claim/dto/"

# 一起写仓库（实体就绪后）：
Task: "Create AlertRepository .../claim/repository/AlertRepository.java"
Task: "Create ClaimEventRepository .../claim/repository/ClaimEventRepository.java"

# 一起写测试（TDD 红）：
Task: "AlertRepositoryTest / AlertClaimServiceTest / AlertClaimControllerTest / AlertDiagnosisRecorderTest"
```

---

## Implementation Strategy

### MVP First（= US1，V1 目标）

1. 完成 Phase 1：Setup
2. 完成 Phase 2：Foundational（阻塞，必须先）
3. 完成 Phase 3：US1（T008-T021）
4. **STOP & VALIDATE**：`mvn test` 全绿 + quickstart Step B–E 通过
5. 演示/提交（每完成一个相对完整子步骤即提交，遵循 Git 规范）

### 增量交付（V1 之后）

1. 本 V1 交付 US1（MVP）→ 独立验证 → 演示
2. US2（抑制窗口，P2）、US3（处置记录，P3）为新 feature/后续切片，回到 `/speckit-specify` 或同 feature 扩展阶段，不在此 V1 展开

---

## Notes

- [P] 任务 = 不同文件、无未完成依赖
- [Story] 标签仅在 US1 阶段使用（V1 单故事）
- 每条任务可独立执行；测试约定遵循 `CLAUDE.md` 与项目宪法（`.specify/memory/constitution.md`）：新增测试走 `XxxTest` 命名、标准 Maven 布局 `src/test/java/...`、跑 `mvn test` 验证、不依赖 DashScope/Prometheus/Milvus 实连
- 提交纪律：每完成一个相对完整的子步骤（如 Phase 2 完成、US1 测试绿）即按 Conventional Commits 提交一次，禁止攒批
