# MyAI 技术数据盘点（待业务定级）

> 盘点日期：2026-11-14。本文依据当前本地源码、Flyway schema、L5 部署清单和 README 记录实际数据字段与流向。它是工程事实清单，不构成法规判断、正式数据分类或保留策略。Run 自动清理保持关闭，数据库/Trace 备份能力已按项目决定移除。

## 1. 运行数据与存储位置

| 数据项 | 产生位置 / 当前存储 | 内容与关联性 | 已知处理方式 | 待业务确认 |
|---|---|---|---|---|
| 用户输入 | CLI/内部入口；PostgreSQL `agent_run.user_input`，同时进入 Run 检查点与消息历史 | 任意自然语言，可能包含个人信息、客户/员工信息、商业秘密、凭据或受监管数据 | PostgreSQL TLS；L5 单节点 `local-path` PVC；Run 清理配置为 `PT0S`，即自动删除关闭 | 允许输入的数据类别；数据主体范围；Run 保留期限、人工删除/法律保全流程 |
| 模型消息与最终答案 | `run_message.payload`、`run_checkpoint.payload`、`agent_run.final_answer` | assistant 文本、模型工具调用参数以及最终自然语言回答；可能重复保存在检查点和投影表 | 按 Run 外键级联清理；当前无静态字段级加密或备份能力说明 | 是否允许保存原文、保存范围、脱敏/加密要求和保留期限 |
| 工具结果 | `run_message.payload` 中 role=`TOOL` 的消息；检查点中亦可能包含 | 工具返回正文。当前工具是本地计算、系统时钟和模拟天气；未来工具结果可能包含外部系统数据 | 日志和 Trace 不记录完整工具参数/结果；持久对话历史会保存模型所需工具结果 | 未来每种工具的数据分级、最小化、脱敏和删除责任 |
| 工具参数与调用请求 | assistant 消息 JSON `toolCalls.arguments`；`run_message` 与检查点 | 模型生成的 JSON 参数，可包含模型提示的复制数据或调用者输入 | Schema 校验后执行；工具审计表不复制参数；工具异常日志只记录分类/异常类型 | 对参数正文的保存必要性、字段级遮盖、访问审计及留存时限 |
| 子任务计划和结果 | `child_task.payload` | 任务描述、依赖、交付物标识、任务消息/步骤、执行结果、Trace Context；描述与结果可能含业务正文 | 与父 Run 同一事务存储并随父 Run 外键级联；Trace Context 不传播 baggage | 是否需要独立子任务访问控制/保留、Trace Context 合规要求 |
| 执行状态及预算 | `agent_run`、`run_step`、`run_budget_reservation`、`tool_execution_audit` | Run/Step/Task ID、状态、时间、模型/工具调用数、Token 数（不可用时标为未知）、租约/fencing、工具名、失败类别 | 追加式工具状态审计不含参数、结果或异常原文；Run 删除时审计行级联删除；Token 用量是可选供应商元数据 | 运维审计期限、ID 可关联性、预算/用量数据的业务归属 |
| 人工评测报告 | 默认忽略目录 `var/agent-evaluations` | 合成样例与模型回答；真实评测报告可能含 provider 返回文本、模型/工具调用统计和人工评分 | 独立目录 POSIX 权限 0700、报告文件 0600；Git 和 Docker build context 均忽略；`mvn clean` 不删除；没有自动过期清理 | 真实评测允许的数据类别、目录托管/访问者、复制/归档与删除要求 |

## 2. 外部处理、日志与遥测

| 流向 / 副本 | 实际内容 | 现有控制 | 风险或未核实事项 |
|---|---|---|---|
| MyAI → DeepSeek API | Runtime 会把当前完整模型消息历史及工具定义发送给配置的 DeepSeek 模型；HTTPS 经 L5 Squid 代理，代理只允许 `api.deepseek.com:443` | Provider Key 以只读 Secret 文件挂载；代理不记录请求正文；模型适配器日志只记录模型名、消息/工具数量 | Provider 侧的数据留存、训练使用、处理地域、合同与组织审批未由本仓库验证。部署前须确认输入内容获准发送到外部 Provider |
| 应用结构化日志 | Run/Step/Task/Worker 标识、状态、耗时、已注册工具名、失败类别、异常类型、Token 是否已知等 | 不记录完整提示词、模型响应、工具参数/结果正文或供应商异常正文；模型返回的未注册工具名统一替换为 `unregistered`；日志输出至标准输出 | 容器日志平台/节点日志轮转和保留策略不由本项目配置确认；Run ID 等标识可与业务记录关联，需确定访问与期限 |
| OpenTelemetry Trace / Tempo | Trace/Span ID、运行/步骤关联、阶段、状态与耗时等；L5 Trace 存在 Tempo | 不把 prompt、response、工具参数作为 Span 属性；单节点 Tempo 本地 PVC，保留 7 天 | Trace ID 可与 Run 关联；Tempo PVC 无备份/节点恢复保障；外部导出端启用前需审查接收方和跨境/访问策略 |
| Prometheus 指标 | 低基数流程指标、状态、队列、Hikari 连接池及服务目标；保留 7 天 | 指标标签不使用 `runId` 等高基数标识；L5 仅 ClusterIP | 规则暂不投递通知；单节点本地 PVC；阈值不等于获批 SLO |
| Collector 持久队列 | 尚待投递的遥测批次，L5 使用本地 1 GiB 文件队列，发送重试窗口最长 1 小时 | PVC 同节点；队列用于短时重试，不是长期审计或灾备 | 内容可能继承 Trace 属性；队列磁盘权限/剩余空间与超过重试期限后的丢弃须纳入运行监控；无节点恢复保障 |

## 3. 凭据与基础设施数据

- DeepSeek API Key 和 Grafana 管理员密码由 Kubernetes Secret 提供；Worker/Job 按文件或 Secret 引用读取。本地开发还支持 `.env`/`deepseek.key`，由 Git 忽略规则禁止提交，并由 Docker build context 忽略规则排除；`.gitignore` 与 `.dockerignore` 也排除常见证书/密钥扩展名、Java keystore、Kubernetes 配置以及 `.kube/`、`.ssh/` 目录。评测报告和运行时数据目录同样排除。L5 K3s Secret 静态加密已启用并完成轮换核验。不要写入 Git、Run、日志、Trace 或评测报告。
- PostgreSQL TLS CA 证书供 Worker `verify-full` 校验服务身份；签名私钥托管/故障轮换按当前决定暂缓。证书过期与私钥故障的组织级响应尚未形成流程。
- PostgreSQL、Prometheus、Tempo 和 Collector 队列使用 L5 单节点 local-path PVC。应用未实现字段级/数据库级静态加密；底层节点磁盘加密状态未由本项目核验。K3s Secret 静态加密只保护 Kubernetes Secret 对象，不代表 PostgreSQL PVC 加密。备份功能已移除；卷/节点损坏可能造成 Run、检查点和遥测数据不可恢复。不要把 PVC 或 Collector 队列当作备份。
- Grafana 当前使用临时本地数据目录；Pod 重建后用户/状态不保证保留。仪表盘和数据源配置由 Git 管理的 provisioning 文件提供。

## 4. 当前工程策略与业务决策边界

1. **当前可确定的工程默认值：** Run 自动清理关闭（`AGENT_RUN_RETENTION_PERIOD=PT0S`）；Prometheus 与 Tempo 各保留 7 天；Collector 队列 1 GiB、单批发送重试最多 1 小时；评测报告本地权限 0700/0600 但无自动删除。不同数据类型的时间窗口不可互相替代。
2. **当前不具备的能力：** 数据库/Trace 备份恢复、节点级 HA、Run 用户级删除入口、字段级静态加密承诺、外部 Provider 数据留存核验、组织级数据分类和数据主体请求流程。
3. **启用自动删除的门槛：** 业务/法务/安全负责人确认各类 Run 内容、状态元数据、审计事件、评测报告、日志和 Trace 的分类、保留期限、删除例外与责任人；随后才可为各存储制定并测试明确策略。现有 Run 清理器删除整条到期终态 Run 及关联消息/步骤/子任务/工具审计，不提供按字段或个人标识删除。
4. **外部模型调用门槛：** 由数据所有者确认提示词、历史上下文、工具定义和可能的工具结果可以传给 DeepSeek，并确定 Provider 合同/区域/保留条款；NetworkPolicy 和 TLS 只能控制网络路径与传输保护，不能替代该审批。
5. **决策责任建议：** 业务数据所有者明确用途与分类，法务/隐私负责人核对合同及法定义务，安全/平台负责人确认存储、访问与删除控制；没有责任人和期限前，继续保持 Run 自动清理关闭。
6. **运行日志与遥测门槛：** 接入集群外的日志/OTLP 接收端前，检查属性白名单、Run ID 可关联性、接收端权限/区域与保留期限，并使用合成数据做泄露回归。

## 5. 来源与复核入口

- PostgreSQL schema：`src/main/resources/db/migration/V1__create_run_store.sql` 至 `V6__append_tool_execution_audit.sql`。
- 持久化/级联：`src/main/java/com/example/agent/workflow/PostgresRunStore.java` 与 `RunRecord.java`。
- 消息/工具协议：`src/main/java/com/example/agent/model/Message.java`、`ToolCall.java`、`src/main/java/com/example/agent/collaboration/ChildTaskRecord.java`。
- Provider 请求与脱敏异常分类：`src/main/java/com/example/agent/client/DeepSeekModelClient.java`。
- 评测报告权限和存储：`src/main/java/com/example/agent/evaluation/AgentEvaluationRunner.java`；运行说明见 README 评测章节。
- L5 当前部署、存储和删除边界：`docs/operations/l5-runbook.md`。
