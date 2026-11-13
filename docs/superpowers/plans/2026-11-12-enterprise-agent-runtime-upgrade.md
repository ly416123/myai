# 企业级 Agent Runtime 升级实施计划

> **面向 AI 代理的工作者：** 按任务顺序实施，每个行为变化先加失败测试，再写最小实现。保留工作区已有未提交改动，不重置或覆盖用户文件。

**目标：** 按企业级架构路线把现有单 JVM Agent Workflow 演进为可运行、可并发协调、可观测、可部署和可回归评测的模块化单体；短期仅提供 CLI/受控内部调用，不交付 HTTP API 或生产身份认证。

**架构：** 保留手写 Agent 与 Supervisor–Worker 控制循环，应用入口逐步采用 Spring Boot。PostgreSQL 作为唯一 Run 主数据源，RunStore 提供原子 claim/lease/fenced update；模型和工具调用在数据库事务之外，外部副作用保持显式不确定性。MQ 仅在验收容量或解耦需求后启用。

**技术栈：** Java 21、Maven、Spring Boot、PostgreSQL、Flyway、Micrometer、OpenTelemetry、JUnit 5、Testcontainers；Fake/Mock 只用于测试。

**进度记录（2026-11-12）：** 计划任务 1–6 的代码基线已落地：PostgreSQL/Flyway RunStore、父/子租约与 fencing、Spring Boot/CLI/Worker 入口、结构化日志及 Micrometer/OTel 埋点、Fake/Provider Stub/PostgreSQL 测试、容器/K3s 清单与 CI 初版。协作子任务支持独立数据库轮询 Worker；Run 级模型/工具预算有原子幂等预留；终态 Run 清理和 age 加密备份/隔离库恢复工具已实现。最近完整 Maven 验收 118 项通过、0 失败、0 错误、0 跳过；新增 PostgreSQL 独立存储实例接管父/子租约恢复测试，以及子任务 Worker 亚毫秒轮询周期拒绝测试，和 JSON Schema Draft 2020-12 嵌套、组合、本地引用和远程引用安全测试。应用镜像 Trivy 复扫为 0 个 HIGH/CRITICAL。仍未完成的企业交付包括：l5 部署和 Secret 管理/回滚演练（l5 非系统工作负载继续保持停止）、多进程/Pod 崩溃故障演练、工具端幂等/补偿/逐工具审计契约、备份保留与异地复制/密钥轮换及定期恢复演练、OTLP Collector 和告警接收端连通、远端 CI 实际门禁结果、真实模型评测与成本预算。HTTP API 与生产认证授权短期暂缓；Fake/Mock 仅在测试中使用。

---

## 文件与职责范围

- 修改 `pom.xml`：Spring Boot BOM/插件、PostgreSQL JDBC、Flyway、Micrometer/OTel、Logback JSON、Testcontainers 测试依赖。
- 修改 `src/main/java/com/example/agent/workflow/RunStore.java`：增加原子认领、租约续期及 fencing token 条件更新契约。
- 新增 `src/main/java/com/example/agent/workflow/PostgresRunStore.java` 和 `src/main/resources/db/migration/`：实现关系化 Run/步骤/消息/子任务持久化与迁移。
- 修改 `AgentRuntime`、`CollaborativeWorkflow`：只通过安全存储契约推进状态，并在过期租约下拒绝写回。
- 新增 `src/main/java/com/example/agent/app/` 与配置类：将依赖组装从 CLI 分开，提供服务边界与受控启动，不引入 HTTP API/生产认证。
- 新增日志、指标与追踪配置：结构化脱敏日志、低基数指标、Run/Step Trace 关联，不记录提示词、参数或凭据。
- 新增 `Dockerfile`、K3s manifests/Helm-free deployment YAML、CI 文档与评测文档；不假设 l5 有 Docker daemon。
- 扩展 `src/test/`：存储并发/租约、Fake Provider 契约、数据库集成、评测与部署配置验证。
- 更新 `README.md`、路线图状态和阶段验收材料，严格区分已实现与计划中能力。

## 任务 1：基线与并发存储契约

1. 保持当前 `mvn clean test` 结果为基线（当前 49 项通过）。
2. 先为 `RunStore` 增加两个执行者同时 claim 同一待运行任务时至多一个成功、租约过期后旧 fencing token 不能写回、续租仅由当前 token 成功的测试。
3. 执行测试并确认因新 API/行为缺失而失败。
4. 定义明确的 `ClaimedRun`/`LeaseToken` 类型和原子方法，内存 Store 实现相同语义作为 Fake。
5. 执行存储测试和全量测试。

## 任务 2：PostgreSQL 持久化

1. 先为 PostgreSQL store 编写集成测试：Flyway 初始化、Run/Message/Step/ChildTask 往返、事务回滚、并发 claim、fencing token 拒绝旧写者、进程重启后查询恢复。
2. 本地或 CI 提供 PostgreSQL 容器；测试不得读取 `deepseek.key` 或访问真实模型。
3. 建表使用 Run 状态/版本/租约列与关系表；外部模型/工具调用不处于数据库事务中。
4. 实现 PostgreSQL store 与 Flyway 迁移，允许清理旧 JSON 开发数据，不实现旧格式兼容。
5. 验证 Postgres 集成测试及完整 Maven 测试。

## 任务 3：运行时租约和副作用安全

1. 添加运行时测试覆盖 lease loss 在模型/工具调用期间发生，旧执行者无法写入终态，工具副作用不确定时标记 INDETERMINATE 且不自动重放。
2. 执行测试确认失败。
3. 为单 Agent 和 Supervisor–Worker 共用安全 claim/update 语义；增加 owner token、lease heartbeat、条件状态更新与状态转换校验。
4. 对 Outbox 暂不启用；当确有 MQ/跨系统可靠发布需求再实现。文档说明启用后为至少一次，消费者按 event ID 幂等。
5. 执行恢复、竞态、并发及全量测试。

## 任务 4：应用入口与配置

1. 先为配置校验、缺少模型密钥启动失败、CLI composition root 和 Fake 身份上下文边界编写测试。
2. 建立 Spring Boot 模块化单体入口；保持 CLI 可用；短期不启用 HTTP API 和生产认证。
3. 生产 secrets 通过环境/Kubernetes Secret 注入；日志不得回显值；本地 key 文件只作为开发启动方式。
4. 验证启动、配置拒绝、CLI 兼容与全量测试。

## 任务 5：结构化可观测性

1. 先为运行和模型/工具步骤埋点的低基数标签、运行耗时/失败类别、未知 Token 计量与 trace context 关联编写测试。
2. 替换 `slf4j-simple` 为结构化 JSON 日志后端；启用 Micrometer 指标与 OpenTelemetry tracing。
3. 对日志字段做 allowlist，不记录消息正文、API Key、完整工具参数或供应商异常文本；Metrics 禁止 Run/user ID 标签。
4. 验证 capture appender/registry 测试、日志敏感字段测试与全量测试。

## 任务 6：部署、CI 与评测

1. 检查部署清单的探针、资源限制、非 root、安全上下文、secret 引用、Postgres 存储/备份和关闭优雅性；通过静态测试验证关键条件。
2. 构建 OCI 镜像；CI 构建/扫描并推送镜像，l5 由 containerd 拉取，不在 l5 假设 Docker/Compose。
3. 保持 l5 非系统工作负载停止；复核 K3s/节点容量后，仅部署经用户目标确认的开发/集成栈，不宣称单节点 HA。
4. 为固定评测记录完成状态、工具选择、调用次数、耗时、模型 token 可用性；确定性 CI 不调用真实 Provider，真实模型评测单独手动运行并设费用预算。
5. 跑 `mvn clean verify`（含 Postgres 集成测试）及镜像/部署验证，记录实际结果与环境限制。

## 完成条件

- 所有路线图“必须支持”能力在代码或部署契约中有直接实现证据和测试；API/生产认证明确在短期范围外。
- 多实例 claim/fencing、故障恢复、PostgreSQL 迁移、脱敏日志、可观测字段和评测均有自动化证据。
- l5 的外部状态没有未经授权的工作负载启动；Docker/CI 与 Kubernetes/containerd 区别清楚。
- README 与路线图标出已实现项、未实施条件项、测试命令和结果；不得将单节点环境称为高可用或将模型 Fake 评测称为质量保证。
