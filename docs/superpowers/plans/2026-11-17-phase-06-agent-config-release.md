# 阶段六 Agent 配置发布与运行时切换实现计划

> **面向 AI 代理的工作者：** 本计划已由用户授权自动执行；逐项采用 TDD，不绕过主分支 PR 审批门禁。

**目标：** 为 MyAi 增加不可变 Agent 配置修订、与离线评测关联的发布门禁、原子激活/回滚，以及每个 Run 固定配置快照的动态加载能力。

**架构：** PostgreSQL 保存不可变配置修订、发布记录、环境激活指针和追加式发布事件；`AgentConfigurationService` 编排校验/门禁/发布动作。Run 创建时解析激活配置并把不可变快照写入现有检查点，Runtime 与协作 Workflow 只读取该快照，从而保证多实例和恢复时版本一致。运维入口先使用现有 CLI，不新增 HTTP API；身份认证尚未实现，因此用户级灰度不启用，生产激活仅可由部署侧已批准的访问控制保护。

**技术栈：** Java 21、Maven、Spring JDBC、PostgreSQL、Flyway、Jackson、JUnit 5、现有 OpenTelemetry 与评测仓库。

---

## 文件职责

- 创建 `src/main/java/com/example/agent/configuration/AgentConfigRevision.java`：规范化、不可变的 Agent 配置正文及内容哈希。
- 创建 `src/main/java/com/example/agent/configuration/AgentConfigRelease.java`、`ReleaseEvent.java`、`ActiveConfigAssignment.java`：发布、激活和审计领域类型。
- 创建 `src/main/java/com/example/agent/configuration/AgentConfigStore.java`、`PostgresAgentConfigStore.java`：存储边界和 PostgreSQL 事务实现。
- 创建 `src/main/java/com/example/agent/configuration/AgentConfigurationService.java`：候选创建、契约校验、评测门禁、激活、回滚、查询和差异。
- 创建 `src/main/java/com/example/agent/configuration/RunConfigurationSnapshot.java`、`ActiveConfigurationProvider.java`：Run 固定快照及新 Run 的激活版本解析。
- 修改 `src/main/java/com/example/agent/workflow/RunRecord.java` 和 Flyway 新迁移：检查点保存配置 Release ID、哈希和实际配置快照。
- 修改 `ModelClient`、`DeepSeekModelClient`、`AgentRuntime`、`CollaborativeWorkflow`、`RunSubmissionService`：将 Run 快照应用于提示词、模型设置、工具 allowlist 和 Worker 契约引用；执行端再次校验工具权限。
- 修改 `AgentConfiguration.java`、`Main.java`、`AgentProperties.java` 与 `application.yml`：组合新组件和 CLI 子命令，不改变旧交互方式。
- 创建 `src/test/java/com/example/agent/configuration/` 下的领域、Store、服务和并发门禁测试；扩展运行时、协作和恢复测试。
- 更新 `README.md` 与部署/迁移文档；仅在阶段验收通过后生成 `docs/stages/phase-06-summary.html`。

## 任务 1：配置修订规范化与领域不变量

- [x] 先为相同配置的稳定哈希、字段变更产生新哈希、拒绝 Secret 明文/无效字段编写单元测试。
- [x] 运行对应测试确认因领域类型缺失失败。
- [x] 实现不可变配置正文、规范 JSON 排序和 SHA-256 计算；Secret 只能出现引用字段。
- [x] 运行领域测试通过。

## 任务 2：PostgreSQL 发布存储与原子激活

- [x] 测试修订/发布不可覆盖、发布事件追加、单环境激活指针 compare-and-set、并发激活仅一个成功、失败事务不改变旧指针。
- [x] 新增 Flyway 迁移和 Store 接口；先运行新集成测试确认红灯。
- [x] 实现 `PostgresAgentConfigStore`，用事务与唯一约束/版本号保证不可变与原子更新；用 Testcontainers PostgreSQL 验证。
- [x] 运行配置 Store 集成测试通过。

## 任务 3：评测关联与发布门禁

- [x] 测试缺少评测、失败评测、不兼容数据集/Evaluator/模式、基线回归时拒绝发布；兼容且满足显式规则时允许发布。
- [x] 扩展评测元数据使 Evaluation 能标识运行配置哈希和规则版本；Fake 评测验证关联，不调用真实模型。
- [x] 实现兼容性比较和显式门禁结果持久化；不可比较时默认拒绝自动放行。
- [x] 运行评测与发布门禁测试通过。

## 任务 4：Run 配置快照与恢复一致性

- [x] 测试创建 Run 时固定 Release ID/哈希/配置正文；切换激活后新 Run 采用新版本，旧 Run 和恢复 Run 仍使用旧版本。
- [x] 将配置快照加到 RunRecord JSON 检查点，并添加数据库可查询投影字段/索引。
- [x] 在提交服务、同步 Runtime 与 CollaborativeWorkflow 创建入口解析激活配置；缺失/损坏/哈希不一致时拒绝新 Run。
- [x] 运行 Run Store、提交、恢复和协作测试通过。

## 任务 5：配置应用于 Harness 与执行权限

- [x] 测试模型请求收到 Run 快照中的系统提示词和模型参数；模型只能看见允许的工具，伪造未授权工具调用也被 Runtime 拒绝。
- [x] 扩展模型请求选项，保留原 `ModelClient` 假实现兼容；DeepSeek adapter 使用请求快照中的模型与非敏感参数。
- [x] 为单 Agent 和 Supervisor/Worker 的规划、工具执行、Worker 契约选择接入同一 Run 快照；恢复不读取新激活配置。
- [x] 运行 Runtime、DeepSeek 契约和 CollaborativeWorkflow 测试通过。

## 任务 6：受控 CLI、差异、历史、激活与回滚

- [x] 测试 CLI 参数解析和服务行为：配置修订列表/详情/差异、激活状态、Run 所用版本、发布事件、审批、激活和带原因回滚。
- [x] 实现 `config` CLI；命令拒绝打印密钥/Secret 值，发布说明和错误信息也不输出配置正文。
- [x] 灰度仅允许环境/实例级稳定分配；没有可信调用身份时拒绝用户/租户分桶。
- [x] 实现基于显式 Run 失败阈值、最小样本数、观察窗口、冷却时间的自动回滚；缺数据不做自动决策；通过 Fake 时钟和确定性 Run 记录测试。
- [x] 运行 CLI、灰度和回滚测试通过。

## 任务 7：观测、文档与全量验收

- [x] 测试发布事件/门禁审计中有 Release ID、哈希与动作结果，不出现 Secret 值；Release ID 不成为 Prometheus 高基数标签。
- [x] 为激活、拒绝、灰度切换、审批和自动回滚记录结构化持久事件。
- [x] 更新 README、环境变量、路线图、阶段六需求状态与迁移说明；记忆候选规划保持独立。
- [x] 按资源约束重查本机/Colima 可用资源，执行 `mvn clean verify`；不清理共享 Docker 数据。
- [x] 使用确定性 Fake/Stub 做回归；不读取真实密钥或调用真实模型。
- [x] 验收通过后生成阶段六 HTML 总结及实际分层架构图。

## 设计边界与已知限制

- Evaluation、Release、Revision 是不同实体，通过 ID 与配置哈希关联；阶段四历史数据不自动推断其使用了哪个阶段六配置。
- 未提供可信用户身份前，不实现用户/租户级灰度；只使用部署环境或实例级策略。
- 不在 Run 执行中热切换配置；每个 Run 的提示词、模型参数、工具权限和 Worker 契约引用均由持久化快照固定。
- 自动回滚仅依据预先配置、可从持久化 Run 状态读取的客观规则；样本不足或状态存储不可用时停止决策。
- 在线 Trace 评测仍是后续候选，不采集生产对话来执行阶段六门禁。
