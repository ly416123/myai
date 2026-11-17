# 阶段五需求：Worker 能力注册与子任务数据契约

> **文档状态：** 阶段五 Worker 契约已完成范围验收（2026-11-17）。本文保留原始需求边界；交付事实、验收证据和企业工程限制见[阶段五实施计划](phase-05-implementation-plan.md)与[阶段五总结](../stages/phase-05-summary.html)。

## 阶段五启动前置里程碑：阶段四交付闭环

阶段五 Worker 契约实现开始前，必须先完成此里程碑，关闭阶段四“本地功能验收通过、远端与 L5 尚未验证”的交付缺口。该里程碑不增加阶段四功能，也不改变数据库备份、认证、告警通知等已确认范围。

### 工作项

1. 审核当前阶段四工作区改动，确认代码、Flyway V7、测试、README、阶段总结与实现计划相互一致；保留并区分已有修改，不覆盖无关工作。
2. 在本机按资源约束执行完整 Maven `clean verify`、Prometheus 告警规则测试、镜像 digest 检查、Kustomize 渲染和 `git diff --check`；记录实际测试数及跳过数。
3. 将审核后的阶段四交付提交到受保护的 GitHub 工作流，等待当前提交对应的 `verify` 成功；若主分支发布工作流适用，确认 Runtime 和代理镜像的 SHA 标签与 amd64 manifest 可读取。不能用历史提交的 CI 结果替代本次结果。
4. 部署前重新检查 L5 节点、MyAI 工作负载、资源 requests/limits、PVC 和 PostgreSQL schema；只变更 MyAI 命名空间中阶段四交付涉及的应用，不触碰其他命名空间。
5. 将与本次已验收提交一一对应的不可变镜像版本部署至 L5，确认 Worker rollout、PostgreSQL Flyway schema V7、评测表可查询、Prometheus/OTel 健康和无意外 Run/Worker 变化。使用不调用真实模型的有界部署检查；不得向 L5 提交真实用户问题或泄露密钥。
6. 若部署失败，停止进入阶段五，先按证据修复并重新验收；不删除 PVC 或运行数据，不假设数据库回滚可逆。

### 通过条件

- 当前阶段四提交的本机全量验证通过，Testcontainers 集成测试实际运行且无意外跳过。
- GitHub Actions 对同一提交的 `verify` 通过；要求发布时，两张镜像的 SHA 标签与 manifest digest 均完成核验。
- L5 已部署与验收提交匹配的应用镜像，Flyway 为 V7，Worker、数据库和观测组件健康；数据库迁移及部署检查没有造成已有 Run 数据变更。
- 阶段四总结和路线图记录实际提交、CI run、镜像摘要、部署状态及验证命令；只有全部条件满足后，才把阶段五状态从“候选”改为“可启动”。

**当前状态：前置里程碑已完成（2026-11-17）；阶段五实施中。** 阶段四提交 `fe209355ddfcce9538b911a4b94ac3517a575b1d` 的本机 `clean verify` 为 204 项通过、0 失败/错误/跳过；GitHub Actions run [43977781637](https://github.com/ly416123/myai/actions/runs/43977781637) 的 `verify` 与 `publish-images` 均成功。GHCR Runtime 镜像 amd64 manifest 为 `sha256:2a72c184118cad308aa207100126891d2fedd4bbee8010f29b016454a19ea43f`，代理镜像 amd64 manifest 为 `sha256:2069428a52d8b947402d18960a7d283753fdbc63cd4f346a854351d2c4ba0556`。L5 的 Worker 已按 GHCR SHA 标签完成 2/2 滚动；数据库成功迁移至 V7，评测表可查询，既有 12 条 Run 及状态分布未改变；Prometheus 10/10 条规则健康、2/2 targets UP、无 firing 告警，Tempo 和 Grafana 健康。详细证据和镜像导入说明见[阶段四总结](../stages/phase-04-summary.html)与[企业升级路线图](../architecture/enterprise-migration-roadmap.md)。

## 1. 背景与当前缺口

MyAi 当前在应用启动时静态配置协作执行 Worker，并将 Worker 标识、职责说明、系统提示词和允许使用的工具提供给 Supervisor。Supervisor 根据用户请求生成子任务计划，Worker 执行时收到原始父请求、自己的任务说明和直接依赖任务的结果。

当前 Worker 定义没有结构化的必需输入、可选输入和输出契约；子任务计划也没有显式记录输入字段及其来源。因此，Supervisor 主要依靠模型从自然语言中推断 Worker 需要什么，运行前无法由程序可靠检查必需信息是否齐全、依赖结果是否按约定传递，也无法统一校验 Worker 交付物。

## 2. 本需求目标

为静态注册的执行 Worker 增加可校验的能力描述和数据契约，使系统能够：

1. 在规划时向 Supervisor 提供适合当前请求的 Worker 能力摘要和输入要求。
2. 在派发前确定每个子任务输入字段的来源，并由程序校验必填项、类型和依赖引用。
3. 缺少必填信息时停止不完整计划的派发，明确报告缺失字段，避免静默编造输入。
4. 按契约校验 Worker 的结构化交付物，并以交付物而不是 Worker 全量对话历史供后续任务和 Supervisor 使用。
5. 将使用的 Worker 能力/契约版本随计划持久化，使恢复时能发现定义变化并采取明确行为。

## 3. 职责边界与术语

- **WorkerRegistry / WorkerSpec：** 保存可委派的执行能力定义，包括稳定 Worker 标识、能力版本、职责摘要、输入契约、输出契约和允许使用的工具标识。初期沿用应用启动时的静态装配，不要求新建数据库注册中心。
- **Supervisor：** 查看候选 Worker 的能力信息，提出结构化任务计划和字段绑定。Supervisor 不作为普通执行 Worker 注册，也不进入可分派 Worker 列表。
- **Workflow / 调度逻辑：** 校验计划、DAG、依赖引用和预算；在依赖结果可用后解析字段绑定，再将完整且通过校验的输入交给匹配的 Worker。
- **Worker：** 通过独立子任务上下文执行，并按输出契约交付结果。Worker 的系统提示词用于说明职责、行为和输出指导，不能替代程序可验证的数据契约。
- **ToolRegistry：** 继续只管理可执行工具及其各自的参数 JSON Schema、实现和执行策略。Worker 契约描述子任务层面的业务输入/交付物，不汇总或替代各工具的参数 Schema。

## 4. 功能需求

### 4.1 静态 Worker 能力定义

1. 为 Worker 定义结构化输入字段：字段名、类型、说明、是否必需，以及必要的格式/枚举/范围约束。
2. 为 Worker 定义输出契约：交付物标识、结构或 Schema、必要字段和说明。需要传给依赖任务的结果必须可被稳定引用。
3. Worker 能力定义声明允许的工具集合；能力摘要和工具 allowlist 由程序侧注册信息产生，不能由模型自行扩大。
4. 注册时校验 ID 唯一、契约合法、工具引用存在、契约版本有效；非法配置应在应用装配或启动校验阶段失败并给出不含敏感内容的诊断。
5. Worker 定义以静态配置/代码装配为首期实现。运行时实例发现、心跳、容量上报和独立能力注册服务不属于本项需求。

### 4.2 计划输入绑定与派发前校验

1. 结构化计划为每个子任务声明输入绑定，至少支持：
   - 从父任务请求/经批准的任务背景提取；
   - 从明确声明的直接依赖 Worker 交付物字段读取。
2. 依赖绑定必须引用计划中存在的直接依赖及其已声明输出字段；禁止引用无关任务、未声明字段或形成循环依赖。
3. Workflow 在任务可执行前解析绑定，并按 Worker 输入契约校验必填、类型及约束；校验失败时不得启动该 Worker 或其工具调用。
4. 缺少必需输入时返回机器可读的缺失字段和来源信息，阻止计划继续。除非调用接口明确支持用户澄清，本阶段不要求新增交互式澄清状态；不得让模型用猜测值静默填补必填项。
5. 对可选字段可缺省；若采用计划明确声明的假设，必须保留假设内容和来源/理由，并使其可检查。是否允许特定 Worker 接受假设由静态能力定义决定。

### 4.3 上下文与数据传递

1. 注册表完整定义仅在应用侧供 Supervisor、Workflow 和调度逻辑读取，不把整份注册表无条件加入模型上下文。
2. 规划模型只获得当前规划所需的 Worker 能力摘要、必要输入字段元信息及输出简介；完整 JSON Schema 主要由程序执行校验。确需模型生成结构化字段绑定时，只提供候选 Worker 对应的契约子集。
3. Worker 执行上下文包含任务说明、经契约筛选并校验的输入字段、完成标准和直接依赖的必要交付物；不默认复制父 Agent 的完整消息历史，也不拼接其他 Worker 的完整对话。
4. Worker 完成后持久化其结构化交付物和独立执行历史。下游 Worker 与 Supervisor 默认只接收契约定义的交付物；需要排查细节时通过父 Run/子任务标识回查执行记录。
5. 记录上下文构造所依据的能力/契约版本和绑定引用，避免恢复时仅凭当前提示词重新解释旧任务输入。

### 4.4 版本与恢复语义

1. 每个 Worker 能力定义有稳定版本或内容指纹；子任务计划持久化实际使用的版本/指纹及解析后的输入快照或等价的安全恢复数据。
2. 恢复时若能力定义版本发生变化，系统不得静默使用新契约重解释已经持久化的计划。对未开始的子任务，采用显式定义的兼容策略；若无法证明兼容则停止并记录稳定失败类别，等待人工/新 Run 处理。
3. 已开始且执行结果未知的工具步骤仍遵循现有 INDETERMINATE/不自动重放语义。增加输入/输出契约不得改变现有副作用恢复边界，也不得宣称 exactly-once。

## 5. 主要数据结构方向

实现可按现有 Java 领域模型演进，名称不强制：

```text
WorkerSpec
  workerId, version, description, systemPrompt, allowedToolIds
  inputSchema, outputSchema

PlannedTask
  taskId, workerId, workerVersion, description, dependencies
  inputBindings, acceptanceCriteria, deliverableIds

ChildTaskRecord
  resolvedInput, structuredOutput, executionMessages, steps, status
```

`inputBindings` 应使用明确来源表达式，而不是把整个主上下文拼成任意文本。例如：`city <- parentRequest.city`，`route <- dependency(task-route).output.route`。若现有父请求不是结构化数据，第一版可以由 Supervisor 生成候选提取结果，再由程序按契约校验；对无法可靠结构化或缺失的必填项应拒绝派发。

## 6. 验收要求

- Worker 静态注册表能加载有效定义，并拒绝重复 ID、非法 Schema、未知工具引用或不匹配版本。
- Supervisor 的规划上下文只包含被选为候选的能力信息；测试证明不会无条件拼入全部 Worker 系统提示词或消息历史。
- 直接依赖的输出绑定可解析并通过字段/类型校验；缺失必填值、引用不存在、非直接依赖引用和循环依赖均在 Worker/工具启动前被拒绝。
- Worker 成功结果符合输出契约并能作为下游输入；不符合契约时按受控失败记录，不污染父上下文。
- 父 Agent 与各 Worker 的消息上下文保持隔离；父级汇总使用结构化交付物，不默认并入子 Worker 全量聊天记录。
- 检查点恢复覆盖契约版本一致、版本变化不兼容、未开始任务和执行结果未知边界；未知副作用不自动重放。
- 使用 Fake ModelClient、Fake Store/或 PostgreSQL Testcontainers 和确定性 Worker 完成回归，不读取真实密钥或访问真实模型 API。
- 阶段验收需测量并记录计划输入大小或等价上下文裁剪证据；没有实际测量时不得宣称已降低 Token 或费用。

## 7. 本需求明确不包含

- Supervisor/主 Agent 作为执行 Worker 注册或递归 Supervisor 层级。
- 动态 Worker 服务发现、实例心跳/容量注册中心及独立注册服务。
- 每种业务场景另建一套工具总 Schema；工具仍逐个维护自己的参数 Schema。
- 自动摘要完整父/子对话、向量检索或长期记忆。
- 引入 MQ、改变现有数据库任务派发方式、HTTP API、生产认证授权或 Web UI。
- 将模型生成的计划/字段绑定视为可信输入；所有绑定仍需程序验证。
