# MyAi 企业级 Agent Runtime

MyAi 是面向企业应用演进的 LLM Agent Runtime，使用 Java 21 实现，核心 Agent 控制循环由项目自行维护，不依赖 Spring AI、LangChain 或 LangGraph。当前模型接入通过 OpenAI Java SDK 兼容 DeepSeek 接口。

源码仓库：[ly416123/myai](https://github.com/ly416123/myai)

## 阶段总结

- [阶段一：最小版 Java Agent Runtime（历史架构补录）](docs/stages/phase-01-summary.html)
- [阶段 1–3 企业级架构升级方案](docs/architecture/enterprise-migration-roadmap.md)
- [阶段二：有状态 Workflow、检查点恢复、基础可观测与回归评测](docs/stages/phase-02-summary.html)
- [阶段三迭代提示词：Supervisor–Worker 多 Agent 协作](docs/plans/phase-03-iteration-prompt.md)
- [阶段三：Supervisor–Worker 协作（复验通过）](docs/stages/phase-03-summary.html)

每个阶段验收通过后，都会在 `docs/stages/` 生成 HTML 总结，记录前阶段遗留问题、本阶段的解决方案与设计理由、实际效果、验收证据和剩余限制，并附上反映该阶段实际交付形态、包含分层体系的整体架构图。复杂阶段可按逻辑分层、运行部署和执行流程拆成多张图或多个文件，并从总结页明确链接。阶段总结中的架构图是各阶段验收时点的历史快照，不代表当前企业架构的完整形态，可分别查看：[阶段一历史快照](docs/stages/phase-01-summary.html#architecture)、[阶段二历史快照](docs/stages/phase-02-summary.html#architecture)、[阶段三历史快照](docs/stages/phase-03-summary.html#architecture)。当前企业化架构与交付状态以[升级路线图](docs/architecture/enterprise-migration-roadmap.md)为准。详见项目根目录的 `AGENTS.md`。

## 项目定位与架构原则

本项目按企业级系统目标设计，不是学习项目。架构决策需要考虑持久化可靠性、并发与多实例协调、故障恢复、权限与敏感数据、可观测性、可测试性、部署升级和运维边界。Docker、关系型数据库、消息队列及远程硬件均可按实际需求采用；是否引入某项设施应由一致性、吞吐、可用性和运维成本等需求决定，不预设必须使用或禁止使用。

企业化改造已落地第一批运行基线：Spring Boot 管理进程生命周期和配置，PostgreSQL/Flyway 保存 Run 与检查点，租约、fencing token 和版本条件保护并发写入；Micrometer/OTel 提供本地指标与可选 OTLP 导出。HTTP API 和生产身份认证授权短期不交付，测试使用 Fake/Mock 验证协议与边界。当前没有启用 MQ，也没有声称具备高可用。实施状态和剩余差距见[阶段 1–3 企业级架构升级方案](docs/architecture/enterprise-migration-roadmap.md)。 数据字段、存储位置、外部流向及尚待业务定级事项见[技术数据盘点](docs/security/data-inventory.md)。

### l5 环境

`l5` 指 `ly@192.168.122.55`。2026-11-13 复核为单节点 K3s v1.36.4+k3s1、4 CPU、约 7.2 GiB 内存，节点 Ready；应用容器由 K3s containerd 管理。K3s Secret 静态加密已启用并完成既有集群密钥轮换，最终状态为 `Enabled / reencrypt_finished` 且密钥哈希一致；28 个当前 Secret 对象的 value 和 old_value 均为加密数据（没有读取 Secret 内容）。

MyAI 已在独立 `myai` namespace 运行 PostgreSQL 16、双副本 Worker、OTel Collector、Prometheus、Tempo 与 Grafana，所有 Service 均为 ClusterIP；NetworkPolicy 默认拒绝该 namespace 入站和出站，只放行列明的内部服务流量。模型 Provider 请求通过专用 Squid HTTP CONNECT 代理；Worker/任务 Pod 只能连代理，代理只允许 `api.deepseek.com:443`，不能绕过代理直接访问公网。应用镜像以 `linux/amd64` 导入 L5 containerd，Provider Key 通过只读 Secret 文件挂载，模型 API Key 在 HTTPS 隧道内传输。Flyway 已运行至 schema v6，Worker、Collector、Prometheus、Tempo、Grafana 和 PostgreSQL 均 Ready。Prometheus 抓取应用、Collector 与 Tempo 指标，保留 9 条运行/可观测性告警规则；告警仅记录在本地，通知投递暂缓。MyAI 不提供数据库备份/恢复能力；PostgreSQL 数据卷或单节点损坏可能导致 Run 与检查点丢失。指标表达式使用 Collector 实际导出的 `myai_` 前缀。内置控制台 `/consoles/agent-overview.html` 展示 Worker 队列、过去 5 分钟失败 Run 数及 Hikari 连接池指标。Grafana Runtime Overview 仪表盘包含队列、租约、失败、连接池、Collector 和 Tempo 健康面板。L5 已演练 Tempo 暂停时 Collector 持久队列在 Collector 重启后恢复投递一条合成 Trace；该结果不代表节点/磁盘恢复或零丢失保证。PostgreSQL 与监控/Trace 数据存于单节点 local-path PVC，不提供节点级高可用或异地恢复。K3s Secret 静态加密已启用并完成既有对象轮换核验。PostgreSQL 已启用 TLS，Worker 通过 CA 与服务名校验连接。Prometheus 当前为 v3.16.0，Trivy 报告一项可修复 HIGH（CVE-2026-78661）；服务仅集群内可达；用户已选择继续使用当前版本，不再投入升级工作，CVE-2026-78661 作为已知未修复风险记录。

L5 的只读诊断、告警检查、Run 恢复原则、Worker 版本回滚和数据持久化限制见[运维手册](docs/operations/l5-runbook.md)。

## 环境与运行

- Java 21
- Maven 3.9+
- DeepSeek API Key

复制 `.env.example` 为 `.env`，填入本机 DeepSeek API Key 和仅供开发使用的 PostgreSQL 凭据。`.env` 已忽略，不要提交真实值。也可以将 DeepSeek API Key 单独写入项目根目录 **deepseek.key**（只放 Key 本身）。

启动本机 PostgreSQL：

    docker compose up -d postgres

加载本地配置并运行 CLI（Spring Boot 启动时自动执行 Flyway 迁移）：

    set -a; source .env; set +a
    mvn spring-boot:run

环境变量 `DEEPSEEK_API_KEY` 优先于密钥文件；`DEEPSEEK_MODEL` 默认是 `deepseek-flash`，`DEEPSEEK_BASE_URL` 默认是 `https://api.deepseek.com`，可配置兼容 API 网关地址。自定义端点只接受 HTTP(S) URL，不能包含 URL 内嵌凭据、查询参数或片段。 `AGENT_MODEL_PROXY_URL` 可设置 HTTP 出站代理（如 `http://127.0.0.1:3128`）；L5 Worker/Job 必须经由只允许 DeepSeek Provider 域名的集群代理，应用自身的代理地址由 ConfigMap 注入。数据库连接由 `AGENT_DB_URL`、`AGENT_DB_USERNAME`、`AGENT_DB_PASSWORD` 配置。当前应用运行记录写入 PostgreSQL；没有启用 HTTP API，也没有生产身份认证。

### 容器与 K3s Worker/Job

仓库提供非 root、只读根文件系统兼容的运行时镜像文件。先由 Maven 打包，再构建镜像：

    mvn -DskipTests package
    docker build -t myai-agent:dev .

短期 CLI 之外提供 `once` 单任务进程模式，供受控批任务调用；任务正文通过 `AGENT_TASK` 环境变量传入。API Key 由只读 Secret 卷挂载为文件，容器通过 `DEEPSEEK_KEY_FILE` 读取；Pod `fsGroup` 与 Secret 文件 `0440` 权限允许 UID 10001 的非 root 进程读取，不把 API Key 放入进程环境。示例 Job 位于 `deploy/k8s/base/agent-task-job.yaml`。GitHub Actions 默认分支验证成功后，会把应用和模型出站代理镜像推送到 `ghcr.io/<owner>/myai-agent:<commit-sha>` 与 `ghcr.io/<owner>/myai-model-egress-proxy:<commit-sha>`，部署清单需替换成实际 GHCR 地址和不可变 commit SHA。通用模板引用 `myai-registry` 镜像拉取 Secret；L5 overlay 使用预导入镜像。需创建 `myai-runtime` ConfigMap、`myai-agent-task`、`myai-provider`、`myai-database` 和 `myai-postgres-tls` Secrets。数据库连接配置必须将 JDBC `sslmode=verify-full` 与 `sslrootcert` 路径指向只读 CA 文件。使用本仓库 PostgreSQL StatefulSet 时，TLS Secret 还需包含与 CA 对应的 `tls.crt`、`tls.key`；连接外部 PostgreSQL 时只向应用 Pod 提供受信任的 `ca.crt`，服务器证书私钥不得挂载到应用容器。Provider Secret 可由本地文件创建，例如 `kubectl create secret generic myai-provider --from-file=deepseek-api-key=deepseek.key`。然后用 `kubectl create -f deploy/k8s/base/agent-task-job.yaml` 创建单次任务。任务 Secret 应按任务单独创建并限制读取权限，完成后删除；不要把任务正文写进 Git 清单或镜像。K3s Secret 静态加密已在 L5 启用并完成轮换核验；MyAI 默认 ServiceAccount 无 Secret 读取权限。对既有 token-refresh RoleBinding 的核查确认：权限仅限 `agentteams-chatgpt-oauth-session` 这一命名 Secret 的 get/patch；对应 Helm CronJob 当前 suspended，且没有运行中的 Pod，因此保留其 Helm 管理资源，不与 MyAI 工作负载混用。

Job 假定 PostgreSQL 已由独立服务提供；L5 的 `myai` PostgreSQL 基础资源当前由 `deploy/k8s/base/l5-postgres.yaml` 管理。Job 和 Worker 都关闭 Kubernetes ServiceAccount Token 自动挂载，因为它们不调用 Kubernetes API；后续若增加 Kubernetes API 操作，应创建最小权限专用 ServiceAccount/RBAC 后再开放。L5 仍是单节点受控集成环境，不提供节点级高可用能力。

持久 Worker 部署模板位于 `deploy/k8s/base/agent-worker-deployment.yaml`，默认 2 个副本，每个进程最多并行处理 2 个 Run。Worker 从 PostgreSQL 读取未认领/租约过期 Run，通过现有单 Agent 或 Supervisor–Worker 恢复入口执行；数据库 claim 与 fencing token 保证只有当前租约持有者能写回。设置 `AGENT_SEPARATE_TASK_WORKERS=true` 后，Worker Pod 还会轮询活跃协作 Run 中依赖已满足的子任务，由独立任务执行池领取、续租并写入任务级检查点，Supervisor 通过 PostgreSQL 状态等待结果。未设置时，协作任务仍由 Supervisor 进程内的固定线程池执行。

CLI 可使用 `/enqueue <任务>` 或 `/multi-enqueue <任务>` 将 Run 以 `CREATED` 状态持久化，交由 Worker 异步处理；`/runs` 从 PostgreSQL 按创建时间倒序查询最近 20 条，不会随运行历史增长而加载全部记录。此入口是进程内应用服务，没有 HTTP 接口或生产身份认证。启动 Run Worker 模式时设置 `AGENT_EXECUTION_MODE=worker`，并配置 `AGENT_WORKER_CONCURRENCY`、`AGENT_WORKER_POLL_INTERVAL`；启用跨进程协作子任务时再设置 `AGENT_SEPARATE_TASK_WORKERS=true`。Kubernetes Worker 模板已启用该选项。部署前将模板中的镜像和 Secret/ConfigMap 名称替换为目标环境值。数据库仍是唯一任务事实来源，不依赖 MQ。

停止本地数据库但保留开发数据：

    docker compose down

若明确要清理本地 PostgreSQL 开发数据，可使用 `docker compose down -v`；这会删除 Compose 命名卷中的全部本地 Run 数据。

CLI 命令：

- 输入普通文本：开始一个新 Run。
- /runs：查看最近 20 条运行摘要和状态。
- /tool-audit <runId>：按时间查看该 Run 的工具步骤状态变更记录；审计不包含工具参数、结果正文或异常原文。
- /resume <runId>：从检查点恢复一个非终态运行。
- /enqueue <任务>、/multi-enqueue <任务>：提交单 Agent 或协作 Run，由后台 Worker 异步消费。
- /exit 或 /quit：退出。

### Supervisor–Worker 演示（阶段三）

输入 `/multi <任务>` 显式启用多 Agent Workflow；普通文本仍走原单 Agent。Supervisor 通过一次结构化规划选择 `NO_SPLIT`（在同一父 Run 中走单 Agent 工具循环）或 `DELEGATE`。非法计划受控失败，不会回退执行。Worker 是静态注册的计算、时间和天气角色，各自只看到 allowlist 中的工具；Worker 只收到父请求、自己的任务描述和计划明确列出的依赖结果。

多 Agent 计划、Worker 子任务、步骤和汇总都归属于同一个父 Run。默认 CLI 模式下独立任务按配置并行；Worker 部署启用 `AGENT_SEPARATE_TASK_WORKERS=true` 后，子任务可由独立进程认领执行，依赖任务仍等待上游成功。父 Run 共享模型/工具总预算和 Deadline；PostgreSQL Store 通过数据库事务、父 Run 行锁和稳定 reservation ID 原子预留预算，同一调用重试不会重复计数，Worker 还有单任务预算。部分结果可用时返回 `PARTIAL` 并说明失败任务和缺失交付项；没有成功结果则为 `FAILED`。`/multi-resume <runId>` 恢复非终态父 Run：已保存的 PENDING 工具调用可继续；上次留下的 IN_PROGRESS 工具执行标为结果未知并且不重放。中断不能撤销工具副作用，也不保证 exactly-once。

规划和汇总会增加模型调用与费用；无须拆分时也需要一次 Supervisor 规划调用，再进入单 Agent 循环。默认阶段三上限为 8 个子任务、2 个并行 Worker、20 次模型调用、20 次工具调用、每 Worker 限定预算和 5 分钟 Deadline。可用 `/runs` 查看父 Run 汇总；当前应用的详细任务、Worker 及步骤状态保存在 PostgreSQL 检查点及关系投影中。

阶段三使用 Fake ModelClient、内存/JSON Store 和确定性工具做固定回归，包括计划路由与拒绝、规范化重复、依赖与并发、工具隔离、部分失败、预算、取消/Deadline、检查点恢复，以及单 Agent 与多 Agent 的相同确定性任务比较。企业存储另由 Testcontainers PostgreSQL 验证迁移、往返、竞争 claim、fencing token、版本与取消请求；Provider HTTP 契约测试使用本地 Stub，不读取真实密钥。运行全部验收：

    mvn clean test

Fake 样例的耗时仅检查字段和调度行为，不能证明真实并行提速；固定任务也不能证明真实模型质量。已对 DeepSeek Flash 连续执行两次 5 样例受控评测，两次工具选择与协作路由均符合预期；样例规模仍不足以评定自然语言回答质量或生产可用性。跨进程协调由 PostgreSQL Worker 路径支持；L5 已验证空队列 Pod 自动补建、在途 Run 的 Worker SIGKILL 后由 peer Pod 接管，以及合成模型请求期间重启 PostgreSQL 后同一 Run 成功结束。长期记忆不在当前路线图范围。L5 已部署 Collector、Prometheus、Tempo 和 Grafana，9 条 Prometheus 告警规则均已加载；包括 Worker 队列轮询失败和 Tempo 查询后端不可用告警。按用户决定，短期只保留本地规则与告警状态，不投递通知。用户已选择 Prometheus 继续使用 v3.16.0，不再投入升级；Trivy HIGH CVE-2026-78661 仍为已知未修复风险。

## Workflow 状态与恢复语义

Run 状态为 CREATED → RUNNING → SUCCEEDED/PARTIAL/FAILED/CANCELLED。每次状态变化附时间和失败类别；模型请求和工具调用各自有稳定 stepId、状态、起止时间和耗时。runId 使用 UUID。持久化的 RunType 区分 SINGLE_AGENT 与 COLLABORATIVE；`/resume` 和 `/multi-resume` 在修改检查点前校验类型，不匹配时拒绝恢复。较早 JSON 检查点缺少该字段时默认按 SINGLE_AGENT 读取。

RunStore 是存储边界，当前提供：

- InMemoryRunStore：Fake Store，用于无文件副作用的单元测试。
- JsonFileRunStore：旧的本地开发/检查点实现，保留用于阶段回归，不作为当前 Spring Boot 应用的主存储或多实例存储。
- PostgresRunStore：当前应用使用的主存储，Flyway 管理 schema；核心状态列可查询，版本化 JSONB 检查点保存完整恢复上下文，消息、步骤和子任务另有关系投影。Run 写入受事务、lease/fencing token 和乐观版本保护；协作子任务另有原子认领、到期重领和任务 fencing 序号。父 Run 租约仍保证一个 Supervisor 推进同一个父 Run；设置 `AGENT_SEPARATE_TASK_WORKERS=true` 时，子任务也可由跨进程 Worker 池独立领取和扩缩。

PostgreSQL claim 使用数据库条件更新，租约到期后新 owner 获得更高 fencing token；旧 owner 的状态写回会被拒绝。租约只阻止陈旧状态覆盖，不能撤销已发出的外部模型/工具副作用。

终态 Run 支持按保留周期自动清理，并级联删除检查点、消息、步骤和子任务投影；清理按小批事务执行，另写入不含 Run ID/正文的数据库审计记录。`AGENT_RUN_RETENTION_PERIOD` 默认 `PT0S`（禁用），用户已选择继续关闭自动清理，待数据分类、法规/合同要求和删除流程确认后再配置；设置为 ISO-8601 时长（例如 `P90D`）后，应用启动一分钟后开始定期清理，每天执行一次；每次最多处理 10 个批次，每批由 `AGENT_RUN_RETENTION_BATCH_SIZE` 控制（默认 500）。保留周期须结合数据分类、法规/合同要求和恢复窗口评估后配置，系统不替业务指定期限。

工具调用检查点分为：

1. 模型响应和全部工具请求先以 PENDING 保存。
2. 执行某个工具前，将其改为 IN_PROGRESS 并再次保存。
3. 执行完成后保存结果消息和 SUCCEEDED / FAILED / INDETERMINATE 步骤状态。

恢复时只执行 PENDING 工具。发现旧进程留下的 IN_PROGRESS 工具步骤时，系统将其视为结果未知，记录 INDETERMINATE，把“未自动重放”的错误结果交回模型，绝不默默重放。工具收到中断不等于外部副作用已经撤销；没有工具端幂等键或事务协议时，本项目不保证 exactly-once。模型请求没有工具副作用，若进程在请求中退出，恢复可能重发该请求并产生重复模型费用。

检查点需要保存消息历史和待执行工具请求，所以 PostgreSQL 检查点可能包含用户输入、模型文本、工具参数和工具结果。运行步骤/日志不会记录完整工具参数或原始异常内容；API Key 不属于 RunRecord。L5 已为 PostgreSQL 与应用 Worker 启用证书校验 TLS；仍需落实组织级 CA 私钥托管/轮换、数据库访问控制、实际 Run 保留周期和用户删除请求处理。

## 预算、重试与错误

默认 Runtime 配置：

- 最多 10 次模型请求，重试也计入总数。
- 最多 20 次工具调用。
- 最多 2 次模型重试。
- 单个任务总 Deadline 为 5 分钟。
- 单个模型请求超时由 DeepSeek 客户端设置为 60 秒。
- 单次工具等待最多 10 秒，并且不会超过任务剩余时间。

DeepSeek SDK 的内部重试已关闭，由 Runtime 统一计数。限流、服务端和网络/超时错误可在剩余模型预算和 Deadline 内重试；认证、请求格式等终止错误不重试。运行记录只保留失败类别，不持久化供应商异常正文。

工具超时会取消等待并中断执行线程，但底层工具可能忽略中断或已经完成副作用，因此步骤是 INDETERMINATE。系统不会把这种状态当成可安全重试的失败。工具执行异常也会以脱敏类别和通用错误反馈给模型。

## 可观测记录

RunRecord 和步骤记录至少包含：

- runId、stepId、状态变化与时间；
- 模型请求数、工具调用数、失败类别；
- 每个模型/工具步骤的耗时；
- 总运行时长、是否恢复；
- Token 用量：模型响应能提供时记录 prompt/completion 数；任一请求用量缺失或请求失败时，全任务 Token 总量显示为未知，不进行估算。

CLI /runs 展示简要汇总。日志只输出 ID、工具名、状态、耗时、失败类别和用量是否可知；不输出 API Key、消息正文、工具参数或原始异常文本。

Worker 轮询时刷新四个 PostgreSQL 聚合 Gauge：`agent.worker.queue.depth`（可恢复 Run 数）、`agent.worker.lease.expired`（已过期 Run 租约数）、`agent.worker.queue.oldest`（最老可恢复 Run 的等待秒数）和 `agent.worker.run.failed.recent`（最近 5 分钟失败 Run 数）。这些值不带 Run ID 等高基数标签。每个 Worker 副本观测的是同一份数据库全局队列快照，多副本聚合仪表盘应按最大值/单实例代表读取，不能把副本样本求和。Spring Boot/Hikari 还会自动绑定 `hikaricp.connections.active`、`hikaricp.connections.max` 等连接池指标。OTLP metrics/traces 默认关闭。`OtlpExporterIntegrationTest` 使用 Testcontainers 启动临时 OpenTelemetry Collector，验证真实 OTLP 推送、trace 接收，以及告警 PromQL 中的指标名与 Collector Prometheus 样本一致；Prometheus 导出名包含单位后缀，例如租约数为 `agent_worker_lease_expired_leases`。测试使用 Testcontainers PostgreSQL 和已连接的 Hikari 池验证连接池指标；该测试不部署长期监控后端。

Run 执行期间会保持 `agent.run` Observation scope；模型调用在线程间显式恢复对应的步骤 Observation。CollaborativeWorkflow 的本地并行 Worker 使用 `agent.step` 作为 Worker span，模型与工具步骤嵌套其下。回归测试验证 `Run → Worker → Model` 父子层级。独立 `separateTaskWorkers` 通过数据库领取任务时，父任务检查点会保存 W3C `traceparent`/`tracestate`，新 Worker 恢复远端父上下文后继续原 Trace；不传播 baggage，避免把业务敏感值带入任务元数据。测试验证跨 Worker 的 `Run → Worker → Model` Trace 父子关系以及 PostgreSQL JSONB 往返。JDBC 已使用 [Datasource Micrometer](https://github.com/jdbc-observations/datasource-micrometer) 与 OpenTelemetry 语义映射导出 query span；只观测 SQL 执行，默认不记录绑定参数，SQL 字面量在 trace 中脱敏，查询日志保持关闭。Testcontainers 集成测试验证 Collector 收到数据库 span 且敏感参数/字面量未导出。模型提示词、响应及工具参数不作为 span 属性。**2026-11-13 已部署到 L5，Worker 运行 `myai-agent:l5-20261113-jdbc-trace`，2/2 Ready；OTel Collector 日志确认持续收到 Trace 信号。** **2026-11-14 随后部署当前源码镜像 `myai-agent:l5-20261114-worker-instance`，Worker 2/2 Ready 且重启数为 0；镜像内 JAR 与评测数据哈希和本地构建产物一致，Flyway schema 仍为 v6，Run 汇总未变化。此镜像基于已部署 Worker 镜像追加当前 JAR/评测资源层，以适应 L5 无法直连 Docker Hub 的环境；不是从 Dockerfile 全量重建，后续应在可用 CI/镜像仓库中生成干净镜像。**

Worker 每次开始轮询时会原子更新 `/tmp/myai-worker-live`，成功完成数据库队列快照后更新 `/tmp/myai-worker-health`。Kubernetes 模板通过 `exec` 分别检查 liveness 与 readiness 标记是否在 15 秒内：数据库轮询失败会让 Pod 暂时退出 Service 就绪集合，但只要轮询线程仍运行就不会因依赖故障而自动重启；轮询线程停止则由 liveness 触发重启。该检查只适用于 `worker` 模式，不开放 HTTP 管理端口。Prometheus 告警规则统一位于 `deploy/k8s/base/agent-alerts.yaml`，覆盖 Collector/Tempo target、队列等待、过期租约、近 5 分钟失败 Run、Hikari 连接池和 Worker 队列轮询失败、队列指标缺失及单副本失联；实例心跳使用 Pod 的 `service.instance.id` 标签。Collector 指标过期窗口为 5 分钟，单副本失联告警通常约 7 分钟后触发。阈值是运维起点，不是业务 SLO。Kustomize 从该文件生成 Prometheus ConfigMap，`promtool` 测试也读取同一文件。Testcontainers 已验证本地 Collector 到 Prometheus 格式的指标名称及 `service.instance.id` 标签映射；L5 已部署单副本 Collector、Prometheus、Tempo、Grafana 和双副本 Worker，9 条 Prometheus 告警规则已加载，包含近期失败 Run、Worker 持久队列轮询失败、Worker 队列指标缺失、单个 Worker 副本失联与 Tempo target 不可用告警。**2026-11-14 L5 已部署并验证 Tempo 2.10.10（7 天保留、10 GiB 同节点 PVC）与 Grafana 12.4.15（内部 ClusterIP、匿名访问关闭）；Tempo/Grafana 均 Ready。合成 Trace 的 Collector 接收、Tempo Trace ID 查询及 Grafana Tempo 数据源均已验证。Grafana 已预置 MyAI Prometheus 数据源和 Runtime Overview 仪表盘，含队列、过期租约、近期失败、数据库连接池、Collector 与 Tempo 健康 7 个面板；仪表盘 API 返回 7 个面板，Prometheus 数据源健康检查 OK，逐条 PromQL 查询成功。** Collector 启用 1 GiB 同节点持久发送队列，发送重试最长 1 小时；Tempo/队列 PVC 都不提供节点故障恢复或备份保证。Trace 资源包含 `deployment.environment.name=l5`，Prometheus Collector/Tempo 活动目标带 `environment=l5`；跨环境规范仍待制定。后端首次不可用期间已超过原 5 分钟重试期限的 span 已丢弃，不能恢复。Grafana 关闭默认插件预装与联网更新，避免运行时动态安装。告警通知按用户决定短期暂缓，多副本队列 Gauge 汇总继续使用 max/单实例值。

Prometheus 告警规则统一保存在 `deploy/k8s/base/agent-alerts.yaml`，Kustomize 从该文件生成运行时 ConfigMap，`promtool` 确定性测试也直接读取同一文件。测试覆盖当前 9 条规则的 `for` 延迟、触发注释和阈值边界，包括 Collector 与 Tempo 目标不可用。运行 `bash ops/tests/prometheus-alerts-test.sh`；脚本默认调用与 L5 相同版本的 `prom/prometheus:v3.16.0` 镜像，也可用 `PROMETHEUS_TEST_IMAGE` 与 `PROMETHEUS_TEST_PLATFORM` 覆盖。CI 运行同一脚本。此测试验证规则表达式和 firing 状态，不验证实际告警投递、生产监控可用性或阈值是否满足业务 SLO。

## 工具与分层

- ModelClient：模型厂商适配边界；DeepSeek 实现负责协议映射、超时和模型错误分类。
- AgentRuntime：手写模型/工具循环、Run 状态机、Checkpoint、预算、恢复和日志。
- RunStore：检查点存储抽象；当前应用以 PostgreSQL 为主存储，内存 Store 用于测试，JSON Store 仅保留本地回归用途。
- AgentTool / ToolRegistry / ToolExecutor：工具契约、注册、Draft 2020-12 JSON Schema 参数校验、执行和异常分类。
- 演示工具：本地四则运算、Java 时区时间、固定模拟天气。

父 Run 的数据库租约默认 30 秒、每 10 秒续租一次（`AGENT_RUN_LEASE_DURATION` / `AGENT_RUN_LEASE_RENEWAL_INTERVAL`）。任务 Deadline 与租约分离：进程崩溃后，其他 Worker 可在租约过期后、Run Deadline 前接管。续租持续失败时，独立的本地单调时钟到期监视器会在最后一次确认的租约期限到达时停止模型推进并取消活动请求；已开始的工具可能仍产生外部副作用，恢复时将其标记为结果未知且不自动重放。租约和 fencing 防止旧执行者写入，不会撤销外部动作，也不提供 exactly-once 保证。 L5 已用合成 Provider Stub 完成在途 Run 崩溃演练：SIGKILL 结束持租 Worker 后，peer Pod 在租约到期时以 fencing token 2 接管并成功完成；中断的模型步骤按恢复语义重试，Token 总用量保持未知，工具调用数为 0。

工具选择仍由模型完成，不通过 Java 关键词分流。工具参数在本地按 JSON Schema Draft 2020-12 校验，包含嵌套、组合约束和本地引用；远程 Schema 引用会在执行侧拒绝。Provider 的工具定义仍受模型厂商支持的 Schema 子集限制，因此最终以运行时本地校验为准。

## 固定回归评测

运行全部单元及集成测试（包含临时 PostgreSQL 与 Collector Testcontainers，需要 Docker）：

    mvn test

只运行 Workflow 评测及持久化恢复样例：

    mvn -Dtest=AgentWorkflowEvaluationTest,FileCheckpointRecoveryTest,JsonFileRunStoreTest test

PostgreSQL 集成测试与 `OtlpExporterIntegrationTest` 通过 Testcontainers 启动临时容器；完整覆盖需要可用的 Docker 环境。没有 Docker 时，Testcontainers 集成测试会按配置跳过；CI 使用 Docker-enabled runner。

样例使用 Fake ModelClient、Fake/In-memory Store 和确定性工具，不读取真实 API Key，不访问真实模型。覆盖普通回答、模型可重试错误、工具调用预算、工具执行失败、任务 Deadline、取消、工具超时未知状态、PENDING 工具恢复、IN_PROGRESS 工具不重放，以及 JSON 文件存取和重启后恢复。断言检查最终状态/文本、工具类别和次数、步骤耗时/用量字段、是否恢复及失败类别。

这些固定样例验证的是已知控制流程和边界行为；它们不能证明真实模型会正确选择工具、工具结果适合业务，也不能代表 Agent 整体回答质量、真实服务可用性或生产安全性。OTLP 导出默认关闭。连接 HTTP OTLP Collector 时，分别设置 `AGENT_OTLP_TRACES_ENABLED=true`、`AGENT_OTLP_METRICS_ENABLED=true`，并通过 `AGENT_OTLP_TRACES_ENDPOINT`（默认 `http://localhost:4318/v1/traces`）和 `AGENT_OTLP_METRICS_ENDPOINT`（默认 `http://localhost:4318/v1/metrics`）配置接收地址。L5 K3s 部署包含内部 Collector、Prometheus、Tempo 与 Grafana；Prometheus 提供 Agent 指标控制台，Grafana/Tempo 提供内部 Trace 查询。该环境为单节点本地存储，不提供备份或节点恢复能力。告警通知投递按用户决定短期暂缓。Testcontainers 与 L5 合成 Trace 查询验收都不替代生产告警路由、HA、备份恢复或业务 SLO 验收。

### 显式运行真实模型评测

仓库维护了版本化、合成且不含业务数据的评测集：`eval/scenarios-v1.json` 保留历史基线，默认运行的 `eval/scenarios-v2.json` 增加了针对已知来源误述的回答短语断言。样例覆盖普通回答、计算器/天气/时区工具选择和协作规划。真实评测不由 Maven 测试或 CI 自动触发；它会向配置的 DeepSeek 模型发送这些样例，并把每个 Run 持久化到当前 PostgreSQL。只有同时显式设置评测模式和启用开关才会执行：

    set -a; source .env; set +a
    export AGENT_EXECUTION_MODE=eval AGENT_EVAL_ENABLED=true
    export AGENT_EVAL_MAX_CASES=5 AGENT_EVAL_MAX_MODEL_CALLS=16 AGENT_EVAL_MAX_TOOL_CALLS=10
    mvn spring-boot:run

评测受总样例数、总模型调用数、总工具调用数和总时限约束；剩余预算不足时会标记后续样例未运行，不会超预算继续发请求。每个 case 记录成功状态、失败类别、预期/实际工具、模型调用数、工具调用数、时长、Token 用量和协作计划摘要。配置 `requiredAnswerPhrases` / `forbiddenAnswerPhrases` 的场景会按大小写敏感的子串匹配检查回答，并把 `answerAssertionsMatched` 写入受限报告；没有配置断言时该字段为 `null`。它只验证明确写出的短语条件，不替代人工语义质量评审。Token 缺失就记为未知。可设置 `AGENT_EVAL_INPUT_USD_PER_MILLION_TOKENS` 与 `AGENT_EVAL_OUTPUT_USD_PER_MILLION_TOKENS` 提供单价，报告才会计算费用估值；这不是供应商账单金额。`manualRating` 留给人工评分；通过标准包括任务成功、工具选择、可选的协作计划及已配置的回答短语断言，不代表回答整体质量已经达标。2026-11-12 的真实模型报告中，时间查询样例调用了真实 Java 系统时钟，但回答误称其为模拟数据；工具结果来源元数据和 v2 回答短语断言已补充。2026-11-14 使用 DeepSeek Flash 对 v2 五个样例重新评测，5/5 通过，完成 9 次模型调用与 3 次工具调用，记录 4,447 个输入 Token、406 个输出 Token；时区样例的回答断言匹配。费用未知（未配置单价），人工评分仍为空；该小型评测不证明一般回答质量。

报告包含模型回答，POSIX 文件系统上使用权限为 0700 的独立目录和 0600 的文件；默认写入已加入 Git 忽略规则的 `var/agent-evaluations`，不会被 `mvn clean` 删除。如果在只读容器中运行，需要把 `AGENT_EVAL_REPORT_DIRECTORY` 指向一个专用可写挂载点。评测数据中的用户提示会写入 PostgreSQL Run 历史，执行前应确认数据库保留策略适用。该入口不读取 Fake/Mock ModelClient，也不把真实评测加入 CI。

## CI 安全扫描

GitHub Actions 在完整 Git 历史上运行 Gitleaks 凭据扫描；构建应用和模型出站代理镜像后，Trivy 会阻止存在可修复 HIGH/CRITICAL 漏洞的镜像，并检查仓库中的 Docker/Kubernetes 配置 HIGH/CRITICAL 问题。两个 Dockerfile 的基础镜像均固定到多架构 OCI manifest digest，Dependabot 每周检查根目录、`ops/` 中的 Dockerfile 基础镜像，以及 `deploy/k8s/base/` 中 Kubernetes 清单引用的镜像标签。Dependabot 仅创建待审阅的更新 PR，不会自动部署；合并前仍需审阅摘要变化并通过 CI 镜像扫描。应用依赖管理显式覆盖 Jackson BOM 2.22.3、HttpCore 5.4.4 和 PostgreSQL JDBC 42.7.14；应用镜像构建升级 Ubuntu 安全更新并移除不使用的 `pebble`。扫描不会输出密钥内容。此处只说明流水线策略；远端 CI 结果以实际运行记录为准。短期 HTTP API 与生产认证授权不在支持范围内，相关身份行为使用 Mock 进行测试，不作为生产认证能力。

CI 在 Java 构建前还会运行 Actionlint 检查 GitHub Actions 工作流语法和常见配置错误。Actionlint 固定为 v1.7.12 Linux amd64，并校验官方发布归档 SHA-256 后才执行；本地也可下载相同版本检查：`actionlint -color .github/workflows/ci.yml`。远端工作流仍需在获准推送后由 GitHub Actions 实际运行验收。

本地 Maven 构建、Prometheus 告警规则与 L5 部署契约的最近验收记录，见[企业升级路线图实施状态](docs/architecture/enterprise-migration-roadmap.md#6-实施状态与剩余交付)。GitHub Actions 远端流水线尚未运行；用户暂缓远程推送，待后续授权再验收。Prometheus v3.16.0 镜像有一项未修复 HIGH（CVE-2026-78661），按用户决定继续保留该版本。
