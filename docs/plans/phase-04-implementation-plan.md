# 阶段四实现计划

> **面向 AI 代理的工作者：** 本计划按步骤执行；每个行为先新增失败测试，再实现并验证。保持当前工作区未提交的需求文档、README 索引与 AGENTS.md 决策记录。

**目标：** 为 Java Agent 增加可持久化、可比较的评测实验生命周期，并保持真实 Provider 显式启用、CI 使用 Fake。

**架构：** 版本化数据集由评测服务校验和指纹化；现有 Runner 执行 case 并报告增量结果；确定性 Evaluator 为 case 生成版本化评分；EvaluationRepository 以 PostgreSQL/Flyway 保存实验与 case 状态；CLI 查询、比较和导出。OpenTelemetry 沿用现有 run/trace，并加入评测关联标识。

**技术栈：** Java 21、Spring Boot、Spring JDBC、PostgreSQL/Flyway、Jackson、JUnit 5、Testcontainers、OpenTelemetry。

> **执行状态：** 实现已进入最终验收。当前工作区已落地版本化 Dataset Snapshot、内置 Evaluator、PostgreSQL 仓储/租约恢复、比较/导出与 CLI；最终阶段结论以 `docs/stages/phase-04-summary.html` 和最新验证结果为准。

---

## 文件职责规划

- 修改 `src/main/java/com/example/agent/evaluation/AgentEvaluationDatasetLoader.java`：读取格式版本、数据集 ID 和源文件 SHA-256，并在执行前验证整个数据集。
- 新增 `AgentEvaluationDatasetSnapshot.java`、`EvaluationEvaluator.java`、`EvaluationScore.java`、内置 Evaluator 实现：表达数据集身份与可比较的确定性评分。
- 新增 `EvaluationRunRecord.java`、`EvaluationCaseRecord.java`、`EvaluationRunStatus.java`、`EvaluationCaseStatus.java`：持久化领域状态和值。
- 新增 `EvaluationRepository.java` 与 `PostgresEvaluationRepository.java`：数据库接口和 JDBC 实现。
- 修改 `AgentEvaluationRunner.java`、`AgentEvaluationResult.java`、`AgentEvaluationCaseResult.java`：支持增量 case 回调、评测关联信息和不包含回答正文的报告导出。
- 新增 `EvaluationService.java`、`EvaluationComparator.java`、`EvaluationJsonExporter.java`：编排、兼容性检查、比较和脱敏导出。
- 修改 `AgentConfiguration.java`、`Main.java`、`AgentProperties.java`、`application.yml`：依赖注入、`/eval` 命令和评测配置。
- 新增 `src/main/resources/db/migration/V7__create_evaluation_store.sql`：`evaluation_run`、`evaluation_case`、状态事件表、执行租约/栅栏与查询索引。
- 新增/修改 `AgentEvaluationDatasetLoaderTest`、`EvaluatorTest`、`EvaluationServiceTest`、`EvaluationComparatorTest`、`EvaluationReportExporterTest`、`PostgresEvaluationRepositoryTest`、`MainTest`。
- 更新 `.github/workflows/ci.yml`（仅在需要为评测数据验证增加显式步骤时）；不得加入真实 Provider 调用或读取 Provider 密钥。
- 最终更新 `README.md`、`docs/architecture/enterprise-migration-roadmap.md` 和验收通过后的 `docs/stages/phase-04-summary.html`。

## 任务 1：数据集身份与加载前校验

1. 为 loader 测试添加：稳定默认 dataset ID、格式版本读取、文件 SHA-256 可重复、重复 case/非法 evaluator/预算拒绝。
2. 用 `mvn -Dtest=AgentEvaluationDatasetLoaderTest test` 确认新行为测试因缺少 API 或行为而失败。
3. 实现 snapshot 和加载校验；保持 v1/v2 JSON 既有字段和用例语义可读。
4. 重跑 loader 测试，预期 PASS；同时运行已有 `AgentEvaluationRunnerTest`。

## 任务 2：确定性 Evaluator 与脱敏报告

1. 测试成功状态、工具选择、路由/Worker 计划、回答短语、Token/调用预算等 Evaluator 的 PASS/FAIL/UNKNOWN/NOT_APPLICABLE。
2. 测试现有报告不再重复包含最终回答正文或工具参数，Token 未知保持 null。
3. 实现版本化 Evaluator 和最小 `EvaluationScore` 输出；评分原因使用稳定代码，不包含敏感正文。
4. 重跑 Evaluator 与 Runner 测试，预期 PASS。

## 任务 3：持久化领域模型与 PostgreSQL Repository

1. 使用 Testcontainers 测试：创建/读取实验、逐 case 幂等保存、非法状态迁移拒绝、不同实验并发、同一实验 claim 冲突、事务回滚和 Run 删除后的关联处理。
2. 对 `PostgresEvaluationRepositoryTest` 运行红灯，确认缺迁移/Repository 时失败。
3. 新增 Flyway V7：`agent_evaluation` 保存实验身份、配置白名单摘要、状态和汇总；`agent_evaluation_case` 保存 case 状态、评分/指标 JSONB、关联 Run ID；唯一 `(evaluation_id, case_id)`；FK 删除语义不导致历史结果意外丢失。
4. 实现 Repository，使用短事务、参数化 SQL、版本条件更新；相同结果重试不得重复累计计数。
5. 重跑 Postgres Repository 集成测试，预期 PASS；检查 Flyway 从空库迁移及既有 V1–V6 升级路径。

## 任务 4：评测服务、预算和中断语义

1. 通过 Fake Runner/Store 测试完整流程：调用前验证、写入 CREATED/RUNNING、逐 case 状态、总预算共享、部分失败后继续独立 case、完成状态与取消/Deadline。
2. 测试 RUNNING 实验 claim 互斥；模拟模型请求未知结果时恢复标记 INDETERMINATE 且不会再次调用模型。
3. 给 Runner 增加受控进度回调，Service 持久化启动和 case 结果；异常后保留已完成 case，并写出稳定失败类别。
4. 测试通过后重跑评测 Service 和 Runner 定向用例。

## 任务 5：兼容比较、查询、JSON 导出与 CLI

1. 先为 Comparator 测试相同 dataset hash/evaluator 版本/Workflow 的通过与回归，以及不兼容条件拒绝比较。
2. 为 JSON Export 测试最小字段、稳定排序、`UNKNOWN` 表达及不存在凭据/回答/工具参数正文。
3. 为 CLI 测试 `run/list/show/compare/export` 参数、真实 Provider 启用保护、未启用时模型调用次数为 0 和稳定错误信息。
4. 实现 CLI 与应用装配；Fake ModelClient 保持只在测试装配中使用，真实评测继续经过 `AGENT_EVAL_ENABLED=true`。
5. 运行上述定向测试并修复。

## 任务 6：全量验收、文档和阶段总结

1. 运行 `mvn --batch-mode --no-transfer-progress clean verify`，记录真实测试数、失败/跳过数及 PostgreSQL Testcontainers 是否实际执行。
2. 运行 `bash ops/tests/prometheus-alerts-test.sh`、`bash ops/tests/image-digests-test.sh`、`kubectl kustomize deploy/overlays/l5`、`git diff --check`；若需 L5 部署，先刷新资源快照并仅做有界验证。
3. 审核 CI 确认真实 Provider 请求和密钥未加入普通 workflow。
4. 更新 README 的阶段四评测使用说明和索引，更新企业路线图中的当前事实与实际决策。
5. 只有全范围验收通过后新增 `docs/stages/phase-04-summary.html`，包含实际架构分层图、数据流/状态流程图、验收事实和遗留风险；失败时不写“阶段完成”。
