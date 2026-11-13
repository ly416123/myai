# MyAi 企业级 Agent Runtime

MyAi 是面向企业应用演进的 LLM Agent Runtime，使用 Java 21 实现，核心 Agent 控制循环由项目自行维护，不依赖 Spring AI、LangChain 或 LangGraph。当前模型接入通过 OpenAI Java SDK 兼容 DeepSeek 接口。

源码仓库：[ly416123/myai](https://github.com/ly416123/myai)

## 阶段总结

- [阶段一：最小版 Java Agent Runtime（历史架构补录）](docs/stages/phase-01-summary.html)
- [阶段 1–3 企业级架构升级方案](docs/architecture/enterprise-migration-roadmap.md)
- [阶段二：有状态 Workflow、检查点恢复、基础可观测与回归评测](docs/stages/phase-02-summary.html)
- [阶段三迭代提示词：Supervisor–Worker 多 Agent 协作](docs/plans/phase-03-iteration-prompt.md)
- [阶段三：Supervisor–Worker 协作（复验通过）](docs/stages/phase-03-summary.html)

每个阶段验收通过后，都会在 `docs/stages/` 生成 HTML 总结，记录前阶段遗留问题、本阶段的解决方案与设计理由、实际效果、验收证据和剩余限制，并附上反映该阶段实际交付形态、包含分层体系的整体架构图。复杂阶段可按逻辑分层、运行部署和执行流程拆成多张图或多个文件，并从总结页明确链接。当前阶段架构图可直接查看：[阶段一](docs/stages/phase-01-summary.html#architecture)、[阶段二](docs/stages/phase-02-summary.html#architecture)、[阶段三](docs/stages/phase-03-summary.html#architecture)。详见项目根目录的 `AGENTS.md`。

## 项目定位与架构原则

本项目按企业级系统目标设计，不是学习项目。架构决策需要考虑持久化可靠性、并发与多实例协调、故障恢复、权限与敏感数据、可观测性、可测试性、部署升级和运维边界。Docker、关系型数据库、消息队列及远程硬件均可按实际需求采用；是否引入某项设施应由一致性、吞吐、可用性和运维成本等需求决定，不预设必须使用或禁止使用。

企业化改造已落地第一批运行基线：Spring Boot 管理进程生命周期和配置，PostgreSQL/Flyway 保存 Run 与检查点，租约、fencing token 和版本条件保护并发写入；Micrometer/OTel 提供本地指标与可选 OTLP 导出。HTTP API 和生产身份认证授权短期不交付，测试使用 Fake/Mock 验证协议与边界。当前没有启用 MQ，也没有声称具备高可用。实施状态和剩余差距见[阶段 1–3 企业级架构升级方案](docs/architecture/enterprise-migration-roadmap.md)。

### l5 环境

`l5` 指 `ly@192.168.122.55`。2026-11-13 复核为单节点 K3s v1.36.4+k3s1、4 CPU、约 7.2 GiB 内存，节点 Ready；应用容器由 K3s containerd 管理。K3s Secret 静态加密已启用并完成既有集群密钥轮换，最终状态为 `Enabled / reencrypt_finished` 且密钥哈希一致。轮换前恢复包保存在本机 age 加密备份中；SQLite 一致性快照完整，28 个当前 Secret 对象的 value 和 old_value 均为加密数据（没有读取 Secret 内容）。

MyAI 已在独立 `myai` namespace 运行 PostgreSQL 16、双副本 Worker、OTel Collector 与 Prometheus，所有 Service 均为 ClusterIP；应用镜像以 `linux/amd64` 导入 L5 containerd，Provider Key 通过只读 Secret 文件挂载。Flyway 已运行至 schema v6，Worker/Collector/Prometheus/PostgreSQL 均 Ready，Prometheus 已抓取 Collector 与应用/Hikari 指标。L5 Prometheus 已加载五条告警规则，包含近期失败 Run 规则；指标表达式使用 Collector 实际导出的 `myai_` 前缀。内置控制台 `/consoles/agent-overview.html` 展示 Worker 队列、过去 5 分钟失败 Run 数及 Hikari 连接池指标。可通过 `kubectl --kubeconfig ~/.kube/l5-k3s.yaml --context l5 -n myai port-forward svc/myai-prometheus 9090:9090` 后访问 `http://localhost:9090/consoles/agent-overview.html`。PostgreSQL 和 Prometheus 使用各自 5Gi local-path PVC。L5 是单节点环境，这些卷不提供节点级高可用或异地恢复。K3s Secret 静态加密已启用并完成既有对象轮换核验。PostgreSQL 已启用 TLS，Worker 和备份任务通过 CA 与服务名校验连接。每日加密备份 CronJob 已启用 30 天清理；L5 手动备份与隔离恢复演练通过。Prometheus 当前为 v3.16.0，Trivy 报告一项可修复 HIGH（CVE-2026-78661）；服务仅集群内可达，等待上游修复版本后升级。旧 namespace 和 PVC 均未复用、删除。

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

环境变量 `DEEPSEEK_API_KEY` 优先于密钥文件；`DEEPSEEK_MODEL` 默认是 `deepseek-flash`，`DEEPSEEK_BASE_URL` 默认是 `https://api.deepseek.com`，可配置兼容 API 网关地址。自定义端点只接受 HTTP(S) URL，不能包含 URL 内嵌凭据、查询参数或片段。数据库连接由 `AGENT_DB_URL`、`AGENT_DB_USERNAME`、`AGENT_DB_PASSWORD` 配置。当前应用运行记录写入 PostgreSQL；没有启用 HTTP API，也没有生产身份认证。

### 容器与 K3s Worker/Job

仓库提供非 root、只读根文件系统兼容的运行时镜像文件。先由 Maven 打包，再构建镜像：

    mvn -DskipTests package
    docker build -t myai-agent:dev .

短期 CLI 之外提供 `once` 单任务进程模式，供受控批任务调用；任务正文通过 `AGENT_TASK` 环境变量传入。API Key 由只读 Secret 卷挂载为文件，容器通过 `DEEPSEEK_KEY_FILE` 读取；Pod `fsGroup` 与 Secret 文件 `0440` 权限允许 UID 10001 的非 root 进程读取，不把 API Key 放入进程环境。示例 Job 位于 `deploy/k8s/base/agent-task-job.yaml`。GitHub Actions 默认分支验证成功后，会把应用和备份镜像推送到 `ghcr.io/<owner>/myai-agent:<commit-sha>` 与 `ghcr.io/<owner>/myai-postgres-backup:<commit-sha>`，部署清单需替换成实际 GHCR 地址和不可变 commit SHA。通用模板引用 `myai-registry` 镜像拉取 Secret；L5 overlay 使用预导入镜像。需创建 `myai-runtime` ConfigMap、`myai-agent-task`、`myai-provider`、`myai-database` 和 `myai-postgres-tls` Secrets。数据库连接配置必须将 JDBC `sslmode=verify-full` 与 `sslrootcert` 路径指向只读 CA 文件。使用本仓库 PostgreSQL StatefulSet 时，TLS Secret 还需包含与 CA 对应的 `tls.crt`、`tls.key`；连接外部 PostgreSQL 时只向应用 Pod 提供受信任的 `ca.crt`，服务器证书私钥不得挂载到应用容器。Provider Secret 可由本地文件创建，例如 `kubectl create secret generic myai-provider --from-file=deepseek-api-key=deepseek.key`。然后用 `kubectl create -f deploy/k8s/base/agent-task-job.yaml` 创建单次任务。任务 Secret 应按任务单独创建并限制读取权限，完成后删除；不要把任务正文写进 Git 清单或镜像。K3s Secret 静态加密已在 L5 启用并完成轮换核验；MyAI 默认 ServiceAccount 无 Secret 读取权限。对既有 token-refresh RoleBinding 的核查确认：权限仅限 `agentteams-chatgpt-oauth-session` 这一命名 Secret 的 get/patch；对应 Helm CronJob 当前 suspended，且没有运行中的 Pod，因此保留其 Helm 管理资源，不与 MyAI 工作负载混用。

Job 假定 PostgreSQL 已由独立服务提供；L5 的 `myai` PostgreSQL 基础资源当前由 `deploy/k8s/base/l5-postgres.yaml` 管理。Job 和 Worker 都关闭 Kubernetes ServiceAccount Token 自动挂载，因为它们不调用 Kubernetes API；后续若增加 Kubernetes API 操作，应创建最小权限专用 ServiceAccount/RBAC 后再开放。L5 仍是单节点受控集成环境，不提供节点级高可用能力。

持久 Worker 部署模板位于 `deploy/k8s/base/agent-worker-deployment.yaml`，默认 2 个副本，每个进程最多并行处理 2 个 Run。Worker 从 PostgreSQL 读取未认领/租约过期 Run，通过现有单 Agent 或 Supervisor–Worker 恢复入口执行；数据库 claim 与 fencing token 保证只有当前租约持有者能写回。设置 `AGENT_SEPARATE_TASK_WORKERS=true` 后，Worker Pod 还会轮询活跃协作 Run 中依赖已满足的子任务，由独立任务执行池领取、续租并写入任务级检查点，Supervisor 通过 PostgreSQL 状态等待结果。未设置时，协作任务仍由 Supervisor 进程内的固定线程池执行。

CLI 可使用 `/enqueue <任务>` 或 `/multi-enqueue <任务>` 将 Run 以 `CREATED` 状态持久化，交由 Worker 异步处理；`/runs` 从 PostgreSQL 按创建时间倒序查询最近 20 条，不会随运行历史增长而加载全部记录。此入口是进程内应用服务，没有 HTTP 接口或生产身份认证。启动 Run Worker 模式时设置 `AGENT_EXECUTION_MODE=worker`，并配置 `AGENT_WORKER_CONCURRENCY`、`AGENT_WORKER_POLL_INTERVAL`；启用跨进程协作子任务时再设置 `AGENT_SEPARATE_TASK_WORKERS=true`。Kubernetes Worker 模板已启用该选项。部署前将模板中的镜像和 Secret/ConfigMap 名称替换为目标环境值。数据库仍是唯一任务事实来源，不依赖 MQ。

停止本地数据库但保留开发数据：

    docker compose down

若明确要清理本地 PostgreSQL 开发数据，可使用 `docker compose down -v`；这会删除 Compose 命名卷中的全部本地 Run 数据。

### PostgreSQL 加密备份与恢复

`ops/postgres-backup.sh` 使用匹配 PostgreSQL 服务主版本的 `pg_dump`，通过独立 `age` 公钥加密流式备份，不在磁盘保留明文 dump；同时生成 SHA-256 校验文件。备份目标文件不会被覆盖。恢复脚本会先校验摘要，并要求显式授权和再次确认目标数据库名；它会清理目标库中的现有对象，因此优先恢复到新建的隔离数据库验证。

先为备份建立独立密钥，私钥应放在与数据库和备份文件不同的受控位置：

    mkdir -p "$HOME/.config/myai"
    age-keygen -o "$HOME/.config/myai/backup-identity.txt"
    chmod 600 "$HOME/.config/myai/backup-identity.txt"
    age-keygen -y "$HOME/.config/myai/backup-identity.txt"

最后一条命令输出的 `age1...` 公钥作为 `AGE_RECIPIENT`。备份和恢复连接使用标准 libpq 环境变量 `PGHOST`、`PGPORT`、`PGDATABASE`、`PGUSER`，数据库密码从 `AGENT_DB_PASSWORD` 读取并写入权限为 0600 的临时 `.pgpass` 文件；生产环境默认要求 `PGSSLMODE=verify-full`。本地 Compose 默认没有 TLS，需显式设置 `PGSSLMODE=disable`。

本地备份示例（需安装 `age`、Python 3 和 PostgreSQL 客户端）：

    set -a; source .env; set +a
    export PGHOST=127.0.0.1 PGPORT="$AGENT_DB_PORT" PGDATABASE=myai PGUSER="$AGENT_DB_USERNAME"
    export PGSSLMODE=disable AGE_RECIPIENT='age1...用上一步命令输出的公钥替换...'
    ops/postgres-backup.sh "$HOME/secure-backups/myai-$(date +%Y%m%d-%H%M%S).dump.age"

恢复前先建一个空的隔离数据库。下面的操作会清除该目标数据库中已有对象：

    createdb myai_restore_check
    export PGDATABASE=myai_restore_check
    export AGE_IDENTITY_FILE="$HOME/.config/myai/backup-identity.txt"
    export AGENT_RESTORE_CONFIRM_DATABASE=myai_restore_check AGENT_ALLOW_DESTRUCTIVE_RESTORE=YES
    ops/postgres-restore.sh "$HOME/secure-backups/myai-备份时间.dump.age"

备份镜像可通过 `AGENT_BACKUP_RETENTION_DAYS` 按数据库清理过期归档；未配置时只备份、不清理。清理仅删除超过配置天数、具备校验文件且没有活动锁目录的归档，并保留缺校验文件的归档供人工调查。保留天数应按组织的数据保留要求设置。运行 `bash ops/tests/postgres-backup-retention-test.sh` 验证清理规则，Docker smoke 也会创建并确认删除一个超期归档。异地复制、备份密钥轮换和定期恢复演练仍需接入组织密钥与存储系统，本仓库脚本不会替代这些运维策略。可用 Docker 与 `age` 的开发环境先构建备份镜像，再运行 `ops/tests/postgres-backup-restore-smoke.sh`。Smoke 使用实际备份镜像以非 root 用户、只读根文件系统连接临时 PostgreSQL 16 容器，创建加密归档，再通过隔离数据库恢复并回验数据；无需本机安装 libpq。构建默认使用官方 Alpine 仓库；本地网络无法访问时可用 `--build-arg ALPINE_MIRROR=mirrors.aliyun.com/alpine` 覆盖仓库镜像，CI 仍使用默认官方仓库。不要把私钥、明文数据库密码或备份文件提交到 Git。

#### Kubernetes 定时备份模板

`deploy/k8s/base/postgres-backup-cronjob.yaml` 每天上海时间 02:00 运行加密备份，禁止并发，并限制启动延迟、单次运行时长和重试次数。先按实际 PostgreSQL 主版本构建对应客户端镜像（当前模板为 PostgreSQL 16）：

    bash ops/fetch-age.sh
    docker build -f ops/backup.Dockerfile -t myai-postgres-backup:dev .
    MYAI_BACKUP_IMAGE=myai-postgres-backup:dev ops/tests/postgres-backup-restore-smoke.sh

L5 overlay 描述每日备份配置；集群 CronJob 已启用，`myai-backup-config` ConfigMap 和 5Gi `myai-postgres-backups` PVC 已创建并 Bound。CronJob 引用既有 `myai-database` 密码 Secret，以 age 公钥加密归档，并按 30 天策略清理。PostgreSQL 已启用 TLS；Worker 与备份客户端均使用 CA 和服务 DNS 名执行 `verify-full` 校验。TLS 配置在 L5 通过 PostgreSQL 客户端握手和实际备份 Job 验证。归档 PVC 与数据库 PVC 位于同一 L5 节点，不能替代异地备份或节点故障恢复副本；用户已决定短期不建设异地备份和 CA 签名密钥故障/托管方案；告警通知接收端仍未配置，尚待确定目标。CronJob 使用预导入并扫描的 linux/amd64 备份镜像。保留清理只删除指定数据库、超过保留期、具备校验文件且无活动锁的归档。镜像使用 PostgreSQL 16 Alpine，并在构建前运行 `ops/fetch-age.sh`，从固定版本工具链构建 age CLI，工具链归档按固定 SHA-256 校验。

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

Fake 样例的耗时仅检查字段和调度行为，不能证明真实并行提速；固定任务也不能证明真实模型质量。已对 DeepSeek Flash 连续执行两次 5 样例受控评测，两次工具选择与协作路由均符合预期；样例规模仍不足以评定自然语言回答质量或生产可用性。跨进程协调由 PostgreSQL Worker 路径支持；L5 已验证空队列 Pod 自动补建、在途 Run 的 Worker SIGKILL 后由 peer Pod 接管，以及合成模型请求期间重启 PostgreSQL 后同一 Run 成功结束。长期记忆不在当前路线图范围。L5 已部署 Collector 和 Prometheus，五条告警规则均已加载；告警接收路由尚未配置。Prometheus 修复版升级尚待完成。

## Workflow 状态与恢复语义

Run 状态为 CREATED → RUNNING → SUCCEEDED/PARTIAL/FAILED/CANCELLED。每次状态变化附时间和失败类别；模型请求和工具调用各自有稳定 stepId、状态、起止时间和耗时。runId 使用 UUID。持久化的 RunType 区分 SINGLE_AGENT 与 COLLABORATIVE；`/resume` 和 `/multi-resume` 在修改检查点前校验类型，不匹配时拒绝恢复。较早 JSON 检查点缺少该字段时默认按 SINGLE_AGENT 读取。

RunStore 是存储边界，当前提供：

- InMemoryRunStore：Fake Store，用于无文件副作用的单元测试。
- JsonFileRunStore：旧的本地开发/检查点实现，保留用于阶段回归，不作为当前 Spring Boot 应用的主存储或多实例存储。
- PostgresRunStore：当前应用使用的主存储，Flyway 管理 schema；核心状态列可查询，版本化 JSONB 检查点保存完整恢复上下文，消息、步骤和子任务另有关系投影。Run 写入受事务、lease/fencing token 和乐观版本保护；协作子任务另有原子认领、到期重领和任务 fencing 序号。父 Run 租约仍保证一个 Supervisor 推进同一个父 Run；设置 `AGENT_SEPARATE_TASK_WORKERS=true` 时，子任务也可由跨进程 Worker 池独立领取和扩缩。

PostgreSQL claim 使用数据库条件更新，租约到期后新 owner 获得更高 fencing token；旧 owner 的状态写回会被拒绝。租约只阻止陈旧状态覆盖，不能撤销已发出的外部模型/工具副作用。

终态 Run 支持按保留周期自动清理，并级联删除检查点、消息、步骤和子任务投影；清理按小批事务执行，另写入不含 Run ID/正文的数据库审计记录。`AGENT_RUN_RETENTION_PERIOD` 默认 `PT0S`（禁用），设置为 ISO-8601 时长（例如 `P90D`）后，应用启动一分钟后开始定期清理，每天执行一次；每次最多处理 10 个批次，每批由 `AGENT_RUN_RETENTION_BATCH_SIZE` 控制（默认 500）。保留周期须结合数据分类、法规/合同要求和恢复窗口评估后配置，系统不替业务指定期限。

工具调用检查点分为：

1. 模型响应和全部工具请求先以 PENDING 保存。
2. 执行某个工具前，将其改为 IN_PROGRESS 并再次保存。
3. 执行完成后保存结果消息和 SUCCEEDED / FAILED / INDETERMINATE 步骤状态。

恢复时只执行 PENDING 工具。发现旧进程留下的 IN_PROGRESS 工具步骤时，系统将其视为结果未知，记录 INDETERMINATE，把“未自动重放”的错误结果交回模型，绝不默默重放。工具收到中断不等于外部副作用已经撤销；没有工具端幂等键或事务协议时，本项目不保证 exactly-once。模型请求没有工具副作用，若进程在请求中退出，恢复可能重发该请求并产生重复模型费用。

检查点需要保存消息历史和待执行工具请求，所以 PostgreSQL 检查点可能包含用户输入、模型文本、工具参数和工具结果。运行步骤/日志不会记录完整工具参数或原始异常内容；API Key 不属于 RunRecord。L5 已为 PostgreSQL、应用 Worker 和备份客户端启用证书校验 TLS；仍需落实组织级 CA 私钥托管/轮换、数据库访问控制、实际 Run 保留周期、用户删除请求处理及备份到期数据清除策略。

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

Worker 轮询时刷新三个 PostgreSQL 聚合 Gauge：`agent.worker.queue.depth`（可恢复 Run 数）、`agent.worker.lease.expired`（已过期 Run 租约数）和 `agent.worker.queue.oldest`（最老可恢复 Run 的等待秒数）。这些值不带 Run ID 等高基数标签。每个 Worker 副本观测的是同一份数据库全局队列快照，多副本聚合仪表盘应按最大值/单实例代表读取，不能把副本样本求和。Spring Boot/Hikari 还会自动绑定 `hikaricp.connections.active`、`hikaricp.connections.max` 等连接池指标。OTLP metrics/traces 默认关闭。`OtlpExporterIntegrationTest` 使用 Testcontainers 启动临时 OpenTelemetry Collector，验证真实 OTLP 推送、trace 接收，以及告警 PromQL 中的指标名与 Collector Prometheus 样本一致；Prometheus 导出名包含单位后缀，例如租约数为 `agent_worker_lease_expired_leases`。测试使用未连接真实数据库的 Hikari 池验证连接池指标，不额外启动 PostgreSQL；该测试不部署长期监控后端或告警接收端。

Worker 每次开始轮询时会原子更新 `/tmp/myai-worker-live`，成功完成数据库队列快照后更新 `/tmp/myai-worker-health`。Kubernetes 模板通过 `exec` 分别检查 liveness 与 readiness 标记是否在 15 秒内：数据库轮询失败会让 Pod 暂时退出 Service 就绪集合，但只要轮询线程仍运行就不会因依赖故障而自动重启；轮询线程停止则由 liveness 触发重启。该检查只适用于 `worker` 模式，不开放 HTTP 管理端口。Prometheus 起步告警规则模板位于 `ops/monitoring/agent-alerts.yaml`，覆盖队列等待、过期租约和 Hikari 连接池；阈值是运维起点，不是业务 SLO。Testcontainers 已验证本地 Collector 到 Prometheus 格式的指标名称映射；L5 已部署长期运行的单副本 Collector/Prometheus 和双副本 Worker，五条 Prometheus 告警规则已加载，包含近期失败 Run 规则。仍需设置环境标签、确认多副本队列 Gauge 聚合口径并配置告警路由。当前没有 Grafana、Alertmanager/告警接收端或外部集中监控后端。

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

这些固定样例验证的是已知控制流程和边界行为；它们不能证明真实模型会正确选择工具、工具结果适合业务，也不能代表 Agent 整体回答质量、真实服务可用性或生产安全性。OTLP 导出默认关闭。连接 HTTP OTLP Collector 时，分别设置 `AGENT_OTLP_TRACES_ENABLED=true`、`AGENT_OTLP_METRICS_ENABLED=true`，并通过 `AGENT_OTLP_TRACES_ENDPOINT`（默认 `http://localhost:4318/v1/traces`）和 `AGENT_OTLP_METRICS_ENDPOINT`（默认 `http://localhost:4318/v1/metrics`）配置接收地址。L5 K3s 部署包含内部 Collector 与 Prometheus；Prometheus 提供 Agent 指标控制台，但目前没有 Grafana、外部集中后端或告警接收端。Testcontainers 的临时 Collector 回归验证与 L5 内部部署都不替代生产告警路由验收。

### 显式运行真实模型评测

仓库维护了版本化、合成且不含业务数据的 `eval/scenarios-v1.json`，覆盖普通回答、计算器/天气/时区工具选择和协作规划。真实评测不由 Maven 测试或 CI 自动触发；它会向配置的 DeepSeek 模型发送这些样例，并把每个 Run 持久化到当前 PostgreSQL。只有同时显式设置评测模式和启用开关才会执行：

    set -a; source .env; set +a
    export AGENT_EXECUTION_MODE=eval AGENT_EVAL_ENABLED=true
    export AGENT_EVAL_MAX_CASES=5 AGENT_EVAL_MAX_MODEL_CALLS=16 AGENT_EVAL_MAX_TOOL_CALLS=10
    mvn spring-boot:run

评测受总样例数、总模型调用数、总工具调用数和总时限约束；剩余预算不足时会标记后续样例未运行，不会超预算继续发请求。每个 case 记录成功状态、失败类别、预期/实际工具、模型调用数、工具调用数、时长、Token 用量和协作计划摘要。Token 缺失就记为未知。可设置 `AGENT_EVAL_INPUT_USD_PER_MILLION_TOKENS` 与 `AGENT_EVAL_OUTPUT_USD_PER_MILLION_TOKENS` 提供单价，报告才会计算费用估值；这不是供应商账单金额。`manualRating` 留给人工评分；通过标准是流程成功、工具列表符合样例预期，且样例声明了协作路由/Worker 时计划也符合预期，不代表回答质量已经达标。

报告包含模型回答，POSIX 文件系统上使用权限为 0700 的独立目录和 0600 的文件；默认写入已加入 Git 忽略规则的 `var/agent-evaluations`，不会被 `mvn clean` 删除。如果在只读容器中运行，需要把 `AGENT_EVAL_REPORT_DIRECTORY` 指向一个专用可写挂载点。评测数据中的用户提示会写入 PostgreSQL Run 历史，执行前应确认数据库保留策略适用。该入口不读取 Fake/Mock ModelClient，也不把真实评测加入 CI。

## CI 安全扫描

GitHub Actions 在完整 Git 历史上运行 Gitleaks 凭据扫描；构建应用和 PostgreSQL 备份镜像后，Trivy 会阻止存在可修复 HIGH/CRITICAL 漏洞的镜像，并检查仓库中的 Docker/Kubernetes 配置 HIGH/CRITICAL 问题。应用依赖管理显式覆盖 Jackson BOM 2.22.3、HttpCore 5.4.4 和 PostgreSQL JDBC 42.7.14；应用镜像构建升级 Ubuntu 安全更新并移除不使用的 `pebble`。扫描不会输出密钥内容。此处只说明流水线策略；远端 CI 结果以实际运行记录为准。短期 HTTP API 与生产认证授权不在支持范围内，相关身份行为使用 Mock 进行测试，不作为生产认证能力。

截至 2026-11-13，最新完整 Maven 验收为 148 项通过、0 失败、0 错误、0 跳过，包含本地 PostgreSQL 与 OTLP Collector Testcontainers 集成测试、父 Run 租约心跳回归，以及存储瞬时故障处理和永久 SQL 错误分类测试。L5 overlay 的 Kustomize 渲染与 kubectl 客户端 dry-run 通过，Trivy 对 Kubernetes base 清单扫描为 0 项 HIGH/CRITICAL 配置发现。备份配置已部署：CronJob 每日上海时间 02:00 执行，30 天保留；PVC 已 Bound。L5 加密备份与隔离 PostgreSQL 恢复演练通过。新增评测 Runner 的 Fake 测试覆盖调用预算停止、工具结果和 Token 汇总、单价估值、禁用保护、版本化场景载入及协作计划摘要；调度线程生命周期测试验证定时任务线程为 daemon，且在调度上下文保持打开时 JVM 可退出；默认评测报告目录与 Maven clean 输出目录隔离；Spring 配置测试验证 Provider Secret 文件路径可由环境变量指定；Job/Worker 清单契约验证只读 Secret 文件挂载、非 root `fsGroup`、`0440` 文件权限、关闭 ServiceAccount Token 自动挂载和 GHCR 私有镜像拉取 Secret。发布工作流契约测试覆盖默认分支门禁、扫描后推送及最小包写权限。DeepSeek Flash 两次真实评测均为 5/5 样例通过、每次 9 次模型调用和 3 次工具调用；Token 分别为 4329/451 与 4329/426，未设置价格，费用估值未知。其余样例覆盖 PostgreSQL 可恢复批次查询、独立 RunStore 实例接管超时父/子租约、全量与限量 Run 列表、队列快照与过期租约统计、Hikari 连接池指标绑定、Worker 并发与类型分派及健康探针、父 Run fencing 后子任务立即安全重领、共享预算并发限额/幂等/fencing、子任务跨进程领取和续租、任务消息/步骤检查点隔离、依赖顺序、Supervisor 等待任务完成、持久取消与模型请求中断及父 Run 终态后的子任务租约收敛、子任务 Worker 亚毫秒轮询配置拒绝、JSON Schema 嵌套/组合/本地引用约束和远程引用拒绝、Provider 401/408/429/503 与请求超时分类、备份 CronJob 与监控配置部署契约、队列等待指标、任务提交、工具状态审计（含父/子任务路径及保留级联清理）、终态 Run 保留清理、数据库回滚和关闭取消。Run Store 应用镜像已在本地构建，Trivy 对当前镜像的 Ubuntu 系统包及 Java 依赖扫描均为 0 个 HIGH/CRITICAL 漏洞。最终备份镜像也通过 Trivy 漏洞扫描；实际镜像入口的非 root/只读文件系统加密备份及隔离库恢复 smoke 通过。Trivy 配置扫描覆盖 Kubernetes base 与 `ops`，无 HIGH/CRITICAL 配置项；Prometheus 镜像另有一项可修复 HIGH 漏洞。L5 Worker、PostgreSQL、Collector 和 Prometheus 已实际部署，Kustomize overlay 客户端 dry-run 通过；L5 加密备份 CronJob 已启用，PVC 已 Bound；手动加密备份和 age 解密至隔离 PostgreSQL 的恢复验证均成功。GitHub Actions 远端流水线尚未运行，不能据此确认 CI 门禁。Prometheus v3.16.0 镜像当前有一项可修复 HIGH（CVE-2026-78661），需待上游发布修复版本后升级。企业升级的已完成/待办边界见[路线图实施状态](docs/architecture/enterprise-migration-roadmap.md#6-实施状态与剩余交付)。
