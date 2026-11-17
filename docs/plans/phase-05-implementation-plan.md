# 阶段五 Worker 契约实施计划

**目标：** 为静态注册的协作 Worker 增加版本化输入/输出契约，使 Supervisor 提案可被校验、派发输入有来源、Worker 输出可结构化传递，并让恢复对契约版本变化 fail closed。

**架构：** 继续使用现有 `WorkerRegistry`、`TaskPlanValidator`、`CollaborativeWorkflow` 与 `ChildTaskRecord`。WorkerSpec 持有 JSON Schema Draft 2020-12、职责摘要、输入/输出简介和显式版本；Supervisor 只看到简要能力字段，不接收系统提示词或完整 Schema；Workflow 在启动 Worker 前解析并验证输入，Worker 结果需符合输出 Schema 后才可持久化为成功交付物。父 Run 检查点继续由现有 RunStore/PostgreSQL JSONB 保存，本阶段不新增表、不引入 MQ。

**技术栈：** Java 21、Jackson、现有 NetworkNT JSON Schema Validator、JUnit 5、Mockito、PostgreSQL Testcontainers。

---

## 设计决策与范围

1. Worker 能力在应用启动时静态装配；不新增运行时注册中心或实例发现。
2. Schema 使用项目已依赖的 Draft 2020-12 JSON Schema 校验器；Schema 仅允许文档内引用，不允许远程 `$ref`。
3. 每个 Worker 显式声明版本。Planner 不能指定版本，运行时在计划校验后写入实际注册版本；恢复时记录版本必须与当前静态 WorkerSpec 一致，否则以稳定失败类别终止，不重解释旧计划。
4. `InputBinding` 只允许 `PARENT_REQUEST` 或 `DEPENDENCY_OUTPUT`。父请求当前是自由文本，模型可以提出候选抽取值并标记来源，程序负责必填字段、类型和约束校验；这不能证明语义来源真实，报告中明确这一限制。依赖字段必须来自同一计划中直接依赖任务的结构化输出 JSON Pointer。
5. 每个 Worker 的最终回答必须是符合其 `outputSchema` 的 JSON object。通过校验后，将规范化 JSON 字符串写入现有 `ChildTaskRecord.result`/PostgreSQL JSONB payload，既满足旧投影结构，也避免增加关系列；子任务消息仍单独保存以供检查。
6. Supervisor 汇总只使用任务状态和符合契约的结构化输出；不把 Worker 对话历史拼入父上下文。预算、租约、fencing、工具权限及 `INDETERMINATE` 不重放语义保持不变。
7. 当前三项静态 Worker 均可作为 Planner 候选，但只提供 ID、职责摘要、字段名/类型/必需性、输出简介和工具能力；不发送 systemPrompt、全量 Schema 或历史消息。规划 token 减少只在实际测量后报告；CI 只检查规划输入中不存在这些完整字段。

## 文件清单与职责

- 修改 `src/main/java/com/example/agent/collaboration/WorkerSpec.java`：增加版本、规划摘要、输入 Schema、输出 Schema、输出简介和防变更复制。
- 修改 `src/main/java/com/example/agent/collaboration/WorkerRegistry.java`：注册时校验 ID、版本和 Schema，提供稳定的规划摘要列表；现有 `CollaborativeWorkflow` 启动校验继续负责工具引用。
- 修改 `src/main/java/com/example/agent/run/FailureCategory.java`：新增稳定的 `WORKER_CONTRACT_MISMATCH` 失败分类。
- 新建 `src/main/java/com/example/agent/collaboration/InputBinding.java`：表达父请求提取值或直接依赖输出 JSON Pointer。
- 修改 `src/main/java/com/example/agent/collaboration/PlannedTask.java` 与 `TaskPlan.java`：加入输入绑定、完成标准和运行时标注的 Worker 版本。
- 新建 `src/main/java/com/example/agent/collaboration/WorkerContractValidator.java`：编译并校验输入/输出 Schema、解析绑定、校验直接依赖和输出 JSON。
- 修改 `src/main/java/com/example/agent/collaboration/TaskPlanValidator.java`：在执行前校验绑定字段、依赖引用、Schema 必填字段和 DAG。
- 修改 `src/main/java/com/example/agent/collaboration/ChildTaskRecord.java`：持久化使用的 Worker 版本和已解析输入快照；结构化输出使用现有 result 字段。
- 修改 `src/main/java/com/example/agent/collaboration/CollaborativeWorkflow.java`：规划请求只发摘要；派发前解析并持久化输入；为 Worker 提供单个契约和筛选后的直接依赖字段；验证 Worker 输出；恢复时比较版本。
- 修改 `src/main/java/com/example/agent/AgentConfiguration.java`：为 Calculator/Clock/Weather Worker 声明显式版本和 Schema。
- 修改 `src/test/java/com/example/agent/collaboration/CollaborativeWorkflowTest.java`、`FileCollaborativeRecoveryTest.java`，新建 `WorkerContractValidatorTest.java` 与 `WorkerRegistryContractTest.java`：Fake 模型下覆盖合法路径、非法输入/输出、上下文隔离、版本变化、恢复及无工具副作用。
- 修改 `src/test/java/com/example/agent/workflow/PostgresRunStoreTest.java`：Testcontainers 验证新增检查点字段往返及现有 child_task JSONB 投影。
- 修改 `docs/plans/phase-05-requirements.md`、`README.md`、`docs/architecture/enterprise-migration-roadmap.md`；验收通过后新增 `docs/stages/phase-05-summary.html`，画阶段五实际分层架构图。

## 任务 1：WorkerSpec 注册契约

**测试：** 新建 WorkerRegistryContractTest，先写以下行为：

```java
@Test
void rejectsInvalidWorkerSchemaAndUnknownToolAtRegistration() {
    WorkerRegistry registry = new WorkerRegistry();
    assertThrows(IllegalArgumentException.class, () -> registry.register(worker(
            "bad-schema", "v1", objectMapper.readTree("{\"type\":\"string\"}"),
            validOutputSchema(), Set.of("missing-tool"))));
}
```

- [x] 确认测试因现有 WorkerSpec 缺少契约字段/注册校验而失败。
- [x] 实现版本化 WorkerSpec、Schema 副本隔离、Draft 2020-12 编译；工具引用仍由 CollaborativeWorkflow 启动校验。
- [x] 覆盖空 ID、空版本、非法根 Schema、远程 `$ref`、有效 Schema 与 Schema 副本隔离；重复 ID 沿用既有注册表行为。
- [x] 运行 `mvn --batch-mode --no-transfer-progress -Dtest=WorkerRegistryContractTest test`，3 项通过。

## 任务 2：计划绑定和 Schema 校验

**测试：** 新建 WorkerContractValidatorTest，至少明确 required/type、未知目标字段、重复目标字段、非直接依赖、缺失 JSON Pointer 和合法绑定：

```java
@Test
void rejectsMissingRequiredWorkerInputBeforeExecution() {
    var input = objectMapper.createObjectNode().put("city", "Beijing");
    var schema = objectMapper.readTree("""
            {"type":"object","required":["city","date"],"properties":{
              "city":{"type":"string"},"date":{"type":"string"}}}
            """);
    assertFalse(validator.isValidInput(schema, input));
}
```

- [x] 实现 InputBinding 两种来源、JSON Pointer 解析、输入 Schema 与输出 Schema 校验；非法绑定写入脱敏、机器可读的 WorkerContractIssue。
- [x] 在 TaskPlanValidator 中校验字段、直接依赖关系、生产者输出 Schema 中 JSON Pointer 声明和必填字段覆盖。
- [x] Fake 用例覆盖 required/type、未知目标字段、重复目标字段、非直接依赖、未声明 JSON Pointer、输出字段筛选和合法绑定。
- [x] 定向运行 `mvn --batch-mode --no-transfer-progress -Dtest=WorkerContractValidatorTest,WorkerRegistryContractTest test`，8 项通过。

## 任务 3：计划持久化与版本语义

- [x] 为 TaskPlan/PlannedTask 增加输入绑定、完成标准和运行时 Worker 版本；旧字段可反序列化，活跃旧任务因缺失版本在执行前 fail closed。
- [x] 为 ChildTaskRecord 增加 `workerVersion` 与深复制的 `resolvedInput`；状态变更保留两个字段。
- [x] File/JSON 与 PostgreSQL Testcontainers 往返测试覆盖版本、绑定、完成标准、解析输入、机器可读错误和规划字节指标。
- [x] PostgreSQL 测试确认新增字段写入现有 JSONB 检查点/子任务 payload，无需 SQL migration；`PostgresRunStoreTest` 20 项通过。

## 任务 4：Supervisor 规划上下文与 Worker 执行流

- [x] Fake ModelClient 覆盖 Planner 只收到能力摘要和输入/输出字段列表，不含 systemPrompt、完整 Schema 或 Worker messages。
- [x] Planner JSON 结构化输入绑定与 acceptanceCriteria，并由程序校验 required、来源和输出字段。
- [x] 将注册版本写入已验证计划与 pending ChildTaskRecord，再持久化计划。
- [x] Worker 可运行时解析父请求候选值与依赖 JSON Pointer，只传绑定字段；已解析输入与初始 messages 在任何 Worker 模型/工具调用前持久化。
- [x] Worker prompt 附本 Worker 输出 Schema；最终回复必须是匹配 Schema 的 JSON object，否则受控失败。
- [x] 汇总使用结构化结果和失败状态；RunRecord 记录安全的 UTF-8 规划消息体大小，不估算 Token 或声称费用下降。
- [x] `CollaborativeWorkflowTest` 覆盖过滤上下文、缺少 required 不派发、无效输出、版本不匹配零模型/工具调用。

## 任务 5：恢复、并发与回归

- [x] 执行 `PENDING` / `IN_PROGRESS` 子任务前核对保存版本；不匹配时 `WORKER_CONTRACT_MISMATCH` fail closed，不调用模型/工具。
- [x] Fake 模型和计数型工具证明版本不匹配恢复期间模型/工具调用均为零。
- [x] File/JSON 和 PostgreSQL 测试覆盖合法检查点中解析输入/消息字段的往返；JSON Pointer 只提取声明字段。
- [x] 现有 collaboration 恢复测试继续验证 Tool timeout/INDETERMINATE 不自动重放。
- [x] 运行完整 collaboration、恢复、ToolExecutor、PostgreSQL RunStore 与 evaluation 回归测试；再运行 `mvn --batch-mode --no-transfer-progress clean verify`。

## 任务 6：验收、文档和发布

- [x] 量化规划请求的 UTF-8 字节数作为稳定可复核的上下文基线；只报告当前测量，不宣称下降比例或成本节省。
- [x] 更新 README 阶段索引、阶段五需求/实施计划状态和路线图事实；功能未验收前不标记阶段完成。
- [x] 本机完整测试、Prometheus 规则、镜像 pin、Kustomize、`git diff --check` 全部通过；记录实际测试数量和跳过数。
- [x] 按项目资源约束核验 L5 与本机空间，完成当前提交 GitHub Actions 后再部署 SHA 固定镜像到 L5；仅做有界启动/基础设施检查，不调用真实模型、不写入示例/业务 Run，验证 Worker/DB/OTel/Prometheus/Grafana 与数据未变。
- [x] 验收通过后新增 `phase-05-summary.html`（分层架构图 + 执行数据流），更新 README 链接；提交、推送并检查对应 CI。
- [x] 阶段五已验收范围和仍未覆盖的企业工程缺口分开说明；不得声称生产级或 exactly-once。

## 当前执行记录

- 基线分支：`main`，阶段五分支：`codex/phase-05-worker-contract`，工作树：`~/.config/superpowers/worktrees/MyAi/phase-05-worker-contract`。
- 基线命令：`DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn --batch-mode --no-transfer-progress clean verify`。
- 基线结果：BUILD SUCCESS，204 项通过，0 失败、0 错误、0 跳过。
- 阶段五定向验证：`mvn --batch-mode --no-transfer-progress -Dtest=WorkerContractValidatorTest,WorkerRegistryContractTest,CollaborativeWorkflowTest,FileCollaborativeRecoveryTest test`，38 项通过，0 失败、0 错误、0 跳过；新增恢复边界回归后 `CollaborativeWorkflowTest` 为 29 项通过。
- 阶段五完整本机验证：`DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn --batch-mode --no-transfer-progress clean verify`，BUILD SUCCESS，218 项通过，0 失败、0 错误、0 跳过；PostgreSQL 与 OTel Testcontainers 集成测试实际执行。
- 部署配置检查：`bash ops/tests/prometheus-alerts-test.sh` 通过；`bash ops/tests/image-digests-test.sh` 五个固定镜像摘要均通过；`kubectl kustomize deploy/overlays/l5` 通过；`git diff --check` 通过。
- 确定性 Fake 规划样例记录的规划消息内容为 1,356 UTF-8 字节；字段只统计规划消息内容，不包含 SDK 序列化/传输开销。没有可比历史样例，不据此推断 Token 或成本变化。
- 当前代码提交 `09178a55fe44e90858ff20ea33ede87a2d44bc26` 的 GitHub Actions run [44002315867](https://github.com/ly416123/myai/actions/runs/44002315867) 成功；`verify` 中 Maven 218 项、Gitleaks、Prometheus 规则测试、镜像 pin、镜像构建/扫描与仓库配置扫描均通过。默认分支专属 `publish-images` 不在此 feature branch 运行。
- L5 使用同一代码提交的本机 linux/amd64 Docker 构建归档并导入 containerd，再以 SHA 标签部署；两个 Worker 均 Ready，容器内 JAR SHA-256 为 `739c7aa684f37ec9e215d26ad6709fe64207db5fb82fcc30c4d512c771c4b436`。这是可核对的同提交本机产物，不声称为 GHCR/CI 发布镜像。
- L5 有界验收时 Node 为 Ready，CPU 434m（10%）、内存 3973Mi（54%）；Worker 2/2 Ready，PostgreSQL schema V7，Run 分布 `CANCELLED=1, FAILED=1, SUCCEEDED=10` 与部署前一致；Prometheus 10/10 rules healthy、2/2 targets up、0 active alerts，Tempo `/ready` 与 Grafana `/api/health` 正常，全部 MyAI Pod Ready、PVC Bound，其他命名空间工作负载未修改。未调用真实模型或提交 Run。
- 部署时第一次使用的 OCI 归档触发 containerd 临时 `import-日期` 名称问题；回滚期间旧 Worker 保持 Ready。重新导出包含 GHCR `RepoTags` 的 Docker 归档并清理本次旧 CRI 缓存后部署成功。临时导入 Pod 已删除；不触碰 PVC、旧镜像或其他 namespace。
- 当前阶段五代码、功能验收、CI 与 L5 有界部署检查已通过；完整证据见[阶段五总结](../stages/phase-05-summary.html)。
- 本计划各任务的完成情况以目标代码/测试证据更新；计划不是已交付功能的声明。
