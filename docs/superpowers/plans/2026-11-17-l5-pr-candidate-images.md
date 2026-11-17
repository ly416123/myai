# L5 PR 候选镜像实施计划

> **执行方式：** 当前会话内联执行；按项目约束保留 `main` 的审批与 CI 门槛。

**目标：** 让已通过 CI 的功能分支可以发布独立候选镜像并供 L5 受控验收，不必等 PR 合入 `main`。

**架构：** `verify` 仍先于镜像发布。默认分支发布原有 `<commit-sha>` 正式标签；非默认分支 push 发布含提交 SHA、run ID 与 attempt 的唯一 `candidate-<commit-sha>-<run-id>-<attempt>` 标签。候选镜像经 GHCR 拉取后，通过已验证的 SSH/containerd 流程导入 L5；PR Auto-merge 仍遵守审批规则。

**技术栈：** GitHub Actions、GHCR、Docker、K3s/containerd、JUnit 5、现有运维手册。

---

### 任务 1：固定候选镜像发布策略的回归测试

**文件：** `src/test/java/com/example/agent/ContainerDeploymentContractTest.java`

- [x] 增加断言：`publish-images` 依赖 `verify`；默认分支保留 SHA 标签；其他分支使用唯一候选标签；PR 事件不触发发布。
- [x] 运行对应测试并确认因当前工作流尚无候选标签逻辑而失败。

### 任务 2：实现经过验证的分支候选发布

**文件：** `.github/workflows/ci.yml`

- [x] 让 `publish-images` 在 branch push 事件运行，并继续依赖成功的 `verify`。
- [x] 默认分支使用现有 SHA 标签，其他分支使用含提交 SHA、run ID 与 attempt 的唯一候选标签。
- [x] 保留两个镜像的漏洞扫描先于推送；pull_request 事件不触发发布。
- [x] 运行 Actionlint 与策略回归测试。

### 任务 3：记录 L5 候选部署与 Auto-merge 边界

**文件：** `docs/operations/l5-runbook.md`

- [x] 增加核对同一提交 `verify` 与候选发布 Job 的步骤，以及 GHCR 拉取、SSH 导入 containerd、Worker 滚动和迁移状态检查步骤。
- [x] 明确候选镜像只用于受控 L5 验收，正式版本仍从 `main` 发布；`main` 的必需审批不会被 Auto-merge 绕过。
- [x] 不修改其他 namespace、数据库 PVC 或 GitHub 分支保护规则。

### 验收

- [x] 运行 `mvn --batch-mode --no-transfer-progress -Dtest=ContainerDeploymentContractTest test`（17 项通过）。
- [x] 运行 `actionlint -color .github/workflows/ci.yml`（使用 release SHA-256 校验的 v1.7.12 darwin/arm64 二进制）。
- [x] 运行全量 `mvn --batch-mode --no-transfer-progress clean verify`（258 项通过），以及 `git diff --check` 与 `kubectl kustomize deploy/overlays/l5`。
- [ ] 推送后确认 PR 分支的 `verify` 与候选 `publish-images` 均成功，记录产出的 SHA 标签，再开始 L5 部署前重新检查资源。
