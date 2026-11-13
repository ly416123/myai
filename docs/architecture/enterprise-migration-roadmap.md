# 阶段 1–3 企业级架构升级方案

**文档状态：** 实施中（2026-11-13 更新）。应用与运行基线已交付：Spring Boot 模块化单体入口、PostgreSQL/Flyway RunStore、租约与 fencing 并发保护、Micrometer/OTel 埋点、Provider 契约及 Testcontainers 测试、容器化 Job、双副本 Run Worker、独立协作子任务 Worker、共享模型/工具预算和真实模型评测入口。DeepSeek Flash 已完成两轮各 5 样例受控基线，工具/计划流程符合预期；回答质量仍需人工评分，费用与数据保留策略仍需组织确认。手动 age 加密备份、隔离库恢复脚本和 Kubernetes 定时备份已落地；L5 已启用 PostgreSQL TLS 校验、每日加密备份与 30 天清理，且完成过加密归档校验和隔离数据库恢复演练。L5 的 Worker、PostgreSQL、OTel Collector、Prometheus 与备份 CronJob 当前运行正常；该环境仍是单节点，PVC 与主库处于同一故障域。

**范围决定：短期不交付 HTTP API 和生产身份认证/授权。用户另行明确短期不建设异地备份、CA 签名密钥故障/托管方案和告警通知投递；这些是显式延期，不视为能力已完成。Prometheus 告警规则和本地告警状态继续保留。其余路线图交付项继续推进。Fake/Mock 仅用于自动化测试，不用于生产装配。** 当前未收尾项包括：新增外部副作用工具前需定义幂等/补偿协议、业务 SLO 与数据分类/保留期限、远端 GitHub CI 验收。当前三项演示工具为本地计算、时间查询和模拟天气，不产生外部副作用；运行中断后未知结果的工具步骤不会自动重放。Prometheus v3.16.0 按用户决定继续使用，不再投入升级工作；其 Trivy HIGH CVE-2026-78661 作为已知风险保留记录。在途 Run 的多 Pod 崩溃恢复演练已于 2026-11-13 在 L5 完成，证据及限制见实施记录。L5 的 Prometheus v3.16.0 镜像扫描报告 `golang.org/x/net v0.61.0` 存在 HIGH（CVE-2026-78661，修复版本 v0.62.0）；截至本次核对，Prometheus 官方 Releases 页面最新正式版仍为 v3.16.0，故未擅自改用未经官方发布的镜像。

## 1. 结论

MyAi 的目标是企业级 Agent Runtime。阶段 1–3 形成了可运行的核心循环、运行状态和 Supervisor–Worker 协作，但早期为控制复杂度采用的 CLI、本地 JSON Store、单进程调度和本地日志，不具备多实例生产运行所需的持久化一致性、集中可观测和运维能力。

建议升级为**模块化单体 + PostgreSQL + Micrometer/OpenTelemetry 可观测 + K3s 部署**。继续保留自研 Agent 控制循环，Spring Boot 负责应用容器、配置、健康检查和生命周期；短期仅支持 CLI/受控内部调用，HTTP API 与生产认证授权暂不交付，但保留后续接入所需的应用服务和身份上下文边界，并用 Fake/Mock 验证这些边界。不因为企业化而直接拆微服务，也不立即强制加入 MQ。只有在任务交付需要跨进程解耦、独立扩缩容或可靠事件投递时，才增加消息队列。

测试中的 Fake/Mock `ModelClient` 不应移除。它是隔离外部模型、复现错误和稳定验证控制流程的必要测试替身；需要补齐提供方协议测试、PostgreSQL 集成测试和受控的真实模型评测，避免把 Fake 测试当作模型质量证明。

## 2. 当前架构与目标形态

当前实现包括：Spring Boot 负责配置绑定与生命周期，CLI、一次性 Run 和后台 Polling Worker 共享应用服务；L5 Worker/任务出站通过 NetworkPolicy 默认拒绝并强制经 Squid 精确域名白名单访问 DeepSeek；`AgentRuntime` 和 `CollaborativeWorkflow` 仍持有手写控制循环；PostgreSQL/Flyway 是应用主存储，RunStore 通过事务、乐观版本、租约和 fencing 保护并发写入；协作流程的模型/工具预算通过 PostgreSQL 行锁和稳定 reservation ID 原子预留，避免幂等重试重复计数；InMemory/JSON Store 继续提供相同契约用于测试与早期回归；日志使用结构化 Logback 输出，Micrometer/OTel 提供埋点与可选 OTLP 导出；DeepSeek 通过 Provider Adapter 接入，测试覆盖 Fake、HTTP Stub 与 Testcontainers PostgreSQL。L5 Worker 和任务 Job 的出站通过 NetworkPolicy 默认拒绝，并强制经仅允许 `api.deepseek.com:443` 的 Squid 代理访问 Provider。持久 Worker 可由多实例领取不同父 Run；启用 `AGENT_SEPARATE_TASK_WORKERS=true` 后，独立子任务 Worker 可按数据库租约领取任务，Supervisor 读取持久状态等待任务完成。HTTP API 与生产认证授权短期不支持。

建议目标结构：

```mermaid
flowchart LR
    User[CLI / 受控内部调用] --> API[应用入口 / Spring Boot 生命周期]
    API --> App[应用服务 / Run 生命周期]
    App --> Agent[自研 Agent 与 Supervisor-Worker 控制循环]
    Agent --> Provider[ModelClient 提供方适配器]
    Provider --> Egress[Squid 出站代理 / 精确域名白名单]
    Egress --> DeepSeek[DeepSeek HTTPS API]
    Agent --> Tools[工具授权、校验与执行网关]
    App --> Store[RunStore 持久化接口]
    Store --> PG[(PostgreSQL 主数据源)]
    App -.满足异步解耦条件后.-> Queue[可选持久消息队列]
    Queue -.-> Worker[可独立扩缩容的 Worker]
    Obs[结构化日志 + Micrometer 指标 + OpenTelemetry Trace] -.贯穿.-> API
    Obs -.贯穿.-> App
    Obs -.贯穿.-> Agent
    Obs -.贯穿.-> Worker
```

首期按模块化单体部署在 K3s。逻辑分层不要求一层一个进程；持久化、并发边界和可观测契约先稳定，再按容量数据决定是否将 Worker 拆为独立 Deployment。

## 3. 按阶段梳理现有选型与企业级改造

| 阶段 | 现状及早期简化 | 企业级目标 | 处理建议 |
|---|---|---|---|
| 阶段 1：应用入口与 Agent 基础循环 | CLI 作为唯一入口；`Main` 同时负责密钥读取、依赖组装和交互；使用 `slf4j-simple`；模型接入目前只有 DeepSeek 适配器；工具以本地 Java 方法为主。 | Spring Boot 模块化单体；配置绑定与启动校验；CLI/受控内部调用；健康检查和优雅停机；结构化日志和外部配置。HTTP API、生产认证授权短期不交付，但应用服务不与 CLI 耦合，并预留身份上下文扩展点。 | 保留手写控制循环与 `ModelClient`/`AgentTool` 边界；将入口、配置和生命周期移入应用框架。生产密钥经 Kubernetes Secret 或组织密钥系统注入，不把密钥文件作为部署配置。测试使用 Fake/Mock 调用方及身份，不把 Mock 身份机制接入生产装配。 |
| 阶段 1：工具协议 | 工具参数使用 Jackson 树结构；自定义校验器只覆盖根级字段类型/required/min/max/enum，嵌套与组合约束未生效。 | 已改用 Draft 2020-12 校验器，支持嵌套和组合约束；本地 `$ref` 可用，远程 Schema 引用在执行侧拒绝。ToolRegistry 提供静态工具 allowlist；PostgreSQL `tool_execution_audit` 追加记录工具步骤状态变化并提供 CLI 查询，不保存参数、结果正文或异常原文。外部副作用工具仍需逐工具定义幂等键或补偿协议。短期不提供 HTTP API 和生产认证授权；工具权限仍由服务端静态策略约束。 | Fake/Mock 仅用于测试边界和拒绝路径，不接入生产装配。生产身份认证、租户隔离及用户级授权待未来 API/真实调用方需求确定后实施。需要外部副作用的工具必须定义幂等或补偿方式。 |
| 阶段 2：Run 状态与 Checkpoint | `InMemoryRunStore` 用于测试；`JsonFileRunStore` 每 Run 一个 JSON 文件。文件原子替换只解决单文件写入完整性，不提供多进程锁、事务、多副本协调或查询治理。 | PostgreSQL 作为 Run 主数据源；使用事务、版本号/乐观锁、租约与状态迁移约束；Flyway 管理版本；可恢复任务由数据库统一认领。 | 以 `agent_run`、`run_message`、`run_step`、`child_task` 等表保存状态和关联；模型消息/计划等变化字段可使用 JSONB。避免把整个 Run 作为无版本大 JSON 覆盖写入。 |
| 阶段 2：日志与监控 | 仅本地 SLF4J 日志和 `/runs` 摘要；没有集中指标、分布式 Trace、告警和 SLO。 | JSON 结构化日志、Micrometer 指标、OpenTelemetry Trace/OTLP；统一关联 `runId`、`stepId`、`taskId`、`traceId`；设定仪表盘和告警。 | 日志和 Trace 可带 Run 标识；指标标签不得使用 `runId` 等高基数值。记录模型/工具耗时、失败类别、Token 可用性、队列等待时间和恢复情况。 |
| 阶段 2：副作用与恢复 | 已区分 PENDING/IN_PROGRESS/INDETERMINATE，但没有工具端幂等协议；取消线程不能撤销外部操作。 | 数据库状态迁移、租约、幂等键/去重表、事务 Outbox 和补偿流程；重启与重复消息可安全收敛。 | 对每种副作用工具单独定义交付语义。没有目标系统幂等/事务配合时，仍不得声称 exactly-once。 |
| 阶段 3：Supervisor–Worker 执行 | 默认 `CollaborativeWorkflow` 在单 JVM 固定线程池并行；Worker 和工具静态注册；单父 Run 保存子任务。 | 父 Run/子任务的数据库事务边界明确；任务可被多实例安全 claim/续租/恢复；可按容量独立扩缩容；Worker 权限、取消、重试和状态变更可审计。 | 已提供数据库轮询的独立子任务 Worker，可通过 `AGENT_SEPARATE_TASK_WORKERS=true` 启用；默认 CLI 仍使用本地线程池。数据库仍是任务事实来源，不需要 MQ。仍需做多进程/Pod 崩溃演练，并按副作用工具定义幂等/补偿协议。 |
| 阶段 3：测试与评测 | Fake ModelClient、固定 Fake 任务和内存 Store 验证确定性流程；没有真实模型质量基线。 | 分层测试：单元 Fake、Provider HTTP 契约测试、PostgreSQL 集成测试、并发/故障测试、版本化 Agent 评测集和受控真实模型评测。 | Fake 保留在测试代码中；新增 HTTP Mock Server 验证 SDK 适配器协议，Testcontainers 验证迁移和数据库并发，真实模型评测独立运行并控制费用/数据。 |

## 4. 存储、队列与恢复的建议

### 4.1 PostgreSQL 作为第一步

当前系统以可恢复的 Run 状态为中心，首先需要事务、并发条件更新和查询能力。建议 PostgreSQL 做唯一主数据源，使用 Flyway 管理迁移；应用通过 Spring JDBC 或 jOOQ 访问，不让 JPA 实体成为状态机的隐式行为层。

建议的最小实体边界：

- `agent_run`：`run_id`、`run_type`、状态、输入引用、最终结果引用、deadline、版本号、租约持有者与过期时间、创建/更新时间。
- `run_message`：按顺序保存消息与必要的工具调用上下文；支持敏感字段保护、访问审计和保留期限。
- `run_step`：模型、工具、规划、汇总等步骤的状态、耗时、失败类别和 Token 用量。
- `tool_execution_audit`：工具步骤状态变化的追加式安全审计元数据；仅保存 Run/步骤/工具调用标识、工具名、状态、失败类别、Worker 和时间，不复制参数、结果或异常内容；通过外键随 Run 保留清理级联删除。
- `child_task`：父 Run、任务/Worker ID、依赖、状态、尝试次数、租约和交付结果引用。
- `outbox_event`：仅当需在提交 Run 事务后可靠通知队列/其他服务时启用。

`RunStore` 需要提供原子认领、续租和带版本/租约条件的状态更新能力，不能只保留当前的 `save/find/list` 接口。认领/状态迁移使用事务和条件更新，不能依赖 JVM 内锁。每次认领生成单调递增的 fencing token（或等价版本令牌）；所有结果写回都校验当前令牌，拒绝已失去租约的旧执行者写入。模型 HTTP 调用和外部工具执行不应放在长数据库事务内；在外部调用前持久化意图，结束后以条件更新记录结果。若租约过期时外部调用仍在执行，令牌只能阻止旧执行者覆盖状态，不能撤销其外部副作用；该工具步骤仍须记录为结果未知，并按工具幂等/查询能力决定后续处理。

子任务租约持久化其父 Run fencing token。父 Run 被新 Supervisor 认领后，即使旧子任务租约的时间尚未到期，也可立即被重新认领；子任务 fencing 序号递增，旧执行者会被拒绝。此机制解决崩溃恢复时父租约和子租约期限错位。启用独立任务 Worker 后，Worker 按轮询周期续租子任务并单独保存任务消息/步骤；Supervisor 从 PostgreSQL 刷新任务状态。父 Run 重新 fencing 时，旧子 Worker 写入会被拒绝。租约和 fencing 只能阻止陈旧状态写入，不能撤销已经开始的外部副作用。

当前处于开发阶段，不要求兼容或迁移既有 JSON 检查点。切换到 PostgreSQL 时允许清理本地开发 Run 数据；仍应保留数据库 schema 的 Flyway 版本迁移、备份恢复与升级验证，这些属于新存储本身的运维要求，不是旧数据兼容。

Run 消息和工具参数可能包含个人信息、密钥或业务数据。已提供 `ops/postgres-backup.sh` 和 `ops/postgres-restore.sh`：使用 `age` 公钥对 `pg_dump` 流加密、SHA-256 校验、临时 0600 `.pgpass` 文件，并要求恢复方显式确认目标数据库；明文 dump 不落盘。另有 `deploy/k8s/base/postgres-backup-cronjob.yaml` 和专用 PostgreSQL 16/age 镜像模板，按每日上海时间 02:00 将加密归档写入专用 PVC；可通过 `retention-days` 配置按数据库清理完整、超期且未锁定的归档。L5 CronJob 已启用每日调度和 30 天清理，且同节点 PVC 不是异地备份；异地副本按用户决定短期暂缓。PostgreSQL、Worker 与备份客户端已启用证书链及主机名校验 TLS；CA 签名密钥故障/组织托管方案按用户决定短期暂缓，当前 TLS 证书链验证正常不代表已建立私钥恢复能力。终态 Run 可按配置定期删除，默认关闭并保留无敏感内容的清理批次审计。用户已选择继续关闭自动清理（`AGENT_RUN_RETENTION_PERIOD=PT0S`）；在数据分类、法规/合同要求和删除流程确认前不启用自动删除。仍需定义组织级数据分类、实际保留期限、用户删除请求流程和备份清理策略配置；异地副本与 CA 签名密钥故障/托管按用户决定短期暂缓；日志中不写完整提示词、密钥或敏感工具参数。

### 4.2 MQ 是条件性组件，不是本轮强制项

当前只有单节点 K3s，l5 上原有 NATS、PostgreSQL、Redis、MinIO、Keycloak 工作负载均已停止，数据卷保留。存在镜像或 PVC 不代表服务已可用，也不代表配置满足生产要求。2026-11-12 检查到 4 CPU、7.2 GiB 内存、根卷可用约 87 GiB；停掉应用工作负载后 available 内存约 5.5 GiB（停机前约 1.0 GiB）。复用前仍要检查实际负载、存储、配置和 PVC 备份。

建议先使用 PostgreSQL 持久任务表和租约完成可恢复任务调度。出现以下需求之一后再评估 MQ：

- Worker 需要独立部署、扩缩容或跨节点消费；
- 峰值任务需要排队削峰，且数据库轮询不满足吞吐/延迟目标；
- 需要把状态变化可靠发布给多个系统。

引入 MQ 后使用事务 Outbox 防止数据库提交成功但消息未发布。Outbox/消息代理提供的是至少一次交付语义，消息可能重复；消费者必须按稳定事件 ID 或业务幂等键去重，不能宣称端到端 exactly-once。选择一个消息系统即可：优先评估 l5 已存在过的 NATS JetStream 是否满足持久化、重投递、消费组和运维要求；若不满足，再比较 RabbitMQ/Kafka 的业务语义和资源成本。不要同时部署多个队列，也不要把 MQ 当作 Run 的唯一事实来源。

## 5. 监控、MockClient 与企业级测试

### 5.1 监控目标

- **日志：** SLF4J 门面配 JSON 格式后端，输出到标准输出，由 K3s/日志平台采集；统一携带 `runId`、`stepId`、`taskId`、`workerId` 和失败类别，敏感内容按字段拒绝或脱敏。
- **指标：** Micrometer 统计 Run 成功/失败/部分成功、阶段耗时、模型和工具调用数、Token 用量未知比例、预算耗尽、队列深度与租约过期；Spring Boot 自动绑定 Hikari 活跃/最大连接数等指标。指标标签使用有限枚举，不使用 Run/用户 ID。
- **追踪：** OpenTelemetry Trace 串联入口、规划、Worker、模型适配器、工具和数据库调用；模型提示词/响应默认不作为 Span 属性。
- **告警：** 先基于服务可用性、错误率、P95 延迟、数据库连接池、任务积压/租约超时和模型失败率定义 SLO 与告警阈值，再决定 Prometheus/Grafana 或现有集中平台。

L5 实际已运行单副本 Collector 与 Prometheus 作为受控集成验证环境；Prometheus 仅 ClusterIP、持久化 7 天，当前已加载五条告警规则，包括近期失败 Run 告警。队列、过期租约和 Hikari 告警表达式使用 Collector 实际导出的 `myai_` 指标前缀；本地契约测试锁定这些名称。Prometheus 提供一个自定义内置控制台 `/consoles/agent-overview.html`，显示队列深度、最老等待时间、过期租约数、过去 5 分钟失败 Run 数和连接池用量，不需要增加 Grafana 镜像。它不提供 HA、Grafana 仪表盘、外部集中后端或告警接收。L5 操作级运维手册已覆盖只读诊断、告警查看、租约恢复原则、Worker 回滚与备份恢复边界；生产环境操作流程仍需结合目标架构、业务 SLO、数据治理和正式灾备要求完善。生产标签/聚合、业务 SLO 与告警路由未确认/交付。Prometheus v3.16.0 的 Trivy HIGH CVE-2026-78661 仍存在；用户已选择保留当前版本，不再投入升级工作。

已实现的 Worker Gauge 为 `agent.worker.queue.depth`、`agent.worker.lease.expired`、`agent.worker.queue.oldest`（秒）和近 5 分钟失败 Run 数，由 Worker 每轮轮询时读取 PostgreSQL 聚合快照；Hikari 指标由 Spring Boot 自动绑定。所有 Worker 副本观测相同的数据库全局队列快照，因此多副本指标后端必须按最大值或单实例读取，不能求和。Worker 通过 `/tmp/myai-worker-live` 和 `/tmp/myai-worker-health` 时间戳提供无 HTTP 的 Kubernetes liveness/readiness exec probe：轮询线程停止时重启，数据库轮询失败时只退出就绪状态。`ops/monitoring/agent-alerts.yaml` 提供队列等待、过期租约、近 5 分钟失败 Run 和 Hikari 连接池的 Prometheus 起步规则，阈值不是已确认 SLO。Prometheus 自带 `promtool` 的确定性规则测试现已纳入 CI，覆盖四类告警的触发延迟和阈值边界；L5 Rules/Alerts API 实测五条规则健康且当前没有 active Agent 告警。L5 运维手册位于 `docs/operations/l5-runbook.md`，包含当前环境可执行的只读检查与恢复边界；生产环境标签、多副本聚合方式、业务 SLO、Grafana/外部集中后端和面向正式服务目标的运维流程仍未配置/验收。告警通知投递按用户决定短期暂缓，Prometheus 仍保留规则和本地告警状态。异地备份与 CA 签名密钥故障/托管方案同样按用户明确决定短期暂缓；同节点 PVC 不作为异地备份。

### 5.2 Fake/Mock ModelClient 的正确定位

当前测试里的 Fake ModelClient 是 `src/test` 中的测试替身，不是生产模型客户端。它应该保留并整理成 `ScriptedModelClient`/记录型测试工具：按预设响应、错误和延迟驱动流程，支持断言请求历史、工具定义、调用次数和取消行为。生产装配必须只接受真实 Provider Adapter，不能通过配置把 Fake 误启用。

当前已补充本地 HTTP Stub 的 Provider 契约测试，覆盖请求路径、鉴权头不泄漏、模型与工具映射、多个工具调用解析、Token 用量，以及 401/408/429/503 和真实请求超时的失败分类、重试属性与敏感正文隔离；没有读取真实 API Key。受控评测能力如下：

1. **Provider 契约测试：** 本地 HTTP Stub 验证 DeepSeek 请求路径、鉴权头存在但不泄漏、模型/工具 Schema 映射、多个工具调用解析、Token 用量、超时和错误分类；无需真实 API Key。
2. **Agent 评测：** 版本化合成场景集与显式启用的真实评测入口已提供，记录任务结果、协作计划摘要、工具选择、调用次数、Token 用量、可按配置单价估算的费用及人工评分字段。通过标准检查流程、预期工具及可选计划路由/Worker；不评价自由文本质量。普通 CI 只运行确定性样例；真实模型评测需要人工显式触发，并受样例数、模型/工具总调用和总时限限制。2026-11-12 已对 DeepSeek Flash 连续运行两轮各 5 个合成样例，两轮均 5/5 符合工具/计划预期，各调用 9 次模型与 3 次工具，Token 分别为 4329/451 和 4329/426；调用单价未配置，费用未知。默认报告目录 `var/agent-evaluations` 下保留第二轮受限报告；尚无人工回答评分或生产质量结论。

数据库集成测试已使用 Testcontainers PostgreSQL 验证 Flyway 初始化、运行记录往返、并发 claim、fencing token、版本冲突与取消请求、工具审计状态追加及父/子任务检查点路径，以及终态 Run 的消息/步骤/子任务/工具审计级联删除；另以真实 PostgreSQL 16 临时容器验证了加密备份恢复到隔离库并读回数据。需要可用的 Docker API 或受支持容器环境；CI 使用 Docker-enabled runner，本地 Colima 通过其 Docker socket 执行。定时备份 CronJob 与可选保留清理模板已添加；L5 overlay 已配置每日调度、30 天保留及 age 加密公钥，CronJob manifest 默认 suspend；L5 CronJob 已部署并启用每日 02:00 调度，age 公钥 ConfigMap 和 5Gi local-path PVC 已创建并 Bound；实际 Job 已验证加密备份、TLS 数据库连接及 30 天清理。异地存储与 CA 签名密钥故障/托管按用户决定短期暂缓；生产环境定期恢复演练仍未完成。

## 6. 实施状态与剩余交付

| 优先级 | 交付项 | 状态与证据 |
|---|---|---|
| P0：架构基线 | 模块边界、Run 状态迁移、副作用恢复语义和 HTTP/认证短期范围已明确；目标并发、可用性、RPO/RTO、数据保留和 SLO 尚无业务数值。 | 现有设计记录见本文件；容量与 SLO 决策待业务侧给定后补 ADR。 |
| P1：应用与部署 | Spring Boot 无 Web 入口、配置外置、CLI、`once` 单任务入口、`worker` 后台模式、OCI Dockerfile、K3s Job/多副本 Worker 清单、CI 构建/测试流水线及默认分支受保护的 GHCR 应用、备份和模型出站代理镜像发布任务已提供；后台 Worker 有界轮询 PostgreSQL 并按 RunType 分派恢复，记录 dispatch 数与可确定的队列等待时长；CLI `/runs` 仅从数据库读取最近 20 条。关闭时请求取消并等待 Run 终态收尾。Job/Worker 的 Provider Key 已改为只读 Secret 文件挂载，非 root Pod 使用 `fsGroup` 读取；Worker/任务 Pod 出站默认拒绝并强制经过仅允许 `api.deepseek.com:443` 的 Squid 代理；无需 Kubernetes API 权限的 Job/Worker 已关闭 ServiceAccount Token 自动挂载。HTTP API/生产认证授权按决策暂缓。 | Worker 并发/类型分派和低基数指标测试、PostgreSQL 可恢复批次查询和最近记录 limit 查询测试、Spring Worker 配置上下文测试、容器清单契约测试。YAML 客户端解析与 Trivy 配置扫描通过；发布任务的契约测试覆盖默认分支门禁、先验证再扫描再推送、最小 `packages: write` 权限和 SHA 标签。GHCR 实际推送、包权限/可见性与集群拉取 Secret 尚未由远端 CI/集群验证；近期用户暂缓推送。2026-11-13 L5 Secret 静态加密已启用并完成密钥轮换/SQLite 加密数据核验；独立 `myai` namespace、5Gi PostgreSQL PVC 与 PostgreSQL 16 StatefulSet 已运行并通过 `pg_isready`。Worker 镜像已在 L5 运行并 Ready，Provider Secret 已挂载，Flyway schema v6 已由应用执行；OTel Collector、Prometheus 已运行，Prometheus 已抓取 Collector 和应用/Hikari 指标。备份 CronJob 与 PVC 已在 L5 部署；amd64 镜像由 Go 1.26.11 固定校验工具链构建，并通过镜像扫描和 L5 隔离恢复演练。旧 token-refresh RoleBinding 最小权限审查已完成；L5 Worker Deployment 回滚并重新应用当前 overlay 的演练已完成，但不覆盖数据库 schema 回滚。GHCR 实际推送、包权限/可见性和集群拉取 Secret 仍未由远端 CI/集群验证；近期用户暂缓推送。 |
| P1：运行存储与基础观测 | PostgreSQL/Flyway、事务写入、乐观版本、原子 claim、租约/fencing（父 Run 默认 30 秒租期、10 秒心跳）、取消标志、结构化日志、Run/step 指标、Worker 队列深度/最老等待/过期租约 Gauge、Hikari 连接池指标及 OTel/OTLP 选项已接入；metrics 与 traces 可以分别启用并配置 HTTP OTLP endpoint；提供 age 加密备份/恢复脚本、按库/天保留的 Kubernetes 定时备份和终态 Run 清理能力；L5 CronJob 与单节点 PVC 已部署，首次手动备份/隔离恢复演练已通过。 | Testcontainers 覆盖迁移、读写、并发 claim、旧 fencing token 拒写、版本冲突、事务回滚，以及 Run/消息/步骤/子任务级联清理与审计；备份清理脚本测试安全边界，真实 PostgreSQL 16 加密备份恢复 smoke 和队列聚合查询通过。新增本地 Testcontainers OpenTelemetry Collector 端到端验证，OTLP traces/metrics 均已接收，告警 PromQL 引用的队列/Hikari 指标名均在 Collector Prometheus 输出中找到。Run 清理默认关闭；L5 已部署 PostgreSQL 与 Prometheus 各自 5Gi local-path PVC、OTel Collector 和 Prometheus；Collector 抓取目标健康且应用/Hikari 指标可见。每日备份 CronJob、30 天保留和 age 加密已在 L5 启用。L5 PostgreSQL 已启用 TLS；Worker 与备份 Job 使用 `verify-full` 和 CA 验证，独立连接检查、Worker/Flyway 启动及备份 Job 均通过。L5 Prometheus 已加载 Collector、队列等待、过期租约、近期失败 Run 和连接池五条告警规则。PVC 为单节点 local-path，异地复制、节点故障恢复演练和告警通知路由仍未完成。2026-11-13 L5 在途 Run 演练使用纯合成 Provider Stub：SIGKILL 结束持租 Worker 后，Run 保持 RUNNING，租约到期后 peer Pod 以 fencing token 2 接管并完成；模型 IN_PROGRESS 步骤被安全重试，可能产生重复模型费用，演练未调用工具。Prometheus v3.16.0 Trivy 扫描有一项 HIGH（CVE-2026-78661）；按用户决定保留当前版本，不投入升级。多副本队列 Gauge 是同一全局值，不能求和。
| P2：协作调度 | 子任务表有数据库原子 claim、父/子 fencing 及父 Run 更换 owner 后立即重领；Run 级共享模型/工具预算使用数据库事务、父 Run 行锁与稳定 reservation ID 做原子、幂等预留，避免过量分配和重复计费。持久 Run Worker 和子任务 Worker 均可跨实例轮询领取工作；Supervisor 以 PostgreSQL 中的任务状态等待结果。CLI 默认本地执行，Worker 部署可显式切换数据库队列路径。 | Fake Store、PostgreSQL 和确定性模型测试覆盖活跃父 Run 扫描、竞争 claim、续租、父 Run refence 后立即重领和旧 token 失效、子任务依赖/检查点/取消以及共享预算。L5 在途 Run 多 Pod 崩溃恢复演练已通过；当前工具仅有本地计算、时间查询和模拟天气，没有外部副作用工具；未来添加此类工具前须逐项定义幂等、补偿和审计策略。MQ 按负载条件评估，不默认部署。 |
| P2：可靠性与数据治理 | 工具调用未知副作用不自动重放；终态 Run 可按配置保留周期清理，删除操作有不含敏感运行数据的审计记录。工具调用状态变更已有独立追加式审计表及 CLI 查询，审计记录不含参数、结果或异常原文，并与 Run 保留删除级联。备份支持按数据库和配置天数清理完整、未锁定的旧归档，未配置时不删除。当前没有外部副作用工具；未来此类工具的幂等/补偿协议须作为上线门槛。数据分类与删除请求流程及生产恢复演练尚未完成；异地数据库备份按用户决定短期暂缓，现有本地恢复验证不构成异地恢复能力。 | 本地手动加密备份与恢复脚本已实测；Run 清理单元与 PostgreSQL 级联/审计集成测试通过，备份保留脚本测试覆盖关闭默认、参数校验、超期/不完整/活动锁及数据库隔离。保留天数需按业务/法规要求配置；继续明确 exactly-once 限制，并按工具逐项定义幂等、补偿或人工核对方式。 |
| P2：评测与安全验收 | Fake 回归、Provider 协议 Stub 和显式启用的真实模型评测 Runner 已提供。Runner 从版本化场景文件读取合成样例，记录工具选择、Run 状态、调用预算、Token 用量、协作计划摘要和人工评分字段；费用只在 Token 用量与调用方配置的单价均已知时估算。CI 不触发真实模型。CI 已配置 Gitleaks 全 Git 历史凭据扫描、应用、备份与模型出站代理镜像 HIGH/CRITICAL 漏洞门禁和仓库 Docker/Kubernetes 配置扫描。 | 本地 Git 历史/工作区 Gitleaks 扫描无命中；本地 Trivy 对应用镜像、备份镜像、模型出站代理镜像及 K8s/Docker 配置扫描均无 HIGH/CRITICAL 项（Prometheus 第三方镜像单独有已知 HIGH CVE，按用户决定继续使用）。远端 CI 尚未运行，流水线门禁仍需由实际运行确认；近期用户暂缓推送，待重新授权发布后再验证远端流水线。Fake 测试验证 Runner 的预算、结果字段、权限和计划摘要；两次 DeepSeek Flash 运行各 5/5 符合流程/工具/路由断言。尚需人工文本质量评价、真实费用单价和远端 CI 结果。 |

不要先把 4 CPU 单节点 l5 宣称为高可用生产集群。它适合当前的开发、集成或受控验证环境；正式可用性目标若要求节点故障时不中断，需要多节点 K3s/Kubernetes、独立持久存储和备份恢复设计。

l5 没有 Docker/Compose 命令不妨碍 K3s 运行容器：CI 已配置默认分支 GHCR 发布和镜像扫描，但没有远端工作流结果，实际镜像发布与集群拉取尚未验证；部署前需确认镜像仓库可达并提供拉取凭据。Job/Worker 清单通过只读 Secret 文件挂载 Provider Key，Pod 禁止自动挂载 ServiceAccount Token；当前默认 ServiceAccount 的 `get/list secrets` 授权检查为 `no`。但 l5 `agentteams` 命名空间仍存在一个既有 token-refresh ServiceAccount RoleBinding，允许其 `get/patch secrets`，这属于集群范围 Secret 治理与本应用隔离验收的待审项。2026-11-13 已在 l5 启用 Kubernetes Secret 静态加密并完成既有集群密钥轮换；`k3s secrets-encrypt status` 返回 `Enabled / reencrypt_finished` 且所有 Server 密钥哈希一致。轮换前已创建并本地 age 加密控制面恢复包，包含 SQLite 一致性副本、Server token、加密配置及 systemd 配置；解密到临时文件核验归档内容与 SQLite `integrity_check=ok` 后删除临时明文。轮换后通过只读 SQLite 一致性副本再次核验 `integrity_check=ok`，28 个当前 Secret 对象的 value 与 old_value 均带 `k8s:enc:` 前缀；没有读取或输出 Secret 内容。K3s 官方对既有集群启用及密钥轮换的步骤见[官方流程](https://docs.k3s.io/cli/secrets-encrypt)。后续只读审查确认，token-refresh Role 仅允许对 `agentteams-chatgpt-oauth-session` 命名 Secret 执行 get/patch；对应 Helm CronJob 已 suspended，当前无活动 Pod。RBAC 的具体 Secret 访问通过 `kubectl auth can-i` 验证为允许，访问另一个 Secret 为拒绝。由于这是其他 Helm release 管理的配置且作用范围已限定，予以保留，不与 MyAI Secret 混用。只有确有 l5 本地构建需求时，才评估 BuildKit 等工具及其磁盘/内存占用。

## 7. 本次不做的事情与待确认业务指标

已在本地代码中切换到 Spring Boot/PostgreSQL，并提供 Compose、OCI 镜像、K3s Job/Worker、PostgreSQL 加密备份模板。2026-11-13 在 L5 启用 Kubernetes Secret 静态加密并完成密钥轮换和 SQLite 加密标记核验。独立 `myai` namespace 中 PostgreSQL 16、双副本 Worker、OTel Collector、Prometheus 均 Ready；Worker 执行 Flyway 至 schema v6，Provider Key 通过只读 Secret 文件挂载。所有 Service 为 ClusterIP，PostgreSQL/Prometheus 各用 5Gi local-path PVC。PostgreSQL 已启用证书链和主机名校验 TLS，Worker 与备份 CronJob 都通过 `verify-full` 连接验证；CronJob 每日 02:00 执行 age 加密备份并按配置保留 30 天，实际 Job 已验证备份与清理。Prometheus 已加载 Collector、队列等待、过期租约、近期失败 Run 和连接池五条规则；告警通知投递按用户决定短期暂缓。Postgres Pod 主容器以 UID 999 非 root、只读 rootfs、无额外 capability 运行；权限初始化与 TLS 私钥准备 initContainer 以受限 root 执行，Trivy 清单扫描无 HIGH/CRITICAL。Prometheus v3.16.0 有一项 Trivy HIGH（CVE-2026-78661），仅内网 ClusterIP；按用户决定保留当前版本，不再投入升级。L5 为单节点 K3s v1.36.4+k3s1；数据库与备份 PVC 为单节点 local-path。同一节点上的备份不构成异地副本。SLO 仍需业务指标；Run 自动清理按用户决定继续关闭（`AGENT_RUN_RETENTION_PERIOD=PT0S`），具体业务保留期限待数据分类和合规要求确认。异地备份、CA 签名密钥故障/托管方案和告警通知投递均按用户决定短期暂缓。L5 另已完成在途 Run 的多 Pod 崩溃恢复验证：使用合成 Provider Stub 和 30 秒租约，SIGKILL 终止持租 Worker 后 Run 未被误标为取消，租约到期后另一 Worker Pod 以 fencing token 2 接管并在原 Deadline 前完成。模型请求按安全语义重试且用量累计标记未知；没有工具副作用，因此该演练不证明外部模型请求 exactly-once，也不证明节点级灾备。

容量与部署参数应在进入对应实施或上线验收前确定，包括目标并发与峰值、可用性目标、RPO/RTO、Run/消息保留时限、模型数据是否允许发送到外部 Provider、模型成本预算，以及 l5 当前作为开发/测试还是生产环境。这些参数用于选择副本、数据库高可用、消息队列和监控后端，不是取消其余路线图能力的前置条件。短期不支持 HTTP API 与生产认证授权；其余路线图能力均需按优先级实现和验证。未来如需接入真实用户/API 或多租户，再结合实际接入方式明确身份提供方、授权模型和租户隔离要求。Fake/Mock 仅用于测试，不是生产认证替代品。HTTP API/生产认证授权是明确的短期范围排除项；Fake/Mock 是测试策略，不是这些能力的实现或延期替代品。

## 8. 官方技术资料

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)：当前参考文档涵盖 Java 21；实施时按团队支持周期选择受支持版本和统一 BOM。
- [PostgreSQL 事务隔离](https://www.postgresql.org/docs/current/transaction-iso.html)：事务隔离和显式锁行为需按具体状态迁移设计验证。
- [Micrometer Tracing](https://docs.micrometer.io/tracing/reference/) 与 [OpenTelemetry Bridge](https://docs.micrometer.io/tracing/reference/tracers.html)：用于 JVM 统一追踪门面与导出器。
- [Testcontainers PostgreSQL 模块](https://testcontainers.com/modules/postgresql/)：用于真实 PostgreSQL 集成测试，不替代单元测试。
- [GitHub Actions 发布和安装容器包](https://docs.github.com/en/packages/managing-github-packages-using-github-actions-workflows/publishing-and-installing-a-package-with-github-actions)：GHCR 工作流通过 `GITHUB_TOKEN` 和 job 级 `packages: write` 发布镜像。

## 9. 本轮验收记录

- 2026-11-12 本地验收复核：重新运行 `MYAI_BACKUP_IMAGE=myai-postgres-backup:acceptance-2026-11-12 bash ops/tests/postgres-backup-restore-smoke.sh`，最终通过：备份容器按保留策略删除过期归档，生成加密备份，并恢复到隔离数据库读回探针数据。首次复验失败的根因由临时 PostgreSQL 容器日志确认是 Colima Docker 虚拟机数据盘空间耗尽（`initdb: ... No space left on device`），不是备份脚本错误；仅清理该 Docker 环境 BuildKit 构建缓存后，PostgreSQL 探针和完整 smoke 均通过。该验证仍是本地手动恢复测试，不替代 l5 CronJob、异地副本、密钥轮换或生产恢复演练。
- 2026-11-12 OTLP 配置复核：新增 `OtlpEndpointConfigurationTest` 先验证失败，再修正 Spring Boot 3.5 traces exporter 的属性路径，并加入独立的 metrics/traces endpoint 配置：`AGENT_OTLP_METRICS_ENDPOINT`、`AGENT_OTLP_TRACES_ENDPOINT`；两路开关默认关闭。完整 `clean verify` BUILD SUCCESS，133 项测试通过，0 失败、0 错误、0 跳过。参考 [Spring Boot 3.5 OTLP 属性](https://docs.spring.io/spring-boot/3.5/appendix/application-properties/)；Collector 连通性、后端落盘、告警路由仍未验证。
- 2026-11-12 l5 Secret RBAC 只读核验：`agentteams` 默认 ServiceAccount 对 Secret 执行 `get/list` 均被拒绝；本项目 Job、Worker 模板也显式关闭 ServiceAccount Token 自动挂载。命名空间 RoleBinding 仍授予既有 token-refresh ServiceAccount `get/patch secrets`；当日集群静态加密尚未启用。
- 2026-11-13 l5 控制面保护与基础设施准备：轮换前 K3s SQLite/Server token/配置的恢复包经 age 加密保存并校验；启用 `--secrets-encryption` 后按官方顺序执行 enable、重启、`rotate-keys`、重启，最后状态 `Enabled / reencrypt_finished` 且所有 hashes match。恢复归档中的 SQLite 与轮换后只读一致性快照均通过 `PRAGMA integrity_check=ok`；快照统计 28/28 当前 Secret value 和 28/28 old_value 为 `k8s:enc:`，未读取 Secret 内容。导入 Temurin 21 amd64 基础镜像后 K3s node Ready，CoreDNS/local-path-provisioner/metrics-server 均为 1/1。l5 OCI 镜像构建仍受本地 BuildKit VM 代理指向不可达地址影响，应用 Worker 镜像未部署。旧 token-refresh RoleBinding 未改。
- 2026-11-13 OTLP 端到端复核：严格样本断言发现租约 Gauge 的 Prometheus 名称为 `agent_worker_lease_expired_leases`（包含 `leases` 单位后缀），原告警查询名错误，已修正规则。`OtlpExporterIntegrationTest` 使用固定版本 OpenTelemetry Collector Testcontainer，将真实 Micrometer metrics 和 Observation trace 推送至 OTLP/HTTP，抓取 Prometheus exporter 样本并与告警表达式逐项核对；当时 Hikari 样本由未连接数据库的测试池提供。后续严格复验发现此测试池未触发 Spring Boot Hikari metrics tracker，已于 2026-11-13 改为连接临时 PostgreSQL 的真实 Hikari 池并重新通过。Collector debug exporter 确认收到 trace。`DOCKER_HOST=unix:///Users/gecko/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true mvn --batch-mode --no-transfer-progress clean verify` BUILD SUCCESS，134 项测试通过，失败/错误/跳过均为 0。此项验证本地 Collector 的 OTLP 接收及 Prometheus 指标映射，不代表已部署长期存储、告警接收端或生产监控后端。
- 2026-11-12 部署凭据收敛：Agent Job 与 Worker 不调用 Kubernetes API，Pod 模板现关闭 ServiceAccount Token 自动挂载；`ContainerDeploymentContractTest` 先因两个模板缺少该限制而按预期失败，补充配置后 5 项契约测试通过。Job 和 Worker 的 `kubectl create --dry-run=client --validate=false --context kind-agentteams` 客户端解析通过；未向 kind 或 l5 提交资源。
- 2026-11-12 镜像发布链路：CI 增加默认分支 push 专用 GHCR 任务，依赖完整 `verify`，使用仅该 Job 持有的 `packages: write` 权限；发布 Job 对 SHA 标签镜像执行 Trivy HIGH/CRITICAL 扫描，通过后只推送不可变 commit SHA 标签，并附 OCI source/revision 标签。三个 K8s Pod 模板引用 `myai-registry` imagePullSecret。契约测试先因发布任务与镜像拉取凭据缺失按预期失败，补齐后验证通过；工作流 YAML 解析通过；远端 GitHub Actions 尚未运行，实际包创建、权限和集群拉取仍未验证。
- `DOCKER_HOST=unix:///Users/gecko/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true mvn --batch-mode --no-transfer-progress clean verify`：本轮 BUILD SUCCESS，132 项测试，失败 0、错误 0、跳过 0；包括 PostgreSQL Testcontainers 集成测试和 GHCR 发布契约测试。这个结果验证本地构建和测试，不代表镜像发布、远端 CI 或 l5 部署已完成。
- `DOCKER_HOST=unix:///Users/gecko/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true mvn --batch-mode --no-transfer-progress clean verify`：此前验收 BUILD SUCCESS；131 项测试，失败 0、错误 0、跳过 0。覆盖调度器 daemon 属性及保持 Spring 上下文打开时子 JVM 自行退出、评测报告目录默认值、Provider 密钥文件配置和 K8s Secret 挂载契约、真实模型评测 Runner 的 Fake 边界、PostgreSQL 工具审计、父/子租约、Schema、DeepSeek HTTP Stub、共享预算、备份模板和部署契约。此前生成在 `target/` 的真实评测报告被 `clean` 清理；默认路径随后移至 `var/agent-evaluations` 并加入 Git 忽略规则，后续报告不受 Maven clean 影响。
- `mvn --batch-mode --no-transfer-progress -Dtest=AgentSchedulingConfigurationTest test`：先因待测调度配置不存在而按预期编译失败；实现后测试通过，实际调度任务在线程上断言 `isDaemon=true`。此修复针对真实评测中观察到的 `eval` 输出结果后 JVM 被 Spring `scheduling-1` 非 daemon 线程挂住的问题。
- Java 21 executable 启动当前应用 JAR，连接临时 PostgreSQL 并通过标准输入发送 `/exit`：进程退出码 0，观察到 Spring 启动与 CLI 退出标记。首次冒烟误用了 PATH 中 Java 17 并得到 `UnsupportedClassVersionError`，确认是验证命令选错 JVM 后改用 Maven 的 Java 21 重跑成功；该 Java 17 结果不是应用失败。
- 受控真实模型评测：对版本 `2026-11-v1`、模型 `deepseek-flash` 连续执行两轮各 5 个样例，均 5/5 通过；每轮 9 次模型调用、3 次工具调用，Token 分别为 4329 prompt / 451 completion 与 4329 / 426。未配置单价，费用未知；每轮均使用临时 PostgreSQL，第二轮进程退出码 0，报告保存于 `var/agent-evaluations/evaluation-3355224007298720591/report.json`（目录 0700、文件 0600，Git 忽略）。样例回答未进行人工质量打分。
- `docker build --build-arg UBUNTU_REPOSITORY=mirrors.aliyun.com/ubuntu-ports --tag myai-agent:eval-check .`：本地构建成功；从镜像提取应用 JAR 并确认含 `AgentSchedulingConfiguration`，容器内确认评测数据集存在，运行用户为 `10001:10001`。未部署集群。
- `trivy image --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 myai-agent:eval-check`：当前重建镜像扫描通过；Ubuntu 26.04 系统包 0 项、应用 JAR 0 项 HIGH/CRITICAL 漏洞。
- K8s Provider 凭据安全：Job/Worker 清单使用 `DEEPSEEK_KEY_FILE` 指向 Secret 只读卷，Secret 文件模式 `0440` 并设置 Pod `fsGroup: 10001`；测试确认不再把 Provider Key 注入环境变量，配置测试使用合成 key 验证文件路径覆盖，不访问模型。
- `kubectl create --dry-run=client --validate=false --context kind-agentteams -f deploy/k8s/base/agent-task-job.yaml -o name` 与 Worker Deployment 同样 dry-run：客户端解析通过；`trivy config --severity HIGH,CRITICAL --exit-code 1 deploy/k8s`：3 个 K8s 清单均 0 项 HIGH/CRITICAL 配置发现。均为本地只读验证，未向 kind 或 l5 提交资源。
- `mvn -q -DskipTests package`：BUILD SUCCESS，供镜像打包阶段复用已构建 JAR。
- Jackson BOM 升级到 2.22.3 并接入 NetworkNT JSON Schema Validator 2.0.8 后，重建 `myai-agent:enterprise-check` 镜像并用 Trivy 扫描 HIGH/CRITICAL：Ubuntu 系统包 0 项、应用 JAR 0 项；中间版本 2.22.1 曾报告 5 项可修复 Jackson HIGH，已升级到 Trivy 标记的修复版本后复扫通过。
- `docker build --build-arg UBUNTU_REPOSITORY=mirrors.aliyun.com/ubuntu-ports --tag myai-agent:enterprise-check .`：本地构建成功；升级 Ubuntu 基础层安全更新并移除未使用且含旧 Go 标准库的 `/usr/bin/pebble`，镜像仍使用 Java 21、UID/GID 10001。Docker build context 忽略 `.env` 与 `deepseek.key`。
- `trivy image --java-db-repository ghcr.io/aquasecurity/trivy-java-db:1 --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 myai-agent:enterprise-check`：修复前发现 8 个 Ubuntu 系统包 HIGH 项、8 个 Java 依赖 HIGH 项和 12 个 `pebble` Go 标准库 HIGH 项；重建后 Ubuntu 系统包与 Java JAR 均为 0 个 HIGH/CRITICAL 项，`pebble` 已从最终镜像移除。
- `docker image inspect ...` 与容器内 `java -version`：确认运行用户为 10001，镜像 Java 版本为 21.0.12。
- `kubectl create --dry-run=client --validate=false -f deploy/k8s/base/agent-task-job.yaml -o name`：客户端解析通过。未向 l5 或其他 Kubernetes 集群提交资源。
- `kubectl create --dry-run=client --validate=false -f deploy/k8s/base/agent-worker-deployment.yaml -o name`：输出 `deployment.apps/myai-agent-worker`。只做本地客户端解析，未部署到 l5。
- `kubectl create --dry-run=client --validate=false -f deploy/k8s/base/postgres-backup-cronjob.yaml -o name`：输出 `cronjob.batch/myai-postgres-backup`。只做本地客户端解析，未部署。
- `trivy config --severity HIGH,CRITICAL --exit-code 1 deploy/k8s` 和相同参数扫描 `ops`：部署清单及备份 Dockerfile 均无 HIGH/CRITICAL 配置发现。
- `bash -n ops/run-scheduled-backup.sh ops/postgres-backup.sh ops/postgres-restore.sh ops/prune-postgres-backups.sh ops/tests/postgres-backup-retention-test.sh ops/tests/postgres-backup-restore-test.sh ops/tests/postgres-backup-restore-smoke.sh`：脚本语法通过。
- `bash ops/tests/postgres-backup-retention-test.sh`：通过，覆盖默认不清理、保留天数校验、仅清理目标数据库的完整超期归档，以及对近期、校验文件缺失、活动锁和其他数据库归档的保护。
- `bash ops/tests/postgres-backup-restore-test.sh`：脚本安全契约通过，覆盖不覆盖已有归档、SHA-256 校验、目标库确认和密码文件权限/转义。
- `bash ops/tests/postgres-backup-restore-smoke.sh`：实际备份容器按 30 天策略删除命名卷中已过期且完整的加密归档，再创建加密归档；恢复脚本经临时 PostgreSQL 容器的 `pg_restore` 恢复到隔离数据库并读回数据，成功。使用 Docker 命名卷，不依赖本机 libpq 或 macOS bind mount 同步；临时容器和卷由脚本退出 trap 删除。
- `docker build --build-arg ALPINE_MIRROR=mirrors.aliyun.com/alpine --file ops/backup.Dockerfile --tag myai-postgres-backup:enterprise-check .`：最终 PostgreSQL 16 Alpine 备份镜像构建成功；`apk upgrade` 将基础包更新到当时仓库版本并移除未使用的 `gosu`。Dockerfile 默认仍使用官方 Alpine 仓库，镜像参数只为本地网络不可达时提供覆盖方式。
- `trivy image --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 myai-postgres-backup:enterprise-check`：最终备份镜像扫描为 0 个 HIGH/CRITICAL 系统包或 Go 二进制漏洞。
- `MYAI_BACKUP_IMAGE=myai-postgres-backup:enterprise-check bash ops/tests/postgres-backup-restore-smoke.sh`：实际运行备份镜像，在非 root 用户和只读根文件系统下创建加密 PostgreSQL 归档，并恢复到独立数据库读回验证成功。Smoke 使用 Docker 命名卷和 `docker cp`，避免依赖宿主机对容器 bind mount 的文件同步行为。
- `docker build --build-arg ALPINE_MIRROR=mirrors.aliyun.com/alpine --file ops/backup.Dockerfile --tag myai-postgres-backup:retention-check .`：新增清理脚本后的备份镜像构建成功；`trivy image --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 myai-postgres-backup:retention-check` 扫描系统包和 age 二进制均为 0 个 HIGH/CRITICAL 漏洞。
- `MYAI_BACKUP_IMAGE=myai-postgres-backup:retention-check bash ops/tests/postgres-backup-restore-smoke.sh`：容器以非 root/只读根文件系统运行，删除命名卷中一个 30 天以上、带校验文件的归档，生成新加密备份并成功恢复到隔离数据库；输出 `deleted=1`。
- GitHub Actions 现先构建备份镜像，再使用该镜像运行加密备份/恢复 smoke，随后扫描镜像；远端 CI 结果尚未取得。

- 2026-11-13 L5 企业部署续验：确认 L5 为 K3s 单节点 amd64；Worker/PostgreSQL/OTel Collector/Prometheus 全部 Ready。Worker 已运行 Flyway 至 schema v6，Prometheus 查询 `up{job="myai-otel-collector"}` 返回 1，并查询到 `myai_agent_worker_queue_depth_runs`。L5 overlay 经 `kubectl kustomize deploy/overlays/l5` 渲染成功，客户端 dry-run 输出 Worker、Collector、Prometheus、PostgreSQL、PVC 与备份 CronJob 资源。Trivy config 扫描 `deploy/k8s` 无 HIGH/CRITICAL。该次检查时备份 amd64 镜像尚未构建；后续记录已完成镜像构建、导入、CronJob 部署、备份和恢复演练，取代本段中“尚未部署”的历史状态。`DOCKER_HOST=unix:///Users/gecko/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true mvn --batch-mode --no-transfer-progress clean verify` BUILD SUCCESS，134 项通过，0 失败/错误/跳过。

- 2026-11-13 L5 加密备份落地及恢复演练：Go 工具链通过固定 SHA-256 校验，使用 `GO_TOOLCHAIN_BASE_URL=https://mirrors.aliyun.com/golang GOPROXY=https://goproxy.cn,direct bash ops/fetch-age.sh amd64` 构建 linux/amd64 age 1.3.2；PostgreSQL 16 Alpine 备份镜像以 UID 10001 构建，Trivy image HIGH/CRITICAL 扫描为 0。实际镜像的加密备份/隔离恢复 smoke 成功。镜像导入 L5 containerd 后，CronJob manifest 使用 `myai-database` 凭据、30 天保留及 age 公钥；CronJob 每日 02:00（Asia/Shanghai）启用。L5 手动 Job 输出加密归档及 SHA-256，Job Complete；独立只读 PVC Reader 取回归档，本机 SHA-256 校验成功后由 age 解密流恢复至独立 PostgreSQL 容器，Flyway v6 与 4 张核心表存在。临时恢复容器、Reader Pod、本地归档和未加密流均已清理。PostgreSQL 当前未启用 TLS，备份数据库连接为集群内明文；备份与主库 PVC 均为单节点 local-path，异地复制、TLS 和恢复告警仍待完成。

- 2026-11-13 L5 备份及 OTLP 回归复验：`GO_TOOLCHAIN_BASE_URL=https://mirrors.aliyun.com/golang GOPROXY=https://goproxy.cn,direct bash ops/fetch-age.sh amd64` 使用 SHA-256 校验的 Go 1.26.11 工具链构建 linux/amd64 age；备份镜像扫描 0 个 HIGH/CRITICAL，真实镜像加密备份/隔离恢复 smoke 通过。L5 手动备份 Job Complete，归档 SHA-256 校验通过；age 解密流恢复到隔离 PostgreSQL 后确认 Flyway v6 和四张核心表。每日 CronJob 已启用、备份 PVC Bound。随后启用数据库 TLS：独立 PostgreSQL 客户端以 `verify-full` 连接成功，Worker/Flyway 启动及备份 CronJob 完成验证，且备份脚本确认 30 天保留清理已生效。Prometheus 告警规则已加载；告警接收路由未配置。严格 OTLP 回归发现原 Hikari 测试池未连数据库，因此缺少真实 Spring Boot Hikari tracker；测试改用 PostgreSQL Testcontainer 与已连接 Hikari 池后通过。完整 `DOCKER_HOST=unix:///Users/gecko/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true mvn --batch-mode --no-transfer-progress clean verify` BUILD SUCCESS，134 项通过、0 失败/错误/跳过。`bash -n`、备份安全/保留契约测试、Trivy config (`deploy/k8s` 与 `ops`) 和 `git diff --check` 均通过。Prometheus v3.16.0 仍有一项可修复 HIGH（CVE-2026-78661）；截至复核日上游最新仍为 v3.16.0，暂无上游修复版。

L5 备份 CronJob 已启用每日 02:00，保留 30 天；使用 Go 1.26.11 官方固定 SHA-256 工具链（阿里云镜像源内容哈希一致）构建 `linux/amd64` 备份镜像，Trivy 报告 0 项 HIGH/CRITICAL。L5 手动备份 Job Complete，归档 SHA-256 验证通过，并用 age 解密管道恢复到隔离 PostgreSQL；Flyway schema v6、4 张核心表存在。备份 PVC 已 Bound。L5 Worker、TLS PostgreSQL、OTel Collector、Prometheus 与每日备份 CronJob 已部署，Flyway v6、Provider Secret 文件挂载、CA/主机名校验数据库连接、备份加密及 Collector 抓取应用/Hikari 指标均已核验；Prometheus 四条告警规则已加载。备份 PVC 与 PostgreSQL 数据 PVC 均为单节点 local-path；异地复制、节点故障恢复演练、CA 签名密钥托管/轮换和告警通知路由未完成；L5 单节点恢复演练已完成，但不是生产灾备演练。Prometheus v3.16.0 存在一项未修复 HIGH（CVE-2026-78661）；按用户决定保留当前版本，不再投入升级。远端 CI 仍未验证；本地 OTLP 集成测试继续覆盖 Collector 接收和 Prometheus 指标映射。真实模型评测已完成两轮合成基线，但仍需人工回答质量评价和费用策略验证。l5 的 Kubernetes Secret 静态加密/轮换和 SQLite Secret 加密标记已于 2026-11-13 验收。旧 token-refresh RoleBinding 已核查为只允许访问一个命名 Secret，相关 CronJob suspended 且无活动 Pod；RBAC 正反向授权检查符合该范围，因此保留 Helm 管理对象。密钥扫描方面，本地 Gitleaks 检查了 Git 历史和工作区，均无命中；Trivy 配置扫描覆盖 `deploy/k8s` 与 `ops`，无 HIGH/CRITICAL 项。当前 Trivy 对 Prometheus v3.16.0 镜像另报告一项可修复 HIGH 漏洞 CVE-2026-78661，该项不属于 Kubernetes 配置扫描。

- 2026-11-13 最终 TLS/部署回归：L5 PostgreSQL 服务端已启用 TLS，PostgreSQL 客户端以 `sslmode=verify-full` 对 `myai-postgres.myai.svc.cluster.local` 完成 CA 与主机名验证；应用 Worker 经 JDBC/Hikari 建立 TLS 连接并完成 Flyway v6 校验。CronJob 使用同一信任 CA、age 加密及 30 天保留，`myai-postgres-backup-final-check` Job Complete，日志确认 `retentionDays=30 deleted=0`。L5 双副本 Worker、PostgreSQL、Collector、Prometheus 均 Ready。确认没有活动 Run 后，将 Worker 从 1 副本扩为 2，删除一个空闲 Pod 后 Deployment 自动补建，两个副本最终 Ready；这只验证空队列进程恢复，不代表在途 Run 故障恢复。Prometheus Rules API 返回 `MyAiCollectorTargetDown`、`AgentWorkerQueueStalled`、`AgentExpiredRunLeases`、`AgentDatabasePoolNearCapacity` 四条规则。单节点 Prometheus 的更新策略设为 `Recreate`，避免滚动更新时两个进程争用同一个 RWO TSDB PVC。新增 Worker 启动探针以容纳 Spring/Flyway 冷启动。`mvn --batch-mode --no-transfer-progress -Dtest=ContainerDeploymentContractTest test` 6/6 通过；`DOCKER_HOST=unix:///Users/gecko/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true mvn --batch-mode --no-transfer-progress clean verify` 134/134 通过。备份 Shell 安全与保留测试、`kubectl kustomize deploy/overlays/l5`、L5 server-side dry-run、Trivy K8s/ops HIGH/CRITICAL 配置扫描和 `git diff --check` 通过。远端 `gh run list --repo ly416123/myai --limit 10 --json ...` 返回空列表；尚未向 GitHub 推送本工作区改动，因此远端 CI/镜像发布未验收。Prometheus 官方发布页截至本记录最新正式版本为 [v3.16.0](https://github.com/prometheus/prometheus/releases)，当前镜像 Trivy 报告一项 HIGH（CVE-2026-78661）；没有扫描为零漏洞的结论。仍待异地备份目的地、告警接收端、CA 签名密钥的组织托管/轮换、节点故障恢复与真实多 Pod 故障演练、业务 SLO/保留策略确认及远端 CI 验收。

- 2026-11-13 Prometheus 指标与本地控制台复核：发现 Collector 为 Micrometer 指标增加 `myai_` 前缀，而三条 Worker/数据库告警规则此前未使用该前缀，已修正 `ops/monitoring/agent-alerts.yaml` 与 L5 Prometheus ConfigMap，并在 `ContainerDeploymentContractTest` 增加实际指标名契约。新增 `myai-agent-overview` ConfigMap，Prometheus 部署只读挂载自定义控制台模板并启用 templates/libraries 路径；未增加额外仪表盘容器。`mvn --batch-mode --no-transfer-progress -Dtest=ContainerDeploymentContractTest test` 7/7 通过；`kubectl kustomize deploy/overlays/l5` 和 `git diff --check` 通过；L5 server-side dry-run 通过，Prometheus Deployment 更新成功。现场 API 检查中 Collector target 为 `up`、四条规则健康状态为 `ok`，队列查询成功并返回 0；`/consoles/agent-overview.html` 返回已渲染的队列与 Hikari 指标页面。可用 `kubectl --kubeconfig ~/.kube/l5-k3s.yaml --context l5 -n myai port-forward svc/myai-prometheus 9090:9090` 后访问 `http://localhost:9090/consoles/agent-overview.html`。该控制台是本地只读观察页面，不提供 Grafana 的历史图表、用户权限或外部告警路由。官方 Releases 页面复核当前最新 Prometheus 正式版仍为 v3.16.0；镜像的可修复 HIGH CVE-2026-78661 仍待上游修复版本，未升级到非正式镜像。其余异地备份、接收端、CA 密钥轮换、在途任务故障演练、SLO 与远端 CI 缺口仍未解决。


- 2026-11-13 父 Run 租约续期与崩溃恢复：新增 `RunLeasePolicy`（默认租期 30 秒、每 10 秒续租）和 `RunLeaseHeartbeat`，覆盖 AgentRuntime 与 CollaborativeWorkflow；存储续租错误会重试至已确认租约有效期结束，续租确认失效后停止模型推进并中断活动请求，已启动工具仍按结果未知处理。`AGENT_RUN_LEASE_DURATION` 与 `AGENT_RUN_LEASE_RENEWAL_INTERVAL` 外置配置。新增测试覆盖长模型/规划调用期间续租、旧 fencing owner 被拒绝、失租通知只触发一次。L5 实际崩溃演练：Run `f4225e88-dee3-4790-a766-030c3eb7ce02` 在 Worker 等待合成模型响应期间，经 containerd SIGKILL 终止持租 Pod；数据库保持 RUNNING，租约过期后另一 Pod 以 fencing token 2 恢复，最终 SUCCEEDED（2 次模型调用、0 次工具调用、resumed=true，Token 总用量因中断步骤记为未知）。Provider 使用仅限演练的合成 Key，演练后已恢复默认 DeepSeek ConfigMap 端点并移除 Stub。另一次 Kubernetes 强删 Pod 触发正常取消，已明确排除为崩溃恢复证据。

- 2026-11-13 租约到期监视与 OTLP 集成回归：为 `RunLeaseHeartbeat` 增加独立的本地单调时钟到期监视任务，避免数据库续租调用阻塞心跳线程时延迟通知 Runtime；续租在本地有效期限之后才返回成功也按失租处理。新增阻塞续租确定性测试，定向 `RunLeaseHeartbeatTest` 2/2 通过。全量验收首次发现 `OtlpExporterIntegrationTest` 等待条件仅检查队列指标，可能在 Hikari 指标下一批导出前过早断言；已改为等待整组告警指标出现并保留整体超时，定向 OTLP Testcontainers 测试通过。最终执行 `DOCKER_HOST=unix:///Users/gecko/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true mvn --batch-mode --no-transfer-progress clean verify`，144 项通过、0 失败/错误/跳过，BUILD SUCCESS。该本地到期监视降低了阻塞续租调用造成的陈旧执行窗口，但数据库租约仍可能受进程暂停/主机时钟和网络故障影响；fencing 仍是最终写入保护，工具外部副作用不因中断而回滚。

- 2026-11-13 L5 租约到期监视版本部署：构建 `myai-agent:l5-20261113-runlease-watchdog`（linux/amd64），Trivy 扫描 Ubuntu 系统包与 JAR 均为 0 个 HIGH/CRITICAL；经 SSH 导入 L5 K3s containerd，Kustomize server-side dry-run 通过后应用 overlay。Worker Deployment 已滚动更新至新镜像并达到 2/2 Ready；PostgreSQL、Collector、Prometheus 和备份资源未变。
- 2026-11-13 PostgreSQL 暂时不可用时的 Run 恢复处理：故障演练首次发现，旧版 `checkCancelled()` 在模型请求等待期间读取 PostgreSQL 出错会让执行 Job 失败；持久 Run 随租约到期后由 Worker 恢复并最终完成，但额外发起了模型调用。新增 `RunStoreUnavailableException`，`PostgresRunStore.find()` 只将资源故障、瞬时及可恢复的数据访问错误包装为临时存储不可用；AgentRuntime 与 CollaborativeWorkflow 在取消轮询读取暂时失败时继续等待，由独立租约心跳作为唯一执行权依据，超过最后确认租期仍会停止执行。新增 Fake Store 测试及 PostgreSQL 离线连接/永久 SQL 错误分类测试。随后在 L5 使用合成 Provider Stub、45 秒受控响应完成 PostgreSQL Pod 重启演练：数据库约 9 秒恢复，同一 Run `569cadc5-88fd-417c-8319-77bf9771cc4a` 最终 `SUCCEEDED`，1 次模型调用、0 次工具调用、未从 Checkpoint 重放，Stub 只收到 1 次请求。最终版本 v2 部署后再次执行同一重启演练：Run `5527484b-e934-44a1-93f3-87ba64512787` 在 `myai-agent:l5-20261113-store-outage-v2` 中遇到一次 `CannotGetJdbcConnectionException` 心跳失败，数据库约 9 秒恢复后同一 Run 以 1 次模型调用、0 次工具调用完成，未恢复重放，Stub 仍只收到 1 次请求。最终执行 `DOCKER_HOST=unix:///Users/gecko/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true mvn --batch-mode --no-transfer-progress clean verify`，148 项通过、0 失败/错误/跳过；构建 `myai-agent:l5-20261113-store-outage-v2` 并经 Trivy 扫描，Ubuntu 系统包与应用 JAR 均为 0 个 HIGH/CRITICAL，Kustomize server-side dry-run 通过，L5 Worker 已滚动至该版本并为 2/2 Ready。演练后删除了临时 Job、合成 Provider Stub 和临时配置/归档；Worker 恢复为 Secret 文件 Key 与 ConfigMap Provider URL。此结果只证明一次短暂数据库重启期间的模型等待与持久 Run 保存，不证明节点级灾备、长时间数据库不可用恢复或外部工具副作用 exactly-once。
- 2026-11-13 外部依赖与 TLS 密钥边界盘点：L5 `lsblk`/`df` 显示只有一块 233.8 GiB 系统盘（根卷可用约 85 GiB），没有独立挂载的备份盘；节点未安装 rclone、AWS CLI、MinIO Client 或 restic。当前进程环境未配置 S3、邮件、Slack、Teams 或 webhook 接收端变量，GitHub 仓库 `ly416123/myai` 的 Actions 运行列表为空，本地工作区改动未发布到远端。L5 `myai-postgres-tls` Secret 只包含 `ca.crt`、`tls.crt`、`tls.key`，未包含 CA 签名私钥；工作区未发现 CA 密钥文件。当前 CA 有效期至 2036-11-10，PostgreSQL 服务器证书至 2029-02-15。不能据此声称 CA 密钥已完成组织托管或轮换；执行轮换前需先确定私钥保管位置、双 CA 信任切换/回滚安排。异地备份与告警通知还需实际外部存储和接收端，单节点本地软件不能替代故障域隔离。


- 2026-11-13 近期失败 Run 指标实现：RunStore 队列快照增加近 5 分钟 FAILED Run 数，PostgreSQL 按 `updated_at` 窗口聚合，Micrometer 暴露 Gauge `agent.worker.run.failed.recent`，Prometheus 模板新增 `AgentRecentRunFailures` 告警，控制台增加对应展示。测试覆盖内存/JSON/PostgreSQL 快照、OTLP 实际导出指标名与清单契约；全量 `DOCKER_HOST=unix:///Users/gecko/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true mvn --batch-mode --no-transfer-progress clean verify` 为 149 项通过、0 失败/错误/跳过。新版本镜像 `myai-agent:l5-20261113-runfailure-metric` 已构建并部署，Trivy 对 Ubuntu 系统包和 Java 依赖的 HIGH/CRITICAL 均为 0；Worker 2/2 Ready。ConfigMap 更新后重启 Prometheus，Rules API 确认五条规则均已加载且健康；失败 Run Gauge 返回 0，内置控制台已渲染新增行。用户明确短期不建设异地备份、CA 签名密钥故障/托管方案和告警通知投递；Prometheus 五条规则与告警状态保留，但不配置接收端或发送通知。

- 2026-11-13 Provider 出站域名收敛（已交付并通过 L5 验收）：Worker 与任务 Job 配置 OpenAI Java SDK HTTP 代理，Kubernetes namespace 增加出站默认拒绝；仅放行到 PostgreSQL、OTel Collector、DNS 和专用 Squid 代理，Squid CONNECT ACL 仅允许 `api.deepseek.com:443`，代理自身只允许公网 HTTPS 且排除 RFC1918、CGNAT、回环和链路本地网段。新增非 root/只读根文件系统代理 Deployment/Service、HTTP CONNECT 配置和 L5 本地镜像 overlay；代理镜像纳入 GitHub Actions 构建、Trivy HIGH/CRITICAL 扫描及 SHA 标记发布。客户端契约测试覆盖代理请求经过、不允许 SOCKS/非法地址；清单契约覆盖网络边界。16 项定向测试通过，Kustomize server-side dry-run 通过，Trivy 对 `deploy/k8s` 配置扫描为 0 项。代理镜像以 linux/amd64 构建并导入 L5 containerd；Trivy Ubuntu 26.04 系统包及镜像文件 HIGH/CRITICAL 为 0。L5 server-side dry-run 通过后应用策略与部署，Squid 2/2、Worker 2/2 Ready；Worker 标签探测 Pod 经代理请求 `https://api.deepseek.com/v1/models` 返回 Provider 的未认证响应 HTTP 401（表明 CONNECT 已获准并到达 Provider），代理请求 `https://example.com` 与 `https://example.api.deepseek.com` 均被拒绝 HTTP 403，直连公网 HTTPS 在 NetworkPolicy 下失败，临时探测 Pod 已删除。Squid 官方 ACL 规则确认无前导点的 `dstdomain api.deepseek.com` 仅匹配精确主机名；部署配置契约测试锁定此行为，防止放宽到子域。Prometheus readiness 为 Ready，OTel Collector target 为 up；部署日志未暴露模型请求正文、API Key 或工具参数。随后 `DOCKER_HOST=unix:///Users/gecko/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true mvn --batch-mode --no-transfer-progress clean verify` 为 151 项通过、0 失败/错误/跳过。应用实际 Run 的 Provider 请求尚未在 L5 发起，本验证覆盖代理连通和网络策略，不代表模型端到端业务调用。
- 2026-11-13 L5 入站网络隔离：新增 `myai` namespace 默认拒绝入站的 NetworkPolicy，并仅允许 Worker、一次性任务和备份 Job 访问 PostgreSQL 5432；Worker 访问 Collector OTLP 4317/4318；Prometheus 抓取 Collector 9464，Collector 抓取 Prometheus 9090。一次性 Job 增加 `component=task` 标签供策略精确选择。契约测试先因策略文件缺失而失败，新增策略后定向 `ContainerDeploymentContractTest` 8/8 通过；Kustomize 渲染和 L5 server-side dry-run 通过；Trivy K8s HIGH/CRITICAL 配置扫描 0 项。策略已应用 L5，Worker 2/2、PostgreSQL、Collector、Prometheus 均 Ready；Prometheus 仍能读取队列与近期失败指标，PostgreSQL 接受连接，Prometheus 端口转发的 readiness 检查通过。临时未授权 Pod 对 PostgreSQL 的连接探测得到 `no response`，随后删除探测 Pod。此次只限制入站，未限制 Worker/Job 出站；应用所需 Provider 出站目标尚未通过 FQDN 感知的 egress 控制收敛。Prometheus 官方 Releases 页面仍显示 v3.16.0 为最新正式版；Go 当前源码主线仍固定 `golang.org/x/net v0.61.0`。在 checksum 校验的 Go 1.26.11 工具链下，对官方 v3.16.0 源码运行 Go 官方 `govulncheck v1.9.0`（`./cmd/prometheus`）报告 0 个调用可达漏洞、5 个模块级漏洞未发现被源码调用；详细刷新因 vuln.go.dev 网络超时失败。该源码级静态结果不等同于二进制不可受影响，Trivy 仍报告镜像 HIGH（CVE-2026-78661），故未豁免扫描结果，也未部署未经上游验证的自定义二进制。

- 2026-11-13 收敛后续范围与策略：用户确认 Prometheus 继续使用 L5 当前 v3.16.0，不再投入版本升级，CVE-2026-78661 保留为已知未修复风险；Run 自动清理继续关闭，配置保持 `AGENT_RUN_RETENTION_PERIOD=PT0S`。近期用户暂缓远程推送，GitHub Actions 远端验收留待后续发布授权。精确 Provider egress 复验使用持久到检查结束的受限测试 Pod：`api.deepseek.com` CONNECT 到达 Provider 并返回无凭据 HTTP 401；`example.com` 和 `example.api.deepseek.com` 均由代理 403 拒绝；公网直连连接失败。临时 Pod 已删除。新增清单契约锁定 Squid `dstdomain api.deepseek.com` 精确主机名规则，定向清单测试 8/8 通过；完整 Maven clean verify 151/151 通过。
- 2026-11-13 L5 Worker 回滚与恢复演练：在确认无 RUNNING Run 后，将 `myai-agent-worker` Deployment 从 `l5-20261113-egress-proxy` 临时回滚到上一修订 `l5-20261113-runfailure-metric`，Deployment 达到 2/2 Ready；随后重新应用 `deploy/overlays/l5`，Worker 恢复至当前 `l5-20261113-egress-proxy` 并再次达到 2/2 Ready。恢复期间新 Pod 的 startup probe 曾短暂失败，随后通过探针并完成滚动更新；未观察到持续故障。复验时 PostgreSQL 1/1、Squid 代理 2/2、OTel Collector 1/1、Prometheus 1/1 Ready，Prometheus readiness 返回 Ready、Collector target 为 UP；数据库 Run 状态为 CANCELLED 1、FAILED 1、SUCCEEDED 5，无 RUNNING Run。该演练验证了 Worker Deployment 的版本回滚与清单恢复，不覆盖数据库 schema 回滚、节点故障、备份异地恢复，也不证明外部副作用工具具备 exactly-once。Prometheus 仍按用户决定保持 v3.16.0，已知 CVE 风险未改变。
- 2026-11-13 Prometheus 告警规则行为回归：新增 `ops/monitoring/agent-alerts-test.yaml` 和 `ops/tests/prometheus-alerts-test.sh`，调用与 L5 相同的 `prom/prometheus:v3.16.0` 容器内 `promtool test rules`；固定覆盖四条告警的 firing 延迟、注释内容与阈值边界，共 12 个状态断言，不启动监控服务、不发送通知。使用 v3.16.0 镜像本地执行脚本返回 `SUCCESS`；将队列阈值临时变异为 999 后，相同测试按预期失败，确认用例可捕获规则回归。CI workflow 增加同一测试入口，README 记录运行方式和覆盖边界。L5 只读查询 Prometheus Rules/Alerts API：五条规则（包括 Collector target）健康状态均为 `ok`，当前无 active Agent 告警。另执行 `ContainerDeploymentContractTest` 8/8 通过、`kubectl kustomize deploy/overlays/l5`、Ruby YAML 语法解析、全量 Maven clean verify（151/151）和 Trivy 配置扫描（0 项 HIGH/CRITICAL）通过；`git diff --check` 通过。`actionlint` 未安装，本次没有运行 GitHub Actions 专用 linter，也未触发远端 CI（用户暂缓推送）。该验证只证明固定指标样例下的规则表达式和状态延迟，不证明告警阈值是业务 SLO，也不验证 Alertmanager/通知投递或真实生产指标分布。
- 2026-11-13 L5 运维手册：新增 `docs/operations/l5-runbook.md` 并从 README 链接，覆盖当前拓扑边界、只读健康/Run/告警检查、Worker/数据库/Provider/监控排障、已验证 Worker 回滚、备份检查和隔离恢复安全边界。命令以 L5 当前 `l5` context 与 `myai` namespace 为依据，明确告警无通知接收端、Run 自动清理关闭、同节点 PVC 非异地备份以及未决 Run/工具副作用的恢复限制。文档撰写前复核 L5：K3s 单节点 Ready；Worker 与 egress proxy 各 2/2，PostgreSQL、Collector、Prometheus 各 1/1；3 个备份 Job 均 Complete；Run 汇总为 CANCELLED 1、FAILED 1、SUCCEEDED 5，无 RUNNING；Prometheus 5 条规则均健康且当前无 active 告警。备份 Job 结果证明当前可检查到的作业已完成，不代表 CronJob 此刻具有最近一次按期执行记录；恢复流程仍要求独立授权及 age 身份文件。该手册面向 L5 集成操作，不能代替经业务 SLO、组织数据策略和正式灾备目标审定的生产运行手册。
