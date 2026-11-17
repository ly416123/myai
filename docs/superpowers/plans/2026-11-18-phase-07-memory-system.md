# 阶段七结构化记忆实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 executing-plans 逐任务实现此计划。步骤使用复选框语法跟踪进度。

**目标：** 在阶段六基线上交付显式管理、PostgreSQL 持久化、可检索并固定到 Run 检查点的偏好/事实记忆。

**架构：** MemoryService 在 Run 首次保存前从 MemoryStore 读取并构造有界 MemoryContextSnapshot；RunStore 持久化快照；Harness 只注入该快照，不直接访问记忆库。CLI `/memory` 命令显式维护记忆，协作 Workflow 不把完整记忆库广播给 Worker。

**技术栈：** Java 21、Spring Boot、PostgreSQL 16、Flyway、Jackson、Micrometer、JUnit 5、Testcontainers。

---

## 文件与职责

### 新增

- `src/main/java/com/example/agent/memory/MemoryKind.java`：允许的记忆类型。
- `src/main/java/com/example/agent/memory/MemoryEntry.java`：持久化条目和值版本。
- `src/main/java/com/example/agent/memory/MemoryEvent.java`：不含正文/key 的管理事件。
- `src/main/java/com/example/agent/memory/MemoryContextSnapshot.java`：固定到 Run 的检索内容及策略元数据。
- `src/main/java/com/example/agent/memory/MemoryStore.java`：存储端口。
- `src/main/java/com/example/agent/memory/InMemoryMemoryStore.java`：确定性单测 Fake。
- `src/main/java/com/example/agent/memory/PostgresMemoryStore.java`：PostgreSQL 实现及事务边界。
- `src/main/java/com/example/agent/memory/MemoryRetriever.java`：Unicode 规范化、中文二元组排序和预算裁剪。
- `src/main/java/com/example/agent/memory/MemoryService.java`：显式 CRUD、候选解析与脱敏失败策略。
- `src/main/java/com/example/agent/memory/MemoryContextProvider.java`：Run 创建时创建快照的应用端口。
- `src/main/java/com/example/agent/memory/MemoryPrompt.java`：把已选条目渲染成明确标注为非指令的 system context。
- `src/main/resources/db/migration/V13__create_agent_memory_store.sql`：记忆与元数据审计表。
- `src/test/java/com/example/agent/memory/MemoryRetrieverTest.java`：确定性排序和预算测试。
- `src/test/java/com/example/agent/memory/MemoryServiceTest.java`：CRUD、限制、冲突与故障策略测试。
- `src/test/java/com/example/agent/memory/PostgresMemoryStoreTest.java`：Testcontainers 迁移、事务、并发和审计测试。

### 修改

- `src/main/java/com/example/agent/workflow/RunRecord.java`：增加可空快照及 JSON 访问器。
- `src/main/java/com/example/agent/app/RunSubmissionService.java`：队列 Run 首次落库前固定快照。
- `src/main/java/com/example/agent/runtime/AgentRuntime.java`：新同步 Run 固定快照，恢复不重新检索；每轮模型请求注入快照。
- `src/main/java/com/example/agent/collaboration/CollaborativeWorkflow.java`：父 Run 固定快照，仅 Supervisor 使用；Worker 请求不附加整份记忆。
- `src/main/java/com/example/agent/AgentProperties.java`、`src/main/resources/application.yml`：namespace 与有硬上限的检索预算。
- `src/main/java/com/example/agent/AgentConfiguration.java`：组装存储、服务、注入点和 CLI 依赖。
- `src/main/java/com/example/agent/Main.java`：解析并执行显式 `/memory` 命令。
- `src/main/java/com/example/agent/observability/AgentTelemetry.java`：记录低基数操作指标，不含 namespace/key/query/content。
- 对应的 `MainTest`、`RunSubmissionServiceTest`、`AgentRuntimeTest`、`CollaborativeWorkflowTest`、`RunRecordTest`：入口、快照和恢复回归。
- `docs/security/data-inventory.md`：记录记忆正文和 Run 快照的数据存储边界及删除限制。
- `README.md`：添加阶段七总结索引占位（验收通过后才链接正式总结）、CLI 使用与评测范围。
- `docs/stages/phase-07-summary.html`：仅在完成验收后创建，记录实际证据和阶段时点架构图。

## 任务 1：记忆模型与确定性检索

**文件：**
- 创建：`src/test/java/com/example/agent/memory/MemoryRetrieverTest.java`
- 创建：`src/main/java/com/example/agent/memory/MemoryKind.java`
- 创建：`src/main/java/com/example/agent/memory/MemoryEntry.java`
- 创建：`src/main/java/com/example/agent/memory/MemoryContextSnapshot.java`
- 创建：`src/main/java/com/example/agent/memory/MemoryRetriever.java`
- 创建：`src/main/java/com/example/agent/memory/MemoryPrompt.java`

- [x] **步骤 1：先写排名、中文和预算的失败测试**

```java
@Test
void ranksExactKeyThenChineseTextOverlapAndUsesStableTieBreak() {
    var entries = List.of(
            entry("c", "travel.city", "北京偏好步行", 1),
            entry("b", "visit.city", "北京旅行路线", 1),
            entry("a", "trip.city", "北京旅行路线", 1));

    var result = new MemoryRetriever(5, 2_000).retrieve("travel.city 北京路线", entries);

    assertEquals(List.of("travel.city", "trip.city", "visit.city"), result.entries().stream()
            .map(MemoryEntry::key).toList());
}

@Test
void capsEntryCountAndCountsSupplementaryCharactersAsOneCodePoint() {
    var result = new MemoryRetriever(2, 2_000).retrieve("北京", List.of(
            entry("a", "city", "北京 😀", 1), entry("b", "route", "北京路线", 1),
            entry("c", "weather", "北京天气", 1)));

    assertEquals(2, result.entries().size());
    String rendered = MemoryPrompt.render(result);
    assertEquals(rendered.codePointCount(0, rendered.length()), result.characterCount());
    assertTrue(result.characterCount() <= 2_000);
}
```

- [x] **步骤 2：运行对应测试确认因类型/实现缺失而失败**

运行：`mvn -Dtest=MemoryRetrieverTest test`

预期：编译失败，缺少新模型类型；不能因测试代码自身语法错误失败。

- [x] **步骤 3：实现模型和确定性 Unicode 检索**

实现 `MemoryKind { PREFERENCE, FACT }`。`MemoryEntry` 是不可变 record，字段为 `UUID memoryId, String namespace, String key, MemoryKind kind, String content, long version, Instant createdAt, Instant updatedAt`。`MemoryContextSnapshot` 保存 `boolean degraded, String policyVersion, List<MemoryEntry> entries, int characterCount`，构造时防御性复制。

`MemoryRetriever.retrieve(String query, List<MemoryEntry> entries)` 以 NFKC + `Locale.ROOT` 规范化；key 精确匹配排第一，其次对 ASCII 字母数字按 token、连续汉字按相邻二元组计算 Jaccard 重合分数，再按 memory UUID 稳定排序。格式化预算按真正注入的 `MemoryPrompt` Unicode 码点数计算（Java 使用 `String.codePointCount`），超限即跳过该条而继续尝试更短条目；最多 5 条且总计不超过配置字符数。

- [x] **步骤 4：运行单测确认通过**

运行：`mvn -Dtest=MemoryRetrieverTest test`

预期：新增测试全部通过。

- [x] **步骤 5：提交任务 1**

```bash
git add src/main/java/com/example/agent/memory src/test/java/com/example/agent/memory/MemoryRetrieverTest.java
git commit -m "feat(记忆): 添加结构化条目与确定性检索（任务1）"
```

## 任务 2：PostgreSQL 存储、迁移与并发语义

**文件：**
- 创建：`src/test/java/com/example/agent/memory/PostgresMemoryStoreTest.java`
- 创建：`src/main/java/com/example/agent/memory/MemoryEvent.java`
- 创建：`src/main/java/com/example/agent/memory/MemoryStore.java`
- 创建：`src/main/java/com/example/agent/memory/InMemoryMemoryStore.java`
- 创建：`src/main/java/com/example/agent/memory/PostgresMemoryStore.java`
- 创建：`src/main/resources/db/migration/V13__create_agent_memory_store.sql`

- [x] **步骤 1：先写 PostgreSQL 行为测试**

```java
@Test
void updateIsVersionCheckedAndAuditNeverStoresMemoryText() {
    var created = store.create("default", "answer.language", MemoryKind.PREFERENCE, "回答使用中文");
    assertTrue(store.update("default", created.memoryId(), created.version(),
            MemoryKind.PREFERENCE, "默认使用简体中文").isPresent());
    assertTrue(store.update("default", created.memoryId(), created.version(),
            MemoryKind.PREFERENCE, "过期并发值").isEmpty());
    assertEquals(2, store.events(created.memoryId()).size());
    Set<String> columns = Set.copyOf(jdbc.queryForList("""
            SELECT column_name FROM information_schema.columns
            WHERE table_name = 'agent_memory_event'
            """, String.class));
    assertFalse(columns.contains("content"));
    assertFalse(columns.contains("memory_key"));
}
```

- [x] **步骤 2：运行集成测试确认迁移/存储能力缺失**

运行：`mvn -Dtest=PostgresMemoryStoreTest test`

预期：测试编译失败，缺少 store/迁移类型；Testcontainers 若 Docker 不可用则记录环境阻塞，不以跳过的测试宣称通过。

- [x] **步骤 3：实现 Flyway V13 与 Store 接口**

迁移创建 `agent_memory`：UUID 主键、namespace、key、kind、正文、version、status、时间；活动条目用部分唯一索引 `(namespace, memory_key) WHERE status='ACTIVE'`，检索索引 `(namespace, status)`。`agent_memory_event` 不设正文或 key 字段，按 append-only 设计记录 UUID、memory UUID、operation、version、occurred_at、固定来源。更新用版本条件更新；删除在同一事务内写事件并将状态设为 `DELETED`、清空 key/正文，允许之后用同一 key 新建。Store 不记录异常原文；MemoryService 将存储错误映射为脱敏的领域错误，保留唯一键冲突供显式冲突提示。

Store 接口提供 `create`、`update(expectedVersion)`、`delete(expectedVersion)`、`findByKey`、`listActive(limit)`、`countActive`、`events(memoryId)`。InMemory Fake 使用锁实现同样唯一性、tombstone 和版本条件。

- [x] **步骤 4：运行 PostgreSQL 测试确认通过**

运行：`mvn -Dtest=PostgresMemoryStoreTest test`

预期：迁移、插入、唯一冲突、并发版本冲突、删除审计、append-only 和事务回滚测试通过，审计表查询不到正文或 key。

- [x] **步骤 5：提交任务 2**

```bash
git add src/main/java/com/example/agent/memory src/main/resources/db/migration/V13__create_agent_memory_store.sql src/test/java/com/example/agent/memory/PostgresMemoryStoreTest.java
git commit -m "feat(记忆): 添加 PostgreSQL 存储和迁移（任务2）"
```

## 任务 3：MemoryService、namespace 限额与脱敏故障策略

**文件：**
- 创建：`src/test/java/com/example/agent/memory/MemoryServiceTest.java`
- 创建：`src/main/java/com/example/agent/memory/MemoryService.java`
- 创建：`src/main/java/com/example/agent/memory/MemoryContextProvider.java`
- 创建：`src/main/java/com/example/agent/memory/MemoryPrompt.java`

- [ ] **步骤 1：先写显式 CRUD、限制与降级测试**

```java
@Test
void createDoesNotOverwriteAndRetrievalFailureReturnsDegradedEmptySnapshot() {
    service.remember("answer.language", MemoryKind.PREFERENCE, "回答使用中文");
    assertThrows(MemoryAlreadyExistsException.class,
            () -> service.remember("answer.language", MemoryKind.PREFERENCE, "其他值"));
    assertTrue(service.update("answer.language", MemoryKind.PREFERENCE, "使用简体中文").isPresent());

    store.failReads(true);
    var snapshot = service.snapshot("新任务");
    assertTrue(snapshot.degraded());
    assertTrue(snapshot.entries().isEmpty());
}
```

- [ ] **步骤 2：运行测试确认服务行为尚不存在**

运行：`mvn -Dtest=MemoryServiceTest test`

预期：测试编译失败，缺少服务与异常类型。

- [ ] **步骤 3：实现规则与安全错误映射**

`MemoryService` 验证 key、kind、正文非空、字符长度不超过 2,000；新增前检查 namespace 活动条目最多 500，随后仍由唯一约束处理竞态；更新/删除都先读取当前 version 并 CAS。CRUD 存储异常转换为无原文的 `MemoryOperationException`。`snapshot(query)` 捕获读取失败后返回 `degraded=true` 空快照；检索候选数上限为 500。`MemoryContextProvider` 只暴露 `snapshot(query)`，`MemoryPrompt.render(snapshot)` 产出唯一可测的 system 内容并按预算计数。

- [ ] **步骤 4：运行服务测试确认通过**

运行：`mvn -Dtest=MemoryServiceTest test`

预期：显式新增/更新/删除、无覆盖、500 条上限、2,000 字符校验、读取降级和管理写入 fail-closed 测试通过。

- [ ] **步骤 5：提交任务 3**

```bash
git add src/main/java/com/example/agent/memory src/test/java/com/example/agent/memory/MemoryServiceTest.java
git commit -m "feat(记忆): 实现显式记忆管理策略（任务3）"
```

## 任务 4：Run 快照与同步/队列入口一致性

**文件：**
- 修改：`src/main/java/com/example/agent/workflow/RunRecord.java`
- 修改：`src/main/java/com/example/agent/runtime/AgentRuntime.java`
- 修改：`src/main/java/com/example/agent/collaboration/CollaborativeWorkflow.java`
- 修改：`src/main/java/com/example/agent/app/RunSubmissionService.java`
- 测试：`src/test/java/com/example/agent/workflow/RunRecordTest.java`
- 测试：`src/test/java/com/example/agent/app/RunSubmissionServiceTest.java`
- 测试：`src/test/java/com/example/agent/runtime/AgentRuntimeTest.java`
- 测试：`src/test/java/com/example/agent/collaboration/CollaborativeWorkflowTest.java`

- [ ] **步骤 1：先写 Run JSON 往返与各入口快照固定测试**

```java
@Test
void queuedRunPinsMemoryBeforeFirstStoreWriteAndResumeDoesNotResolveAgain() {
    AtomicInteger resolutions = new AtomicInteger();
    MemoryContextProvider provider = query -> {
        resolutions.incrementAndGet();
        return snapshot("answer.language", 1, "回答使用中文");
    };
    RunSubmissionService submissions = submissions(store, provider);

    var queued = submissions.submitSingle("用中文总结");
    assertEquals("回答使用中文", store.find(queued.runId()).orElseThrow()
            .memoryContextSnapshot().entries().getFirst().content());
    assertEquals(1, resolutions.get());
}
```

- [ ] **步骤 2：运行测试确认 Run 快照字段尚不存在**

运行：`mvn -Dtest=RunRecordTest,RunSubmissionServiceTest,AgentRuntimeTest,CollaborativeWorkflowTest test`

预期：编译失败，缺少快照属性/Provider 注入构造器。

- [ ] **步骤 3：加可空 Run 快照并在首次保存前解析**

`RunRecord` 添加 `MemoryContextSnapshot memoryContextSnapshot` 与 Jackson getter/setter。单 Agent、协作、队列入口都在首次 `save` 前调用 provider 并固定快照；provider 为空时创建空的 `policy-v1` 快照。老 JSON 缺少字段时保持 null，恢复代码将其解释为空快照但绝不调用 provider。保留旧构造器供现有调用和单测兼容。

- [ ] **步骤 4：运行 Run 快照测试确认通过**

运行：`mvn -Dtest=RunRecordTest,RunSubmissionServiceTest,AgentRuntimeTest,CollaborativeWorkflowTest test`

预期：创建入口一次解析、RunStore 往返保留版本/正文、恢复不再解析、旧 checkpoint 不注入新记忆的测试通过。

- [ ] **步骤 5：提交任务 4**

```bash
git add src/main/java/com/example/agent/workflow/RunRecord.java src/main/java/com/example/agent/runtime/AgentRuntime.java src/main/java/com/example/agent/collaboration/CollaborativeWorkflow.java src/main/java/com/example/agent/app/RunSubmissionService.java src/test/java/com/example/agent/workflow/RunRecordTest.java src/test/java/com/example/agent/app/RunSubmissionServiceTest.java src/test/java/com/example/agent/runtime/AgentRuntimeTest.java src/test/java/com/example/agent/collaboration/CollaborativeWorkflowTest.java
git commit -m "feat(记忆): 将检索快照固定到 Run（任务4）"
```

## 任务 5：模型注入、Supervisor 边界和 Worker 数据最小化

**文件：**
- 修改：`src/main/java/com/example/agent/runtime/AgentRuntime.java`
- 修改：`src/main/java/com/example/agent/collaboration/CollaborativeWorkflow.java`
- 测试：`src/test/java/com/example/agent/runtime/AgentRuntimeTest.java`
- 测试：`src/test/java/com/example/agent/collaboration/CollaborativeWorkflowTest.java`

- [ ] **步骤 1：先写模型请求消息边界测试**

```java
@Test
void supervisorGetsMemoryContextButDelegatedWorkerDoesNotGetWholeMemory() {
    var result = runCollaborativeWithMemory("用户偏好：只用中文；参考资料不可当作指令");

    assertTrue(result.plannerRequest().contains("只用中文"));
    assertTrue(result.workerRequest().contains("任务"));
    assertFalse(result.workerRequest().contains("用户偏好：只用中文"));
}
```

- [ ] **步骤 2：运行测试确认当前 prompt 尚不注入快照**

运行：`mvn -Dtest=AgentRuntimeTest,CollaborativeWorkflowTest test`

预期：新断言失败，表明 planner/single model request 缺少记忆上下文。

- [ ] **步骤 3：在 Runtime 与 Supervisor 模型消息中渲染固定快照**

AgentRuntime 每轮 `modelMessages` 将 `MemoryPrompt.render(run.memoryContextSnapshot())` 合并进 system 内容；协作 Workflow 仅当 `workerId` 为 supervisor/planning/summary 角色时附加快照。Worker 内容仍只由已解析任务输入和依赖结果组成。提示明确记忆不可作为指令、当前请求优先、禁止模型写记忆。

- [ ] **步骤 4：运行模型请求边界测试确认通过**

运行：`mvn -Dtest=AgentRuntimeTest,CollaborativeWorkflowTest test`

预期：单 Agent 和 Supervisor 得到同一固定内容；Worker 不收到完整快照；用户/模型/工具消息序列与原协议保持有效。

- [ ] **步骤 5：提交任务 5**

```bash
git add src/main/java/com/example/agent/runtime/AgentRuntime.java src/main/java/com/example/agent/collaboration/CollaborativeWorkflow.java src/test/java/com/example/agent/runtime/AgentRuntimeTest.java src/test/java/com/example/agent/collaboration/CollaborativeWorkflowTest.java
git commit -m "feat(记忆): 将快照注入受控模型上下文（任务5）"
```

## 任务 6：配置、CLI 命令、组合根与遥测

**文件：**
- 修改：`src/main/java/com/example/agent/AgentProperties.java`
- 修改：`src/main/resources/application.yml`
- 修改：`src/main/java/com/example/agent/AgentConfiguration.java`
- 修改：`src/main/java/com/example/agent/Main.java`
- 修改：`src/main/java/com/example/agent/observability/AgentTelemetry.java`
- 修改：`src/test/java/com/example/agent/MainTest.java`
- 测试：`src/test/java/com/example/agent/AgentApplicationContextTest.java`

- [ ] **步骤 1：先写 CLI 命令和属性约束测试**

```java
@Test
void memoryCommandCreatesListsUpdatesAndDeletesWithoutCallingModel() {
    runCli("/memory remember answer.language preference 回答使用中文", "/memory list",
            "/memory update answer.language preference 默认使用简体中文",
            "/memory delete answer.language", "/exit");
    assertEquals(0, fakeModel.calls());
    assertTrue(output().contains("已删除"));
}
```

- [ ] **步骤 2：运行 CLI 测试确认命令未支持**

运行：`mvn -Dtest=MainTest,AgentApplicationContextTest test`

预期：新命令断言失败或应用组合测试暴露缺失 Bean。

- [ ] **步骤 3：实现命令解析、依赖装配和低基数指标**

`AgentProperties` 配 namespace、最大条数、最大字符数，并校验 1..5 与 1..2000 上限。Main 在普通 Run 分支之前识别 `/memory`，以命令、key、kind 为前三段，将剩余原文作为正文，精确解析 `help/list/show/remember/update/delete`；错误输出不回显正文或异常。`AgentConfiguration` 装配 `PostgresMemoryStore`、retriever、service、provider，并注入 Runtime、协作、submission 和 CLI。Telemetry 仅记 operation、result、degraded 三个固定标签与耗时/数量，不记录 key/query/content/namespace。

- [ ] **步骤 4：运行 CLI 与 Spring context 测试确认通过**

运行：`mvn -Dtest=MainTest,AgentApplicationContextTest test`

预期：显式命令完成 CRUD 且不调用模型；应用上下文启动并使用 Flyway/PostgreSQL Store；不合法参数不落库。

- [ ] **步骤 5：提交任务 6**

```bash
git add src/main/java/com/example/agent/AgentProperties.java src/main/resources/application.yml src/main/java/com/example/agent/AgentConfiguration.java src/main/java/com/example/agent/Main.java src/main/java/com/example/agent/observability/AgentTelemetry.java src/test/java/com/example/agent/MainTest.java src/test/java/com/example/agent/AgentApplicationContextTest.java
git commit -m "feat(记忆): 接入 CLI 配置与运行观测（任务6）"
```

## 任务 7：数据盘点、README 和阶段七验收

**文件：**
- 修改：`docs/security/data-inventory.md`
- 修改：`README.md`
- 创建：`docs/stages/phase-07-summary.html`（仅验收通过后）
- 创建：`src/test/java/com/example/agent/memory/MemoryPrivacyContractTest.java`

- [ ] **步骤 1：先写日志/遥测脱敏与完整生命周期回归测试**

```java
@Test
void telemetryAndAuditDoNotContainMemoryKeyQueryOrContent() {
    service.remember("private.note", MemoryKind.FACT, "private-value-7f9a");
    service.snapshot("private-value-7f9a");
    String exposed = captureLogsMetricsAndAudit();
    assertFalse(exposed.contains("private.note"));
    assertFalse(exposed.contains("private-value-7f9a"));
}
```

- [ ] **步骤 2：运行隐私与回归测试确认失败点真实**

运行：`mvn -Dtest=MemoryPrivacyContractTest test`

预期：新测试因尚未有测试夹具或对应指标/审计契约而失败；如测试本身不成立，先修正测试。

- [ ] **步骤 3：完善集成回归及文档**

README 增加 `/memory` 使用示例、`AGENT_MEMORY_NAMESPACE` 默认值、删除/Run 快照语义、Fake/确定性/PostgreSQL 验收命令和评测限制；data inventory 标明 memory 表与 Run checkpoint 存储正文、普通日志和指标不含正文、历史快照按 Run retention 保留。所有完整阶段测试通过后生成中文 HTML 阶段总结，含阶段实际范围、遗留问题影响、实现与替代方案、真实命令/数量/结果、限制及阶段完成时分层架构图；README 更新正式 HTML 链接。

- [ ] **步骤 4：运行阶段验收命令**

运行：`mvn clean verify`

预期：全套单元、Testcontainers PostgreSQL/OTel 集成和构建均通过，无失败、错误或意外跳过；记录实际测试数和 Docker 环境。

- [ ] **步骤 5：差异和 HTML 结构复核**

运行：`git diff --check`；使用 JDK/Python 标准 XML/HTML parser 解析 `docs/stages/phase-07-summary.html`；确认 README 索引链接存在，架构图描述阶段七真实交付而非目标架构。

预期：无空白错误、HTML 可解析、索引有效、阶段总结证据与命令输出一致。

- [ ] **步骤 6：提交验收文档**

```bash
git add README.md docs/security/data-inventory.md docs/stages/phase-07-summary.html src/test/java/com/example/agent/memory/MemoryPrivacyContractTest.java
git commit -m "docs(阶段七): 记录记忆能力验收结果（任务7）"
```

## 完成前核对

- 所有记忆内容仅通过显式 `/memory` 命令变更。
- 创建/排队 Run 只解析一次；同步、协作和队列入口一致；恢复不重新检索。
- 记忆注入有固定条数和字符预算，Supervisor 以外 Worker 不收到完整快照。
- 管理写失败不会伪报成功；检索失败有 `degraded` 快照且继续任务。
- 删除阻止未来读取，但不声称擦除 checkpoint/provider 已持有的数据。
- 日志、Trace、metric labels 和审计均不含正文、key、query 或异常原文。
- 所有测试使用 Fake/确定性工具或 Testcontainers，不依赖真实模型/API Key。
- 只有验收证据齐备后才把阶段标记为完成并创建 HTML 总结；阶段六 PR 与主分支发布不纳入本阶段。
